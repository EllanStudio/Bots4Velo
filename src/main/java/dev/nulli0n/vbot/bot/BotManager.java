package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolDetectionService;
import dev.nulli0n.vbot.protocol.StatusProtocolDetector;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import org.slf4j.Logger;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public final class BotManager implements AutoCloseable {
    private final BotPluginConfig config;
    private final Logger logger;
    private final ScheduledExecutorService executor;
    private final ProtocolDetectionService protocolDetectionService;
    private final Function<String, Optional<String>> currentServer;
    private final TransportRegistry transportRegistry;
    private final ConnectionRateLimiter connectionRateLimiter;
    private final Clock clock;
    private final MaintenanceHoldRegistry maintenanceHolds;
    private final Map<String, BotSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, ScheduledFuture<?>> pendingActivations = new ConcurrentHashMap<>();
    private final Map<String, ActivationSnapshot> pendingActivationSnapshots = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> activationEpochs = new ConcurrentHashMap<>();
    private final Object activationLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean activationsPaused = new AtomicBoolean();
    private final List<Consumer<BotEvent>> eventListeners = new CopyOnWriteArrayList<>();
    private final BiConsumer<String, Boolean> activationDispatchObserver;
    private long nextActivationNanos;

    public BotManager(BotPluginConfig config, Logger logger) {
        this(config, logger, (definition, endpoint) -> new StatusProtocolDetector().detect(endpoint));
    }

    public BotManager(BotPluginConfig config, Logger logger,
                      ProtocolDetectionService protocolDetectionService) {
        this(config, logger, protocolDetectionService, Clock.systemUTC());
    }

    public BotManager(BotPluginConfig config, Logger logger,
                      ProtocolDetectionService protocolDetectionService,
                      Function<String, Optional<String>> currentServer) {
        this(config, logger, protocolDetectionService, Clock.systemUTC(),
            (ignoredId, ignoredComplete) -> { }, currentServer);
    }

    BotManager(BotPluginConfig config, Logger logger,
               ProtocolDetectionService protocolDetectionService, Clock clock) {
        this(config, logger, protocolDetectionService, clock, (ignoredId, ignoredComplete) -> { });
    }

    BotManager(BotPluginConfig config, Logger logger, ProtocolDetectionService protocolDetectionService,
               Clock clock, BiConsumer<String, Boolean> activationDispatchObserver) {
        this(config, logger, protocolDetectionService, clock, activationDispatchObserver, null);
    }

    private BotManager(BotPluginConfig config, Logger logger, ProtocolDetectionService protocolDetectionService,
                       Clock clock, BiConsumer<String, Boolean> activationDispatchObserver,
                       Function<String, Optional<String>> currentServer) {
        this.config = config;
        this.logger = logger;
        this.protocolDetectionService = protocolDetectionService;
        this.currentServer = currentServer;
        this.clock = clock;
        this.activationDispatchObserver = Objects.requireNonNull(
            activationDispatchObserver, "activationDispatchObserver");
        this.maintenanceHolds = new MaintenanceHoldRegistry(clock);
        AtomicInteger threadId = new AtomicInteger();
        int threads = Math.max(2, Math.min(8, config.bots().size() + 1));
        this.executor = Executors.newScheduledThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "vbot-worker-" + threadId.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        this.transportRegistry = new TransportRegistry();
        this.connectionRateLimiter = new ConnectionRateLimiter(config.runtime().spawnIntervalMillis());
        config.bots().forEach((key, definition) -> {
            sessions.put(key, createSession(definition));
            activationEpochs.put(key, new AtomicLong());
        });
    }

    public void startEnabled() {
        for (BotSession session : sortedSessions()) {
            if (session.definition().enabled()) {
                scheduleActivation(session, config.runtime().autoStartDelayMillis(), session::start,
                    ActivationKind.START);
            }
        }
    }

    /** Pauses and invalidates manager-level activations during a reload handoff. */
    public void pauseActivations() {
        synchronized (activationLock) {
            if (closed.get()) {
                return;
            }
            activationsPaused.set(true);
            List.copyOf(pendingActivations.keySet()).forEach(this::invalidateActivationLocked);
        }
    }

    /** Opens a paused manager after the proxy confirms old bot players are gone. */
    public boolean resumeActivations() {
        return !closed.get() && activationsPaused.compareAndSet(true, false);
    }

    public boolean activationsPaused() {
        return activationsPaused.get();
    }

    public Optional<BotSession> find(String id) {
        return Optional.ofNullable(sessions.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<BotSnapshot> snapshots() {
        return sortedSessions().stream().map(BotSession::snapshot).toList();
    }

    /** Returns the stable wall-clock ETAs of manager-level activations. */
    public List<ActivationSnapshot> activationSnapshots() {
        synchronized (activationLock) {
            return pendingActivationSnapshots.values().stream()
                .sorted(Comparator.comparing(ActivationSnapshot::scheduledAt)
                    .thenComparing(ActivationSnapshot::botId, String.CASE_INSENSITIVE_ORDER))
                .toList();
        }
    }

    public Optional<ActivationSnapshot> pendingActivation(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        synchronized (activationLock) {
            return Optional.ofNullable(pendingActivationSnapshots.get(id.trim().toLowerCase(Locale.ROOT)));
        }
    }

    public List<MaintenanceHoldSnapshot> holdSnapshots() {
        return maintenanceHolds.snapshots();
    }

    public Optional<MaintenanceHoldSnapshot> holdSnapshot(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return maintenanceHolds.snapshot(id);
    }

    public boolean isHeld(String id) {
        return holdSnapshot(id).isPresent();
    }

    /** Returns stable bot sessions for optional integrations such as TAB. */
    public List<BotSession> sessions() {
        return sortedSessions();
    }

    /**
     * Resolves a stable, local bot selector. Server selectors are completed by
     * the Velocity-facing plugin because only it can observe current backend
     * connections. Supported local selectors are an id, {@code all},
     * {@code @group:<name>}, {@code @tag:<name>} and the compact
     * {@code @<name>} form (group or tag).
     */
    public List<BotSession> select(String selector) {
        String normalized = selector == null ? "" : selector.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return List.of();
        }
        if (normalized.equals("all") || normalized.equals("@all") || normalized.equals("*")) {
            return sortedSessions();
        }
        if (!normalized.startsWith("@")) {
            return find(normalized).map(List::of).orElseGet(List::of);
        }
        String expression = normalized.substring(1);
        if (expression.startsWith("group:")) {
            return byLabel(expression.substring("group:".length()), true);
        }
        if (expression.startsWith("tag:")) {
            return byLabel(expression.substring("tag:".length()), false);
        }
        if (expression.startsWith("server:")) {
            return sortedSessions();
        }
        return sortedSessions().stream()
            .filter(session -> session.definition().groups().contains(expression)
                || session.definition().tags().contains(expression))
            .toList();
    }

    public List<String> groups() {
        return sortedSessions().stream().flatMap(session -> session.definition().groups().stream())
            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public List<String> tags() {
        return sortedSessions().stream().flatMap(session -> session.definition().tags().stream())
            .distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public void addEventListener(Consumer<BotEvent> listener) {
        eventListeners.add(listener);
    }

    public void removeEventListener(Consumer<BotEvent> listener) {
        eventListeners.remove(listener);
    }

    public boolean start(String id) {
        return find(id).map(session -> session.nextConnectionAttempt().isPresent()
            || replaceActivation(session, session::start, ActivationKind.START)).orElse(false);
    }

    public boolean startAutomatically(String id) {
        return find(id).map(session -> session.nextConnectionAttempt().isPresent()
            || scheduleActivation(session, 0, session::startAutomatically, ActivationKind.START)).orElse(false);
    }

    public boolean stop(String id) {
        BotSession session = find(id).orElse(null);
        if (session == null) {
            return false;
        }
        String key = session.definition().id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            if (closed.get()) {
                return false;
            }
            if (sessions.get(key) != session) {
                return false;
            }
            invalidateActivationLocked(key);
        }
        session.stop();
        return true;
    }

    public boolean reconnect(String id) {
        return find(id).map(session -> {
            if (isHeld(session.definition().id())) {
                return false;
            }
            session.cancelPendingConnectionAttempt();
            return replaceActivation(session, session::reconnectNow, ActivationKind.RECONNECT);
        }).orElse(false);
    }

    public boolean reconnectAutomatically(String id) {
        return find(id).map(session -> session.nextConnectionAttempt().isPresent()
            || scheduleActivation(session, 0, session::reconnectAutomatically, ActivationKind.RECONNECT))
            .orElse(false);
    }

    /**
     * Places one bot on an indefinite maintenance hold. The hold is installed
     * before queued work is cancelled, so a concurrent activation cannot pass
     * the manager gate. The session is always stopped after the gate is set.
     */
    public boolean hold(String id, String reason) {
        return applyHold(id, reason, null, "");
    }

    /** Places one bot on a maintenance hold with a positive TTL. */
    public boolean hold(String id, String reason, Duration ttl) {
        if (ttl == null) {
            throw new IllegalArgumentException("Maintenance hold TTL must be positive");
        }
        return applyHold(id, reason, ttl, "");
    }

    /** Places one bot on an indefinite hold and remembers its last backend. */
    public boolean hold(String id, String reason, String server) {
        return applyHold(id, reason, null, server);
    }

    /** Places one bot on a hold while retaining its last current backend for selector-based resume. */
    public boolean hold(String id, String reason, Duration ttl, String server) {
        if (ttl == null) {
            throw new IllegalArgumentException("Maintenance hold TTL must be positive");
        }
        return applyHold(id, reason, ttl, server);
    }

    private boolean applyHold(String id, String reason, Duration ttl, String server) {
        BotSession session = find(id).orElse(null);
        if (session == null) {
            return false;
        }
        String key = session.definition().id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            if (closed.get() || sessions.get(key) != session) {
                return false;
            }
            if (ttl == null) {
                maintenanceHolds.hold(session.definition().id(), reason, server);
            }
            else {
                maintenanceHolds.hold(session.definition().id(), reason, ttl, server);
            }
            invalidateActivationLocked(key);
        }
        session.stop();
        return true;
    }

    /** Restores an unexpired hold without changing its audit timestamps or expiry. */
    public boolean restoreHold(MaintenanceHoldSnapshot hold) {
        BotSession session = hold == null ? null : find(hold.botId()).orElse(null);
        if (session == null) {
            return false;
        }
        String key = session.definition().id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            if (closed.get() || sessions.get(key) != session || !maintenanceHolds.restore(hold)) {
                return false;
            }
            invalidateActivationLocked(key);
        }
        session.stop();
        return true;
    }

    /** Removes a hold without starting the bot. */
    public boolean resume(String id) {
        if (find(id).isEmpty()) {
            return false;
        }
        synchronized (activationLock) {
            return maintenanceHolds.resume(id);
        }
    }

    public CreateResult create(BotDefinition definition) {
        Objects.requireNonNull(definition, "Bot definition must not be null");
        BotSession session;
        Throwable activationFailure;
        String key = definition.id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            if (closed.get() || activationsPaused.get()) {
                throw new IllegalStateException("Bot manager is not accepting new bots");
            }
            if (sessions.size() >= config.runtime().maximumBots()) {
                return CreateResult.LIMIT_REACHED;
            }
            if (sessions.containsKey(key)) {
                return CreateResult.ALREADY_EXISTS;
            }
            if (sessions.values().stream().anyMatch(existing ->
                existing.definition().username().equalsIgnoreCase(definition.username()))) {
                return CreateResult.ALREADY_EXISTS;
            }
            session = createSession(definition);
            sessions.put(key, session);
            activationEpochs.computeIfAbsent(key, ignored -> new AtomicLong());
            try {
                if (definition.enabled()
                    && !scheduleActivation(session, 0, session::start, ActivationKind.START)) {
                    throw new IllegalStateException("Bot activation could not be scheduled");
                }
                return CreateResult.CREATED;
            }
            catch (RuntimeException | Error failure) {
                sessions.remove(key, session);
                invalidateActivationLocked(key);
                activationEpochs.remove(key);
                maintenanceHolds.remove(key);
                activationFailure = failure;
            }
        }
        try {
            session.stop();
        }
        catch (RuntimeException | LinkageError cleanupFailure) {
            activationFailure.addSuppressed(cleanupFailure);
        }
        if (activationFailure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) activationFailure;
    }

    public boolean remove(String id) {
        String key = id.toLowerCase(Locale.ROOT);
        BotSession removed;
        synchronized (activationLock) {
            removed = sessions.remove(key);
            if (removed == null) {
                return false;
            }
            invalidateActivationLocked(key);
            activationEpochs.remove(key);
            maintenanceHolds.remove(key);
        }
        try {
            removed.stop();
        }
        catch (RuntimeException | LinkageError exception) {
            logger.warn("Bot {} was removed but its transport cleanup failed",
                removed.definition().id(), exception);
        }
        return true;
    }

    public int maximumBots() {
        return config.runtime().maximumBots();
    }

    public boolean command(String id, String command) {
        return find(id).map(session -> session.sendCommand(command)).orElse(false);
    }

    public boolean moveTo(String id, double x, double y, double z) {
        return find(id).map(session -> session.moveTo(x, y, z)).orElse(false);
    }

    public boolean look(String id, float yaw, float pitch) {
        return find(id).map(session -> session.look(yaw, pitch)).orElse(false);
    }

    private List<BotSession> sortedSessions() {
        List<BotSession> result = new ArrayList<>(sessions.values());
        result.sort(Comparator.comparing(session -> session.definition().id(), String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<BotSession> byLabel(String label, boolean group) {
        if (label.isBlank()) {
            return List.of();
        }
        return sortedSessions().stream()
            .filter(session -> (group ? session.definition().groups() : session.definition().tags()).contains(label))
            .toList();
    }

    private BotSession createSession(BotDefinition definition) {
        ProtocolResolver resolver = new ProtocolResolver(config.proxy(), definition, protocolDetectionService);
        return new BotSession(definition, config.proxy(), config.runtime(), resolver,
            transportRegistry, connectionRateLimiter, executor, logger, this::publishEvent,
            () -> closed.get() || maintenanceHolds.isHeld(definition.id()),
            currentServer == null ? null : () -> currentServer.apply(definition.username()));
    }

    private void publishEvent(BotEvent event) {
        for (Consumer<BotEvent> listener : eventListeners) {
            try {
                listener.accept(event);
            }
            catch (RuntimeException exception) {
                logger.warn("Bot event listener failed", exception);
            }
        }
    }

    private boolean scheduleActivation(BotSession session, long minimumDelayMillis, Runnable action,
                                       ActivationKind kind) {
        return scheduleActivation(session, minimumDelayMillis, action, kind, false);
    }

    private boolean replaceActivation(BotSession session, Runnable action, ActivationKind kind) {
        return scheduleActivation(session, 0, action, kind, true);
    }

    private boolean scheduleActivation(BotSession session, long minimumDelayMillis, Runnable action,
                                       ActivationKind kind, boolean replaceExisting) {
        String key = session.definition().id().toLowerCase(Locale.ROOT);
        synchronized (activationLock) {
            if (closed.get() || activationsPaused.get()) {
                return false;
            }
            if (sessions.get(key) != session) {
                return false;
            }
            if (maintenanceHolds.isHeld(key)) {
                return false;
            }
            ScheduledFuture<?> existing = pendingActivations.get(key);
            if (existing != null && !existing.isDone()) {
                if (!replaceExisting) {
                    return true;
                }
                existing.cancel(false);
                pendingActivations.remove(key, existing);
                pendingActivationSnapshots.remove(key);
            }
            else if (existing != null) {
                pendingActivations.remove(key, existing);
                pendingActivationSnapshots.remove(key);
            }
            AtomicLong activationEpoch = activationEpochs.computeIfAbsent(key, ignored -> new AtomicLong());
            long expectedEpoch = activationEpoch.incrementAndGet();
            long now = System.nanoTime();
            long earliest = now + TimeUnit.MILLISECONDS.toNanos(Math.max(0, minimumDelayMillis));
            long scheduledAt = Math.max(earliest, nextActivationNanos);
            nextActivationNanos = scheduledAt
                + TimeUnit.MILLISECONDS.toNanos(config.runtime().spawnIntervalMillis());
            long delayNanos = Math.max(0, scheduledAt - now);
            Instant activationAt = clock.instant().plusNanos(delayNanos);
            AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
            ScheduledFuture<?> future = executor.schedule(() -> {
                boolean execute;
                synchronized (activationLock) {
                    boolean currentActivation = pendingActivations.remove(key, scheduled.get());
                    if (currentActivation) {
                        pendingActivationSnapshots.remove(key);
                    }
                    execute = currentActivation && activationCurrent(
                        key, session, activationEpoch, expectedEpoch);
                }
                if (!execute) {
                    return;
                }
                activationDispatchObserver.accept(session.definition().id(), false);
                try {
                    synchronized (session) {
                        if (!activationCurrent(key, session, activationEpoch, expectedEpoch)) {
                            return;
                        }
                        action.run();
                    }
                }
                finally {
                    activationDispatchObserver.accept(session.definition().id(), true);
                }
            }, delayNanos, TimeUnit.NANOSECONDS);
            scheduled.set(future);
            pendingActivations.put(key, future);
            pendingActivationSnapshots.put(key,
                new ActivationSnapshot(session.definition().id(), activationAt, kind));
            return true;
        }
    }

    private void cancelActivationLocked(String key) {
        ScheduledFuture<?> pending = pendingActivations.remove(key);
        pendingActivationSnapshots.remove(key);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void invalidateActivationLocked(String key) {
        activationEpochs.computeIfAbsent(key, ignored -> new AtomicLong()).incrementAndGet();
        cancelActivationLocked(key);
    }

    private boolean activationCurrent(String key, BotSession session, AtomicLong activationEpoch,
                                      long expectedEpoch) {
        return activationEpoch.get() == expectedEpoch && !closed.get()
            && !activationsPaused.get() && sessions.get(key) == session
            && !maintenanceHolds.isHeld(key);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        synchronized (activationLock) {
            pendingActivations.values().forEach(future -> future.cancel(false));
            pendingActivations.clear();
            pendingActivationSnapshots.clear();
            activationEpochs.values().forEach(AtomicLong::incrementAndGet);
            maintenanceHolds.clear();
        }
        sessions.values().forEach(BotSession::stop);
        // Reload runs on a Velocity command/event thread. All scheduled work is
        // invalid after sessions are stopped, so interrupt it immediately
        // instead of waiting for delayed tasks to reach their due time.
        executor.shutdownNow();
    }

    public enum CreateResult {
        CREATED,
        ALREADY_EXISTS,
        LIMIT_REACHED
    }
}
