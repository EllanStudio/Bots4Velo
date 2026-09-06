package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.BotDefinition;
import dev.nulli0n.vbot.config.BotPluginConfig.ProxyEndpoint;
import dev.nulli0n.vbot.config.BotPluginConfig.RegistrationSecondArgument;
import dev.nulli0n.vbot.config.BotPluginConfig.ResourcePackMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RuntimeConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiInputPurpose;
import dev.nulli0n.vbot.transport.AuthenticationUiProvider;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import org.slf4j.Logger;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

public final class BotSession implements BehaviorTarget {
    private final BotDefinition definition;
    private final ProxyEndpoint endpoint;
    private final RuntimeConfig runtime;
    private final ProtocolResolver protocolResolver;
    private final TransportRegistry transportRegistry;
    private final ConnectionRateLimiter connectionRateLimiter;
    private final ScheduledExecutorService executor;
    private final Logger logger;
    private final ReconnectPolicy reconnectPolicy;
    private final ReconnectStabilityGate reconnectStability;
    private final BotBehaviorRunner behavior;
    private final AuthenticationSettleGate authenticationSettle;
    private final BotEventLog events;
    private final Consumer<BotEvent> eventSink;
    private final BooleanSupplier maintenanceBlocked;
    private final Supplier<Optional<String>> currentServer;
    private final AtomicReference<BotState> state = new AtomicReference<>(BotState.STOPPED);
    private final AtomicBoolean manualStop = new AtomicBoolean(true);
    private final AtomicBoolean authenticationInterventionRequired = new AtomicBoolean();
    private final AtomicInteger reconnectAttempts = new AtomicInteger();
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean loginSent = new AtomicBoolean();
    private final AtomicBoolean registerSent = new AtomicBoolean();
    private final AuthenticationOutcomeGate authenticationOutcome = new AuthenticationOutcomeGate();
    private final AtomicBoolean authenticationContinuationApplied = new AtomicBoolean();
    private final AuthenticationUiFlow authenticationUiFlow = new AuthenticationUiFlow();
    private final AtomicBoolean authenticationCommandUiActive = new AtomicBoolean();
    private final AuthenticationCommandUiTracker authenticationCommandUiTracker =
        new AuthenticationCommandUiTracker();
    private final AtomicReference<AuthenticationUiType> deferredChatAuthenticationPrompt = new AtomicReference<>();
    private final AtomicBoolean playInitialized = new AtomicBoolean();
    private final AtomicInteger playTransitionsThisConnection = new AtomicInteger();
    private final AtomicBoolean serverSwitchPending = new AtomicBoolean();
    private final AtomicBoolean serverSwitchTransitionSeen = new AtomicBoolean();
    private final AtomicInteger serverSwitchAttempts = new AtomicInteger();
    private final AtomicLong playEntries = new AtomicLong();
    private final AtomicLong disconnects = new AtomicLong();
    private final AtomicLong resourcePacksLoaded = new AtomicLong();
    private final AtomicLong authenticationUiPresentations = new AtomicLong();
    private final AtomicLong authenticationUiSubmissions = new AtomicLong();
    private final AtomicReference<String> lastAuthenticationUi = new AtomicReference<>("none");
    private final List<Pattern> loginPrompts;
    private final List<Pattern> registerPrompts;
    private final List<Pattern> successMessages;
    private final List<Pattern> failureMessages;

    private volatile BotTransport transport;
    private volatile ScheduledFuture<?> reconnectTask;
    /** Guarded by this; invalidates scheduled/running session connection attempts. */
    private long connectionAttemptEpoch;
    private volatile ScheduledFuture<?> serverSwitchTask;
    private volatile ScheduledFuture<?> authenticationTimeoutTask;
    private volatile Instant connectedAt;
    private volatile Instant lastPlayAt;
    private volatile Instant lastDisconnectAt;
    private volatile ConnectionAttemptSnapshot nextConnectionAttempt;
    private volatile ProtocolVersion activeProtocolVersion;
    private volatile String activeProtocol = "unresolved";
    private volatile String activeProtocolSource = "unresolved";
    private volatile String lastDisconnectReason = "never connected";
    private volatile String followTarget = "";

    public BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
                      ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
                      ConnectionRateLimiter connectionRateLimiter,
                      ScheduledExecutorService executor, Logger logger) {
        this(definition, endpoint, runtime, protocolResolver, transportRegistry, connectionRateLimiter, executor,
            logger, ignored -> { }, () -> false);
    }

    public BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
                      ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
                      ConnectionRateLimiter connectionRateLimiter,
                      ScheduledExecutorService executor, Logger logger, Consumer<BotEvent> eventSink) {
        this(definition, endpoint, runtime, protocolResolver, transportRegistry, connectionRateLimiter, executor,
            logger, eventSink, () -> false);
    }

    BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
               ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
               ConnectionRateLimiter connectionRateLimiter, ScheduledExecutorService executor,
               Logger logger, Consumer<BotEvent> eventSink, BooleanSupplier maintenanceBlocked) {
        this(definition, endpoint, runtime, protocolResolver, transportRegistry, connectionRateLimiter,
            executor, logger, eventSink, maintenanceBlocked, null);
    }

    BotSession(BotDefinition definition, ProxyEndpoint endpoint, RuntimeConfig runtime,
               ProtocolResolver protocolResolver, TransportRegistry transportRegistry,
               ConnectionRateLimiter connectionRateLimiter, ScheduledExecutorService executor,
               Logger logger, Consumer<BotEvent> eventSink, BooleanSupplier maintenanceBlocked,
               Supplier<Optional<String>> currentServer) {
        this.definition = definition;
        this.endpoint = endpoint;
        this.runtime = runtime;
        this.protocolResolver = protocolResolver;
        this.transportRegistry = transportRegistry;
        this.connectionRateLimiter = connectionRateLimiter;
        this.executor = executor;
        this.logger = logger;
        this.eventSink = eventSink == null ? ignored -> { } : eventSink;
        this.maintenanceBlocked = maintenanceBlocked == null ? () -> false : maintenanceBlocked;
        this.currentServer = currentServer;
        this.events = new BotEventLog(definition.id());
        this.reconnectPolicy = new ReconnectPolicy(runtime.reconnect());
        this.reconnectStability = new ReconnectStabilityGate(executor,
            Duration.ofSeconds(runtime.reconnect().stableResetSeconds()),
            this::resetReconnectAttemptsIfStable);
        this.behavior = new BotBehaviorRunner(this, definition.behavior(), executor, logger);
        this.authenticationSettle = new AuthenticationSettleGate(executor);
        this.loginPrompts = compile(definition.auth().loginPrompts());
        this.registerPrompts = compile(definition.auth().registerPrompts());
        this.successMessages = compile(definition.auth().successMessages());
        this.failureMessages = compile(definition.auth().failureMessages());
    }

    public BotDefinition definition() {
        return definition;
    }

    public BotSnapshot snapshot() {
        long onlineSeconds = connectedAt == null ? 0L : Math.max(0L, Duration.between(connectedAt, Instant.now()).toSeconds());
        return new BotSnapshot(definition.id(), definition.username(), activeProtocol, activeProtocolSource, state.get(),
            reconnectAttempts.get(), connectedAt, playEntries.get(), disconnects.get(),
            resourcePacksLoaded.get(), lastPlayAt, lastDisconnectAt, position(), lastAuthenticationUi.get(),
            authenticationUiPresentations.get(), authenticationUiSubmissions.get(), lastDisconnectReason,
            behavior.snapshot(), onlineSeconds, FailureCategory.classify(lastDisconnectReason), events.snapshot());
    }

    /** Estimated time of the next connection attempt, if one is currently scheduled. */
    public Optional<Instant> nextConnectionAttemptAt() {
        return nextConnectionAttempt().map(ConnectionAttemptSnapshot::scheduledAt);
    }

    /** Estimated time and operation kind of the next session-level connection attempt. */
    public Optional<ConnectionAttemptSnapshot> nextConnectionAttempt() {
        return Optional.ofNullable(nextConnectionAttempt);
    }

    /** Cancels a session retry when an operator replacement reconnect is queued. */
    synchronized void cancelPendingConnectionAttempt() {
        cancelReconnect();
    }

    public synchronized void start() {
        if (maintenanceBlocked.getAsBoolean() || !connectionStartAllowed(state.get())) {
            return;
        }
        authenticationInterventionRequired.set(false);
        startInternal();
    }

    /**
     * Starts a bot for a scheduler or presence rule without bypassing a
     * fail-closed authentication result. An operator can still use start or
     * reconnect after correcting the account or authentication configuration.
     */
    public synchronized void startAutomatically() {
        if (maintenanceBlocked.getAsBoolean()
            || !automaticStartAllowed(state.get(), authenticationInterventionRequired.get())) {
            return;
        }
        startInternal();
    }

    static boolean automaticStartAllowed(BotState current, boolean authenticationInterventionRequired) {
        return automaticRecoveryAllowed(authenticationInterventionRequired) && connectionStartAllowed(current);
    }

    static boolean automaticRecoveryAllowed(boolean authenticationInterventionRequired) {
        return !authenticationInterventionRequired;
    }

    private static boolean connectionStartAllowed(BotState current) {
        return current == BotState.STOPPED || current == BotState.FAILED;
    }

    private void startInternal() {
        BotState current = state.get();
        // Presence rules run periodically. Starting again while a connection
        // is pending or already active creates a second client with the same
        // username, which Velocity correctly rejects as a duplicate login.
        if (current != BotState.STOPPED && current != BotState.FAILED) {
            return;
        }
        manualStop.set(false);
        event("START_REQUESTED", "operator or automatic startup");
        scheduleConnection(0, ActivationKind.START);
    }

    public synchronized void stop() {
        manualStop.set(true);
        reconnectStability.invalidate();
        event("STOPPED", "operator request");
        generation.incrementAndGet();
        cancelReconnect();
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        authenticationSettle.cancel();
        behavior.onUnavailable();
        BotTransport active = transport;
        transport = null;
        connectedAt = null;
        if (active != null) {
            state.set(BotState.STOPPING);
            try {
                active.disconnect("Bot stopped by operator");
            }
            catch (RuntimeException exception) {
                logger.warn("Bot {} transport rejected the stop request", definition.id(), exception);
            }
        }
        state.set(BotState.STOPPED);
    }

    public synchronized void reconnectNow() {
        if (maintenanceBlocked.getAsBoolean()) {
            return;
        }
        authenticationInterventionRequired.set(false);
        reconnectInternal("operator request");
    }

    private void reconnectInternal(String source) {
        reconnectStability.invalidate();
        manualStop.set(false);
        event("RECONNECT_REQUESTED", source);
        generation.incrementAndGet();
        cancelReconnect();
        cancelServerSwitch();
        authenticationSettle.cancel();
        behavior.onUnavailable();
        BotTransport active = transport;
        transport = null;
        connectedAt = null;
        if (active != null) {
            try {
                active.disconnect("Bot reconnect requested");
            }
            catch (RuntimeException exception) {
                logger.warn("Bot {} transport rejected the reconnect disconnect request",
                    definition.id(), exception);
            }
        }
        state.set(BotState.RECONNECT_WAIT);
        scheduleConnection(200, ActivationKind.RECONNECT);
    }

    public synchronized void reconnectAutomatically() {
        if (maintenanceBlocked.getAsBoolean()
            || !automaticRecoveryAllowed(authenticationInterventionRequired.get())) {
            return;
        }
        reconnectInternal("automatic schedule");
    }

    public boolean sendCommand(String command) {
        String normalized = normalizeCommand(command);
        BotTransport active = transport;
        return !normalized.isBlank() && active != null && state.get() == BotState.PLAY
            && active.sendCommand(normalized);
    }

    public boolean moveTo(double x, double y, double z) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.moveTo(x, y, z);
    }

    public boolean look(float yaw, float pitch) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.look(yaw, pitch);
    }

    public boolean swingMainHand() {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.swingMainHand();
    }

    public boolean jump() {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.jump();
    }

    public boolean setSneaking(boolean sneaking) {
        BotTransport active = transport;
        return active != null && state.get() == BotState.PLAY && active.setSneaking(sneaking);
    }

    public BotPosition position() {
        BotTransport active = transport;
        return active == null ? BotPosition.unknown() : active.position();
    }

    public boolean isPlayable() {
        BotTransport active = transport;
        return active != null && active.isConnected() && state.get() == BotState.PLAY;
    }

    public boolean isAuthenticationComplete() {
        return authenticationOutcome.succeeded() && authenticationContinuationApplied.get();
    }

    public void startBehavior() {
        behavior.start();
    }

    public void pauseBehavior() {
        behavior.pause();
    }

    public boolean requestBehaviorServerSwitch(String server) {
        String normalized = server == null ? "" : server.trim();
        if (normalized.isBlank() || definition.serverSwitchCommand().isBlank()) {
            return false;
        }
        String command = definition.serverSwitchCommand().replace("{server}", normalized);
        return sendCommand(command);
    }

    public BehaviorSnapshot behaviorSnapshot() {
        return behavior.snapshot();
    }

    public String followTarget() {
        return followTarget;
    }

    public void setFollowTarget(String target) {
        followTarget = target == null ? "" : target.trim();
    }

    /** Records an operator-visible event produced by a proxy-side integration. */
    public void recordExternalEvent(String type, String detail) {
        event(type, detail);
    }

    /**
     * Stops the configured chat-command switch loop before Velocity moves this
     * connection through its own API. Otherwise the retry loop could pull the
     * bot back to the statically configured target after an operator switch.
     */
    public void prepareExternalServerSwitch() {
        cancelServerSwitch();
    }

    private void connectIfNeeded(long attemptEpoch) {
        long currentGeneration;
        synchronized (this) {
            if (attemptEpoch != connectionAttemptEpoch) {
                return;
            }
            // This runnable may clear only the metadata it was scheduled with.
            // A cancelled runnable can already be running and waiting for this
            // monitor while a replacement task is installed.
            reconnectTask = null;
            nextConnectionAttempt = null;
            if (manualStop.get() || maintenanceBlocked.getAsBoolean()) {
                state.set(BotState.STOPPED);
                return;
            }
            BotTransport active = transport;
            if (active != null && active.isConnected()) {
                return;
            }

            currentGeneration = generation.incrementAndGet();
            reconnectStability.connectionStarted(currentGeneration);
            resetConnectionState();
            state.set(BotState.CONNECTING);
        }

        BotTransport created = null;
        try {
            ProtocolVersion protocol = protocolResolver.resolve();
            String resolvedProtocol = protocol.displayName() + " (" + protocol.protocolId() + ")";
            String resolvedProtocolSource = protocolResolver.source();
            UUID uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + definition.username())
                .getBytes(StandardCharsets.UTF_8));
            TransportConfig transportConfig = new TransportConfig(
                definition.username(), uuid, endpoint.address(), endpoint.port(),
                endpoint.virtualHost(), endpoint.virtualPort(), definition.renderDistance(),
                runtime.resourcePackMode() == ResourcePackMode.ACCEPT_WITHOUT_DOWNLOAD,
                runtime.resourcePackStepDelayMillis(), runtime.autoRespawn()
            );
            synchronized (this) {
                if (!isConnectionAttemptCurrentLocked(attemptEpoch, currentGeneration)
                    || manualStop.get() || maintenanceBlocked.getAsBoolean()) {
                    settleCancelledConnection();
                    return;
                }
                // Publish diagnostics only for the still-current connection
                // attempt. A detector retired by stop/reconnect must not
                // overwrite the replacement attempt's protocol metadata.
                activeProtocolVersion = protocol;
                activeProtocol = resolvedProtocol;
                activeProtocolSource = resolvedProtocolSource;
            }
            created = transportRegistry.create(protocol, transportConfig,
                new SessionTransportListener(currentGeneration), executor);
            synchronized (this) {
                if (!isConnectionAttemptCurrentLocked(attemptEpoch, currentGeneration) || manualStop.get()
                    || maintenanceBlocked.getAsBoolean()) {
                    settleCancelledConnection();
                }
                else {
                    transport = created;
                    created.connect();
                    logger.info("Bot {} ({}) connecting to {}:{} using protocol {} detected via {}",
                        definition.id(), definition.username(), endpoint.address(), endpoint.port(), activeProtocol,
                        activeProtocolSource);
                    event("CONNECTING", activeProtocol + " via " + activeProtocolSource);
                    return;
                }
            }
            closeCancelledTransport(created);
        }
        catch (Exception exception) {
            if (created != null) {
                synchronized (this) {
                    if (transport == created) {
                        transport = null;
                    }
                }
                closeCancelledTransport(created);
            }
            synchronized (this) {
                if (!isConnectionAttemptCurrentLocked(attemptEpoch, currentGeneration)
                    || manualStop.get() || maintenanceBlocked.getAsBoolean()) {
                    settleCancelledConnection();
                    return;
                }
                reconnectStability.invalidate();
                long reconnectGeneration = generation.incrementAndGet();
                lastDisconnectReason = exception.getMessage() == null
                    ? exception.getClass().getSimpleName() : exception.getMessage();
                logger.warn("Bot {} could not start its connection: {}",
                    definition.id(), lastDisconnectReason, exception);
                scheduleReconnect(reconnectGeneration);
            }
        }
    }

    private void closeCancelledTransport(BotTransport cancelled) {
        try {
            cancelled.disconnect("Bot start cancelled");
        }
        catch (RuntimeException exception) {
            logger.debug("Bot {} could not close a cancelled transport", definition.id(), exception);
        }
    }

    private void settleCancelledConnection() {
        if (manualStop.get() || maintenanceBlocked.getAsBoolean()) {
            state.set(BotState.STOPPED);
        }
    }

    private void resetConnectionState() {
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        authenticationSettle.cancel();
        connectedAt = null;
        loginSent.set(false);
        registerSent.set(false);
        authenticationOutcome.reset();
        authenticationContinuationApplied.set(false);
        authenticationUiFlow.reset();
        authenticationCommandUiActive.set(false);
        authenticationCommandUiTracker.reset();
        deferredChatAuthenticationPrompt.set(null);
        authenticationUiPresentations.set(0);
        authenticationUiSubmissions.set(0);
        lastAuthenticationUi.set("none");
        playInitialized.set(false);
        playTransitionsThisConnection.set(0);
        serverSwitchPending.set(false);
        serverSwitchTransitionSeen.set(false);
        serverSwitchAttempts.set(0);
    }

    private synchronized void onTransportState(long currentGeneration, TransportState transportState) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        switch (transportState) {
            case LOGIN -> {
                state.set(BotState.LOGIN);
                reconnectStability.leftPlay(currentGeneration);
                behavior.onUnavailable();
                if (definition.auth().mode() != AuthMode.NONE) {
                    // AuthMe's modern UI can be presented before the first
                    // PLAY transition, so the timeout must begin at LOGIN.
                    scheduleAuthenticationTimeout(currentGeneration);
                }
                if (connectedAt == null) {
                    connectedAt = Instant.now();
                }
                lastDisconnectReason = "connected";
                event("CONNECTED", activeProtocol);
            }
            case CONFIGURATION -> {
                state.set(BotState.CONFIGURATION);
                reconnectStability.leftPlay(currentGeneration);
                behavior.onUnavailable();
                if (serverSwitchPending.get()) {
                    serverSwitchTransitionSeen.set(true);
                }
            }
            case PLAY -> {
                state.set(BotState.PLAY);
                reconnectStability.enteredPlay(currentGeneration);
                playTransitionsThisConnection.incrementAndGet();
                boolean firstPlay = playInitialized.compareAndSet(false, true);
                if (firstPlay) {
                    playEntries.incrementAndGet();
                    lastPlayAt = Instant.now();
                    logger.info("Bot {} entered PLAY using {}", definition.id(), activeProtocol);
                    event("PLAY", activeProtocol);
                }
                if (firstPlay && authenticationOutcome.consumePrePlaySuccess()) {
                    logger.info("Bot {} accepted authentication success received before PLAY", definition.id());
                    event("AUTHENTICATED", "success message received before PLAY");
                    scheduleAuthenticationSuccess(currentGeneration);
                }
                else if (authenticationUiFlow.credentialReadyOnPlay()) {
                    if (completeAuthenticationAfterSettle(currentGeneration)) {
                        logger.info("Bot {} completed authentication UI after entering PLAY", definition.id());
                        event("AUTHENTICATED", lastAuthenticationUi.get() + " entered PLAY");
                    }
                }
                else if (firstPlay) {
                    if (authenticationUiActive()) {
                        logger.info("Bot {} is waiting for the next AuthMeUI stage", definition.id());
                    }
                    else {
                        scheduleAuthentication(currentGeneration);
                    }
                }
                else if (serverSwitchPending.get() && isConfirmedServerTransition()) {
                    completeServerSwitch(currentGeneration);
                }
                else if (!serverSwitchPending.get() && authenticationOutcome.succeeded()
                    && authenticationContinuationApplied.get()) {
                    behavior.onReady();
                }
            }
        }
    }

    private void scheduleAuthentication(long currentGeneration) {
        AuthMode mode = definition.auth().mode();
        if (mode == AuthMode.NONE) {
            completeAuthentication(currentGeneration);
            return;
        }
        if (authenticationUiActive()) {
            return;
        }
        scheduleAuthenticationTimeout(currentGeneration);
        executor.schedule(() -> runInitialChatAuthentication(currentGeneration, mode),
            initialAuthenticationDelayMillis(activeProtocolVersion, definition.auth().loginDelayMillis(),
            definition.auth().uiDetectionGraceMillis()), TimeUnit.MILLISECONDS);
    }

    private synchronized void runInitialChatAuthentication(long currentGeneration, AuthMode mode) {
        if (!shouldRunChatAuthentication(
            isPlayable(currentGeneration), !authenticationOutcome.pending(), authenticationUiActive())) {
            return;
        }
        AuthenticationUiType prompted = deferredChatAuthenticationPrompt.getAndSet(null);
        AuthenticationUiType selected = prompted != null && authenticationTypeExpected(mode, prompted)
            ? prompted
            : mode == AuthMode.REGISTER ? AuthenticationUiType.REGISTER : AuthenticationUiType.LOGIN;
        if (selected == AuthenticationUiType.REGISTER) {
            sendRegister();
            return;
        }
        sendLogin();
        if (mode == AuthMode.LOGIN) {
            return;
        }
        executor.schedule(() -> runFallbackRegistration(currentGeneration),
            definition.auth().fallbackRegisterDelayMillis(), TimeUnit.MILLISECONDS);
    }

    private synchronized void runFallbackRegistration(long currentGeneration) {
        if (!shouldRunChatAuthentication(
            isPlayable(currentGeneration), !authenticationOutcome.pending(), authenticationUiActive())) {
            return;
        }
        sendRegister();
    }

    private synchronized void handleAuthMessage(long currentGeneration, String message) {
        if (!isCurrent(currentGeneration) || !authenticationOutcome.pending()
            || definition.auth().mode() == AuthMode.NONE) {
            return;
        }
        if (matches(failureMessages, message)) {
            failAuthentication(currentGeneration, message);
        }
        else if (matches(successMessages, message)) {
            logger.info("Bot {} matched an authentication success message", definition.id());
            if (isPlayable(currentGeneration)) {
                if (completeAuthenticationAfterSettle(currentGeneration)) {
                    event("AUTHENTICATED", "success message");
                }
            }
            else if (!playInitialized.get() && authenticationOutcome.succeedBeforePlay()) {
                cancelAuthenticationTimeout();
                logger.info("Bot {} deferred authentication success until its first PLAY transition",
                    definition.id());
                event("AUTH_SUCCESS_DEFERRED", "success message received before PLAY");
            }
        }
        else if (matches(registerPrompts, message)) {
            if (authenticationUiActive()) {
                logger.debug("Bot {} ignored a chat registration prompt because AuthMeUI is active", definition.id());
                return;
            }
            logger.info("Bot {} matched a registration prompt", definition.id());
            event("AUTH_PROMPT", "registration");
            if (deferChatAuthenticationPrompt(AuthenticationUiType.REGISTER)) {
                return;
            }
            sendRegister();
        }
        else if (matches(loginPrompts, message)) {
            if (authenticationUiActive()) {
                logger.debug("Bot {} ignored a chat login prompt because AuthMeUI is active", definition.id());
                return;
            }
            logger.info("Bot {} matched a login prompt", definition.id());
            event("AUTH_PROMPT", "login");
            if (deferChatAuthenticationPrompt(AuthenticationUiType.LOGIN)) {
                return;
            }
            sendLogin();
        }
    }

    private boolean deferChatAuthenticationPrompt(AuthenticationUiType type) {
        if (!withinUiDetectionGrace(activeProtocolVersion, definition.auth().uiDetectionGraceMillis(),
            lastPlayAt, Instant.now())) {
            return false;
        }
        deferredChatAuthenticationPrompt.set(type);
        logger.info("Bot {} deferred its {} chat prompt during the authentication UI detection grace",
            definition.id(), type);
        event("AUTH_PROMPT_DEFERRED", type.name());
        return true;
    }

    private synchronized void failAuthentication(long currentGeneration, String message) {
        if (!isCurrent(currentGeneration) || !authenticationOutcome.fail()) {
            return;
        }
        FailureCategory category = FailureCategory.classify(message);
        lastDisconnectReason = "authentication failed: " + category.name();
        event("AUTH_FAILED", category.name());
        logger.warn("Bot {} stopped after authentication failure: {}", definition.id(), category);
        stopAfterAuthenticationFailure();
    }

    private void scheduleAuthenticationTimeout(long currentGeneration) {
        long timeout = definition.auth().timeoutMillis();
        if (timeout <= 0) {
            return;
        }
        if (authenticationTimeoutTask != null && !authenticationTimeoutTask.isDone()) {
            return;
        }
        authenticationTimeoutTask = executor.schedule(
            () -> authenticationTimedOut(currentGeneration, timeout), timeout, TimeUnit.MILLISECONDS);
    }

    private synchronized void authenticationTimedOut(long currentGeneration, long timeout) {
        if (!isCurrent(currentGeneration) || !authenticationOutcome.fail()) {
            return;
        }
        lastDisconnectReason = "authentication timed out after " + timeout + " ms";
        event("AUTH_TIMEOUT", Long.toString(timeout));
        logger.warn("Bot {} stopped after authentication timed out after {} ms", definition.id(), timeout);
        stopAfterAuthenticationFailure();
    }

    private synchronized void stopAfterAuthenticationFailure() {
        cancelAuthenticationTimeout();
        reconnectStability.invalidate();
        authenticationSettle.cancel();
        authenticationInterventionRequired.set(true);
        manualStop.set(true);
        cancelReconnect();
        cancelServerSwitch();
        behavior.onUnavailable();
        BotTransport active = transport;
        if (active != null) {
            active.disconnect("Authentication requires operator intervention");
        }
        state.set(BotState.FAILED);
    }

    private synchronized void handleAuthenticationUi(long currentGeneration,
                                                     AuthenticationUiChallenge challenge) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        AuthenticationUiChallenge normalizedChallenge = normalizeAuthenticationUiChallenge(
            challenge, definition.auth().registrationSecondArgument());
        AuthMode mode = definition.auth().mode();
        if (ignoresAuthenticationUi(mode)) {
            recordIgnoredAuthenticationUi(normalizedChallenge.description());
            return;
        }
        if (!authenticationOutcome.pending()) {
            return;
        }
        authenticationUiPresentations.incrementAndGet();
        AuthenticationUiFlow.Presentation presentation = authenticationUiFlow.present(
            normalizedChallenge, mode, definition.auth().acceptRules(),
            definition.auth().registrationEmail(), definition.auth().registrationSecondArgument(),
            state.get() == BotState.PLAY);
        lastAuthenticationUi.set(presentation.stage());
        event("AUTH_UI", presentation.stage());

        if (presentation.decision() == AuthenticationUiFlow.Decision.REJECT) {
            failAuthenticationUi(currentGeneration, presentation.stage(), presentation.reason());
            return;
        }
        if (presentation.decision() == AuthenticationUiFlow.Decision.IGNORE) {
            logger.info("Bot {} ignored a repeated AuthMeUI rules action at {}",
                definition.id(), presentation.stage());
            return;
        }

        BotTransport active = transport;
        if (active == null || !active.submitAuthenticationUi(
            normalizedChallenge, definition.password(), definition.auth().registrationEmail())) {
            failAuthenticationUi(currentGeneration, presentation.stage(),
                "transport could not submit the AuthMeUI stage");
        }
        else {
            boolean submittedInPlay = state.get() == BotState.PLAY;
            authenticationUiFlow.markSubmitted(presentation, submittedInPlay);
            authenticationUiSubmissions.incrementAndGet();
            logger.info("Bot {} submitted {}", definition.id(), presentation.stage());
            event("AUTH_UI", presentation.stage() + " submitted");
        }
    }

    private synchronized void handleAuthenticationCommandUi(long currentGeneration, AuthenticationUiType type) {
        AuthMode mode = definition.auth().mode();
        if (!isPlayable(currentGeneration)
            || (type != AuthenticationUiType.LOGIN && type != AuthenticationUiType.REGISTER)) {
            return;
        }
        String stage = "AUTHME COMMAND " + type;
        if (ignoresAuthenticationUi(mode)) {
            recordIgnoredAuthenticationUi(stage);
            return;
        }
        if (!authenticationOutcome.pending()) {
            return;
        }
        authenticationUiPresentations.incrementAndGet();
        lastAuthenticationUi.set(stage);
        event("AUTH_UI", stage);
        if (!authenticationTypeExpected(mode, type)) {
            failAuthenticationUi(currentGeneration, stage,
                type + " command UI is incompatible with auth mode " + mode);
            return;
        }
        if (authenticationCommandUiTracker.wasSubmitted(type)) {
            failAuthenticationUi(currentGeneration, stage,
                "credential command UI was presented again after submission");
            return;
        }

        authenticationCommandUiActive.set(true);
        deferredChatAuthenticationPrompt.set(null);
        boolean submitted = type == AuthenticationUiType.LOGIN ? sendLogin() : sendRegister();
        boolean commandAlreadySent = type == AuthenticationUiType.LOGIN ? loginSent.get() : registerSent.get();
        authenticationCommandUiTracker.recordSubmission(type, submitted || commandAlreadySent);
        if (submitted) {
            authenticationUiSubmissions.incrementAndGet();
            logger.info("Bot {} submitted {}", definition.id(), stage);
            event("AUTH_UI", stage + " submitted");
        }
        else if (commandAlreadySent) {
            // The grace-period chat fallback may have submitted the same
            // command just before the structured UI arrived. Treat that as
            // this type's one submission and wait for explicit success.
        }
        else {
            authenticationCommandUiActive.set(false);
            lastAuthenticationUi.set(stage + " retry pending");
            logger.warn("Bot {} could not submit {}; a repeated presentation may retry it", definition.id(), stage);
            event("AUTH_UI_RETRY", stage + " command send failed");
        }
    }

    private void recordIgnoredAuthenticationUi(String stage) {
        authenticationUiPresentations.incrementAndGet();
        String ignored = ignoredAuthenticationUiStage(stage);
        lastAuthenticationUi.set(ignored);
        event("AUTH_UI_IGNORED", ignored);
    }

    static boolean ignoresAuthenticationUi(AuthMode mode) {
        return mode == AuthMode.NONE;
    }

    static String ignoredAuthenticationUiStage(String stage) {
        return stage + " ignored mode=NONE";
    }

    static AuthenticationUiChallenge normalizeAuthenticationUiChallenge(
        AuthenticationUiChallenge challenge,
        RegistrationSecondArgument configured
    ) {
        if (challenge == null || challenge.type() != AuthenticationUiType.REGISTER
            || challenge.provider() != AuthenticationUiProvider.AUTHME_UI) {
            return challenge;
        }
        RegistrationSecondArgument mode = configured == null
            ? RegistrationSecondArgument.AUTO
            : configured;
        AuthenticationUiInputPurpose purpose = switch (mode) {
            case AUTO -> challenge.secondaryInputPurpose() == null
                ? AuthenticationUiInputPurpose.NONE
                : challenge.secondaryInputPurpose();
            case CONFIRMATION -> AuthenticationUiInputPurpose.CONFIRMATION;
            case EMAIL_OPTIONAL, EMAIL_MANDATORY -> AuthenticationUiInputPurpose.EMAIL;
        };
        return new AuthenticationUiChallenge(
            challenge.type(), challenge.provider(), challenge.actionId(), challenge.passwordInput(),
            challenge.secondaryInput(), purpose, challenge.agreementInput());
    }

    static long initialAuthenticationDelayMillis(
        ProtocolVersion protocol,
        long loginDelayMillis,
        long uiDetectionGraceMillis
    ) {
        if (protocol == ProtocolVersion.MINECRAFT_1_16_5) {
            return loginDelayMillis;
        }
        return Math.max(loginDelayMillis, uiDetectionGraceMillis);
    }

    static boolean withinUiDetectionGrace(
        ProtocolVersion protocol,
        long uiDetectionGraceMillis,
        Instant firstPlayAt,
        Instant now
    ) {
        if (protocol == null || protocol == ProtocolVersion.MINECRAFT_1_16_5
            || uiDetectionGraceMillis <= 0L || firstPlayAt == null || now == null) {
            return false;
        }
        long elapsedMillis = Duration.between(firstPlayAt, now).toMillis();
        return elapsedMillis >= 0L && elapsedMillis < uiDetectionGraceMillis;
    }

    static boolean authenticationTypeExpected(AuthMode mode, AuthenticationUiType type) {
        return mode == AuthMode.AUTO
            || (mode == AuthMode.LOGIN && type == AuthenticationUiType.LOGIN)
            || (mode == AuthMode.REGISTER && type == AuthenticationUiType.REGISTER);
    }

    static boolean shouldRunChatAuthentication(boolean playable, boolean completed, boolean uiActive) {
        return playable && !completed && !uiActive;
    }

    private boolean authenticationUiActive() {
        return authenticationUiFlow.active() || authenticationCommandUiActive.get();
    }

    private synchronized void failAuthenticationUi(long currentGeneration, String stage, String reason) {
        if (!isCurrent(currentGeneration) || !authenticationOutcome.fail()) {
            return;
        }
        lastAuthenticationUi.set(stage + " failed");
        lastDisconnectReason = "authentication failed: AUTH_UI " + reason;
        event("AUTH_UI_FAILED", stage + " - " + reason);
        logger.warn("Bot {} stopped at {}: {}", definition.id(), stage, reason);
        stopAfterAuthenticationFailure();
    }

    private boolean sendLogin() {
        if (authenticationUiFlow.active()) {
            return false;
        }
        return submitAuthenticationCommand(loginSent, () -> {
            logger.info("Bot {} submitting its login command", definition.id());
            return sendCommand(CommandTemplate.render(definition.auth().loginCommand(), definition));
        });
    }

    private boolean sendRegister() {
        if (authenticationUiFlow.active()) {
            return false;
        }
        return submitAuthenticationCommand(registerSent, () -> {
            logger.info("Bot {} submitting its registration command", definition.id());
            return sendCommand(CommandTemplate.render(definition.auth().registerCommand(), definition));
        });
    }

    static boolean submitAuthenticationCommand(AtomicBoolean sent, BooleanSupplier sender) {
        if (!sent.compareAndSet(false, true)) {
            return false;
        }
        boolean submitted = false;
        try {
            submitted = sender.getAsBoolean();
            return submitted;
        }
        finally {
            if (!submitted) {
                sent.set(false);
            }
        }
    }

    enum AuthenticationOutcome {
        PENDING,
        SUCCESS,
        FAILURE
    }

    static final class AuthenticationOutcomeGate {
        private final AtomicReference<AuthenticationOutcome> outcome =
            new AtomicReference<>(AuthenticationOutcome.PENDING);
        private boolean successPendingPlay;

        boolean succeed() {
            return outcome.compareAndSet(AuthenticationOutcome.PENDING, AuthenticationOutcome.SUCCESS);
        }

        synchronized boolean succeedBeforePlay() {
            if (!succeed()) {
                return false;
            }
            successPendingPlay = true;
            return true;
        }

        boolean fail() {
            return outcome.compareAndSet(AuthenticationOutcome.PENDING, AuthenticationOutcome.FAILURE);
        }

        synchronized boolean consumePrePlaySuccess() {
            if (outcome.get() != AuthenticationOutcome.SUCCESS || !successPendingPlay) {
                return false;
            }
            successPendingPlay = false;
            return true;
        }

        boolean pending() {
            return outcome.get() == AuthenticationOutcome.PENDING;
        }

        boolean succeeded() {
            return outcome.get() == AuthenticationOutcome.SUCCESS;
        }

        boolean failed() {
            return outcome.get() == AuthenticationOutcome.FAILURE;
        }

        AuthenticationOutcome outcome() {
            return outcome.get();
        }

        synchronized void reset() {
            successPendingPlay = false;
            outcome.set(AuthenticationOutcome.PENDING);
        }
    }

    static final class AuthenticationCommandUiTracker {
        private final EnumSet<AuthenticationUiType> submitted = EnumSet.noneOf(AuthenticationUiType.class);

        synchronized boolean wasSubmitted(AuthenticationUiType type) {
            return submitted.contains(type);
        }

        synchronized void recordSubmission(AuthenticationUiType type, boolean submissionSucceeded) {
            if (submissionSucceeded) {
                submitted.add(type);
            }
        }

        synchronized void reset() {
            submitted.clear();
        }
    }

    private synchronized boolean completeAuthentication(long currentGeneration) {
        if (!isPlayable(currentGeneration) || !authenticationOutcome.succeed()) {
            return false;
        }
        applyAuthenticationSuccess(currentGeneration);
        return true;
    }

    private synchronized boolean completeAuthenticationAfterSettle(long currentGeneration) {
        if (!isPlayable(currentGeneration) || !authenticationOutcome.succeed()) {
            return false;
        }
        scheduleAuthenticationSuccess(currentGeneration);
        return true;
    }

    /**
     * Gives the freshly admitted client time to finish its first PLAY packets
     * before a successful authentication immediately moves it to another
     * backend. This is especially important for AuthMe session restoration:
     * its success message can arrive in the same tick as the initial join.
     */
    private void scheduleAuthenticationSuccess(long currentGeneration) {
        cancelAuthenticationTimeout();
        long delay = definition.auth().afterAuthDelayMillis();
        authenticationSettle.scheduleUntilComplete(delay, 250,
            () -> applyAuthenticationSuccessWhenPlayable(currentGeneration));
    }

    private synchronized boolean applyAuthenticationSuccessWhenPlayable(long currentGeneration) {
        if (!isCurrent(currentGeneration) || !authenticationOutcome.succeeded()) {
            return true;
        }
        BotTransport active = transport;
        if (active == null || !active.isConnected()) {
            return true;
        }
        if (!isPlayable(currentGeneration)) {
            return false;
        }
        applyAuthenticationSuccess(currentGeneration);
        return true;
    }

    private synchronized void applyAuthenticationSuccess(long currentGeneration) {
        if (!isPlayable(currentGeneration) || !authenticationOutcome.succeeded()
            || !authenticationContinuationApplied.compareAndSet(false, true)) {
            return;
        }
        // A successful chat/UI match alone is not enough to renew the retry
        // budget. Start the stability window only once the post-auth flow is
        // actually applied while this transport remains in PLAY.
        reconnectStability.authenticationCompleted(currentGeneration);
        authenticationUiFlow.complete();
        authenticationCommandUiActive.set(false);
        deferredChatAuthenticationPrompt.set(null);
        cancelAuthenticationTimeout();
        if (!definition.targetServer().isBlank() && !definition.serverSwitchCommand().isBlank()) {
            if (currentServer == null && playTransitionsThisConnection.get() > 1) {
                serverSwitchAttempts.set(0);
                serverSwitchPending.set(true);
                logger.info("Bot {} observed an authentication-plugin server transition before auth completion",
                    definition.id());
                completeServerSwitch(currentGeneration);
                return;
            }
            beginServerSwitch(currentGeneration);
            return;
        }
        scheduleAfterLoginCommands(currentGeneration, 0);
    }

    private void beginServerSwitch(long currentGeneration) {
        cancelServerSwitch();
        serverSwitchAttempts.set(0);
        serverSwitchTransitionSeen.set(false);
        serverSwitchPending.set(true);
        attemptServerSwitch(currentGeneration);
    }

    private synchronized void attemptServerSwitch(long currentGeneration) {
        if (!isCurrent(currentGeneration) || manualStop.get() || !serverSwitchPending.get()) {
            return;
        }
        if (!isPlayable(currentGeneration)) {
            BotTransport active = transport;
            if (active == null || !active.isConnected()) {
                serverSwitchPending.set(false);
                return;
            }
            serverSwitchTask = executor.schedule(() -> attemptServerSwitch(currentGeneration),
                250, TimeUnit.MILLISECONDS);
            return;
        }
        // A same-server command has no CONFIGURATION/PLAY transition. AuthMe
        // may also move the player before our first request or between retries.
        if (isOnTargetServer()) {
            completeServerSwitch(currentGeneration);
            return;
        }
        int attempt = serverSwitchAttempts.incrementAndGet();
        int maximumAttempts = definition.serverSwitchMaximumAttempts();
        if (maximumAttempts > 0 && attempt > maximumAttempts) {
            serverSwitchPending.set(false);
            lastDisconnectReason = "server switch to " + definition.targetServer() + " exhausted "
                + maximumAttempts + " attempts";
            logger.error("Bot {} could not switch to server {} after {} attempts; after-login commands were not run",
                definition.id(), definition.targetServer(), maximumAttempts);
            return;
        }
        String command = CommandTemplate.render(definition.serverSwitchCommand(), definition);
        if (sendCommand(command)) {
            if (attempt == 1) {
                logger.info("Bot {} requested server switch to {} (attempt {})",
                    definition.id(), definition.targetServer(), attempt);
            }
            else {
                logger.debug("Bot {} requested server switch to {} (attempt {})",
                    definition.id(), definition.targetServer(), attempt);
            }
        }
        if (attempt % 20 == 0) {
            logger.warn("Bot {} is still waiting for server {} after {} attempts",
                definition.id(), definition.targetServer(), attempt);
        }
        long retryDelay = Math.max(250L, definition.serverSwitchDelayMillis());
        serverSwitchTask = executor.schedule(() -> attemptServerSwitch(currentGeneration),
            retryDelay, TimeUnit.MILLISECONDS);
    }

    private boolean isConfirmedServerTransition() {
        if (currentServer != null) {
            return isOnTargetServer();
        }
        return activeProtocolVersion == ProtocolVersion.MINECRAFT_1_16_5
            || serverSwitchTransitionSeen.get();
    }

    private boolean isOnTargetServer() {
        return currentServer != null && currentServer.get()
            .filter(server -> server.equalsIgnoreCase(definition.targetServer())).isPresent();
    }

    private void completeServerSwitch(long currentGeneration) {
        if (!isCurrent(currentGeneration) || !serverSwitchPending.compareAndSet(true, false)) {
            return;
        }
        cancelServerSwitchTaskOnly();
        logger.info("Bot {} confirmed server switch to {} after {} attempt(s)",
            definition.id(), definition.targetServer(), serverSwitchAttempts.get());
        event("SERVER_SWITCHED", definition.targetServer());
        scheduleAfterLoginCommands(currentGeneration, definition.serverSwitchDelayMillis());
    }

    private void scheduleAfterLoginCommands(long currentGeneration, long initialDelay) {
        long delay = initialDelay;
        for (String command : definition.afterLoginCommands()) {
            scheduleCommand(currentGeneration, CommandTemplate.render(command, definition), delay);
            delay += runtime.commandIntervalMillis();
        }
        behavior.onReady();
    }

    private void scheduleCommand(long currentGeneration, String command, long delay) {
        executor.schedule(() -> sendWhenPlayable(currentGeneration, command, 0), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void sendWhenPlayable(long currentGeneration, String command, int attempt) {
        if (!isCurrent(currentGeneration) || manualStop.get() || maintenanceBlocked.getAsBoolean()) {
            return;
        }
        if (sendCommand(command)) {
            return;
        }
        if (attempt < 20) {
            executor.schedule(() -> sendWhenPlayable(currentGeneration, command, attempt + 1),
                250, TimeUnit.MILLISECONDS);
        }
        else {
            logger.warn("Bot {} could not execute a queued command because it never returned to PLAY", definition.id());
        }
    }

    private synchronized void onDisconnected(long currentGeneration, String reason, Throwable cause) {
        if (!isCurrent(currentGeneration)) {
            return;
        }
        // Retire the listener generation before releasing the session monitor.
        // Packet callbacks queued by the old transport can no longer rewrite
        // state or authentication data while a reconnect is waiting/starting.
        long reconnectGeneration = generation.incrementAndGet();
        reconnectStability.invalidate();
        connectedAt = null;
        disconnects.incrementAndGet();
        lastDisconnectAt = Instant.now();
        protocolResolver.invalidateAutomaticDetection();
        cancelServerSwitch();
        cancelAuthenticationTimeout();
        authenticationSettle.cancel();
        behavior.onUnavailable();
        // An operator-actionable authentication failure is more useful than
        // the synthetic disconnect reason used to close the transport.
        if (!authenticationOutcome.failed()) {
            lastDisconnectReason = reason;
        }
        event("DISCONNECTED", reason);
        if (cause != null) {
            logger.warn("Bot {} disconnected: {}", definition.id(), reason, cause);
        }
        else {
            logger.info("Bot {} disconnected: {}", definition.id(), reason);
        }
        transport = null;
        if (authenticationOutcome.failed()) {
            state.set(BotState.FAILED);
        }
        else if (manualStop.get() || maintenanceBlocked.getAsBoolean()) {
            state.set(BotState.STOPPED);
        }
        else {
            scheduleReconnect(reconnectGeneration);
        }
    }

    private synchronized void scheduleReconnect(long currentGeneration) {
        if (!isCurrent(currentGeneration) || manualStop.get() || maintenanceBlocked.getAsBoolean()) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        int attempt = reconnectAttempts.incrementAndGet();
        if (!reconnectPolicy.allows(attempt)) {
            state.set(BotState.FAILED);
            logger.error("Bot {} exhausted {} reconnect attempts", definition.id(), attempt - 1);
            return;
        }
        long policyDelay = reconnectPolicy.delayMillis(attempt, ThreadLocalRandom.current().nextDouble());
        long delay = connectionRateLimiter.reserveDelayMillis(policyDelay);
        state.set(BotState.RECONNECT_WAIT);
        if (delay > policyDelay) {
            logger.info("Bot {} reconnect attempt {} in {} ms ({} ms policy delay plus global connection spacing)",
                definition.id(), attempt, delay, policyDelay);
        }
        else {
            logger.info("Bot {} reconnect attempt {} in {} ms", definition.id(), attempt, delay);
        }
        nextConnectionAttempt = new ConnectionAttemptSnapshot(
            Instant.now().plusMillis(delay), ActivationKind.RECONNECT);
        long attemptEpoch = ++connectionAttemptEpoch;
        reconnectTask = executor.schedule(() -> connectIfNeeded(attemptEpoch), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void scheduleConnection(long minimumDelayMillis, ActivationKind kind) {
        if (manualStop.get() || maintenanceBlocked.getAsBoolean()) {
            return;
        }
        BotTransport active = transport;
        if (active != null && active.isConnected()) {
            return;
        }
        if (reconnectTask != null && !reconnectTask.isDone()) {
            return;
        }
        long delay = connectionRateLimiter.reserveDelayMillis(minimumDelayMillis);
        if (delay > 0) {
            state.set(BotState.RECONNECT_WAIT);
        }
        nextConnectionAttempt = new ConnectionAttemptSnapshot(Instant.now().plusMillis(delay), kind);
        long attemptEpoch = ++connectionAttemptEpoch;
        reconnectTask = executor.schedule(() -> connectIfNeeded(attemptEpoch), delay, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelReconnect() {
        connectionAttemptEpoch++;
        if (reconnectTask != null) {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        nextConnectionAttempt = null;
    }

    private boolean isConnectionAttemptCurrentLocked(long attemptEpoch, long currentGeneration) {
        return attemptEpoch == connectionAttemptEpoch && isCurrent(currentGeneration);
    }

    private synchronized void cancelServerSwitch() {
        serverSwitchPending.set(false);
        serverSwitchTransitionSeen.set(false);
        cancelServerSwitchTaskOnly();
    }

    private synchronized void cancelServerSwitchTaskOnly() {
        if (serverSwitchTask != null) {
            serverSwitchTask.cancel(false);
            serverSwitchTask = null;
        }
    }

    private synchronized void cancelAuthenticationTimeout() {
        if (authenticationTimeoutTask != null) {
            authenticationTimeoutTask.cancel(false);
            authenticationTimeoutTask = null;
        }
    }

    private boolean isPlayable(long currentGeneration) {
        BotTransport active = transport;
        return isCurrent(currentGeneration) && active != null && active.isConnected() && state.get() == BotState.PLAY;
    }

    private boolean isCurrent(long currentGeneration) {
        return generation.get() == currentGeneration;
    }

    private synchronized void resetReconnectAttemptsIfStable(long currentGeneration) {
        if (isCurrent(currentGeneration) && !manualStop.get() && !maintenanceBlocked.getAsBoolean()
            && isPlayable(currentGeneration) && isAuthenticationComplete()) {
            reconnectAttempts.set(0);
        }
    }

    private static String normalizeCommand(String command) {
        String normalized = command == null ? "" : command.trim();
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    private static List<Pattern> compile(List<String> expressions) {
        return expressions.stream().map(Pattern::compile).toList();
    }

    private static boolean matches(List<Pattern> patterns, String value) {
        return patterns.stream().anyMatch(pattern -> pattern.matcher(value).find());
    }

    private void event(String type, String detail) {
        events.add(type, detail);
        List<BotEvent> snapshot = events.snapshot();
        if (!snapshot.isEmpty()) {
            eventSink.accept(snapshot.getLast());
        }
    }

    private final class SessionTransportListener implements TransportListener {
        private final long currentGeneration;

        private SessionTransportListener(long currentGeneration) {
            this.currentGeneration = currentGeneration;
        }

        @Override
        public void onStateChanged(TransportState transportState) {
            onTransportState(currentGeneration, transportState);
        }

        @Override
        public void onSystemMessage(String message) {
            handleAuthMessage(currentGeneration, message);
        }

        @Override
        public void onAuthenticationUi(AuthenticationUiChallenge challenge) {
            handleAuthenticationUi(currentGeneration, challenge);
        }

        // TransportListener 2.7 exposes this structured callback for AuthMe's
        // post-join command-template dialogs. Keeping Dialog text out of prompt
        // matching prevents a native UI and the chat fallback from racing.
        @Override
        public void onAuthenticationCommandUi(AuthenticationUiType type) {
            handleAuthenticationCommandUi(currentGeneration, type);
        }

        @Override
        public void onDisconnected(String reason, Throwable cause) {
            BotSession.this.onDisconnected(currentGeneration, reason, cause);
        }

        @Override
        public void onResourcePackStatus(String status) {
            if (isCurrent(currentGeneration)) {
                if (status.startsWith("SUCCESSFULLY_LOADED")) {
                    resourcePacksLoaded.incrementAndGet();
                }
                logger.info("Bot {} resource pack: {}", definition.id(), status);
            }
        }

        @Override
        public void onDiagnostic(String message) {
            logger.debug("Bot {}: {}", definition.id(), message);
        }
    }
}
