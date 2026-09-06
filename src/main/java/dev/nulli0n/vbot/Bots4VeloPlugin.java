package dev.nulli0n.vbot;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.nulli0n.vbot.bot.BotManager;
import dev.nulli0n.vbot.bot.BotSession;
import dev.nulli0n.vbot.bot.BotSnapshot;
import dev.nulli0n.vbot.bot.BotEvent;
import dev.nulli0n.vbot.bot.MaintenanceHoldSnapshot;
import dev.nulli0n.vbot.api.Bots4VeloApi;
import dev.nulli0n.vbot.api.Bots4VeloApiProvider;
import dev.nulli0n.vbot.api.common.CommonBotApi;
import dev.nulli0n.vbot.api.common.CommonBotApiProvider;
import dev.nulli0n.vbot.api.common.CoreCommonBotApi;
import dev.nulli0n.vbot.addon.AddonLoader;
import dev.nulli0n.vbot.addon.CoreAddonBotService;
import dev.nulli0n.vbot.addon.Slf4jAddonLogger;
import dev.nulli0n.vbot.backend.BackendControlPatch;
import dev.nulli0n.vbot.backend.BackendControlResult;
import dev.nulli0n.vbot.backend.BackendControlService;
import dev.nulli0n.vbot.backend.VelocityBackendControlService;
import dev.nulli0n.vbot.backend.protocol.BackendChannel;
import dev.nulli0n.vbot.command.VBotCommand;
import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.config.ConfigLoader;
import dev.nulli0n.vbot.config.ConfigChangePreview;
import dev.nulli0n.vbot.config.ManagedCredentialReference;
import dev.nulli0n.vbot.config.ManagedBotStore;
import dev.nulli0n.vbot.message.PluginMessages;
import dev.nulli0n.vbot.observe.PrometheusExporter;
import dev.nulli0n.vbot.observe.WebhookNotifier;
import dev.nulli0n.vbot.tab.TabIntegration;
import dev.nulli0n.vbot.protocol.VelocityBackendProtocolDetector;
import dev.nulli0n.vbot.schedule.DailySchedule;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

@Plugin(
    id = "bots4velo",
    name = "Bots4Velo",
    version = BuildConstants.VERSION,
    description = "Embedded multi-version headless Minecraft clients for Velocity",
    authors = {"OpenAI Codex"},
    dependencies = {@Dependency(id = "tab", optional = true)}
)
public final class Bots4VeloPlugin implements Bots4VeloApi {
    private static final MinecraftChannelIdentifier BACKEND_CONTROL_CHANNEL =
        MinecraftChannelIdentifier.from(BackendChannel.ID);
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private volatile BotPluginConfig config;
    private volatile BotManager manager;
    private volatile ManagedBotStore managedBotStore;
    private volatile PluginMessages messages;
    private volatile AddonLoader addonLoader;
    private volatile CommonBotApi commonBotApi;
    private final ConcurrentMap<String, ScheduledTask> followTasks = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledTask> scheduledActions = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ScheduledTask> presenceTasks = new ConcurrentHashMap<>();
    private volatile PrometheusExporter prometheusExporter;
    private volatile TabIntegration tabIntegration;
    private volatile ScheduledTask tabTask;
    private volatile ScheduledTask reloadHandoffTask;
    private volatile Set<String> reloadHandoffUsernames = Set.of();
    private final RuntimeGenerationGate runtimeGeneration = new RuntimeGenerationGate();
    private volatile VelocityBackendControlService backendControlClient;
    private volatile boolean backendControlChannelRegistered;
    private volatile boolean shuttingDown;
    private final BackendControlService backendControl = new BackendControlService() {
        @Override
        public CompletionStage<BackendControlResult> probe(String botId) {
            return activeBackendControl().probe(botId);
        }

        @Override
        public CompletionStage<BackendControlResult> apply(String botId, BackendControlPatch patch) {
            return activeBackendControl().apply(botId, patch);
        }

        @Override
        public CompletionStage<BackendControlResult> respawn(String botId) {
            return activeBackendControl().respawn(botId);
        }

        @Override
        public CompletionStage<BackendControlResult> recover(String botId) {
            return activeBackendControl().recover(botId);
        }
    };

    @Inject
    public Bots4VeloPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public synchronized void onInitialize(ProxyInitializeEvent event) {
        try {
            messages = PluginMessages.load(dataDirectory, logger);
            managedBotStore = ManagedBotStore.load(dataDirectory);
            config = ConfigLoader.load(dataDirectory, managedBotStore.definitions());
            manager = new BotManager(config, logger, new VelocityBackendProtocolDetector(proxy),
                this::currentServerByUsername);
            backendControlClient = createBackendControl(config, manager);
            proxy.getChannelRegistrar().register(BACKEND_CONTROL_CHANNEL);
            backendControlChannelRegistered = true;
            backendControlClient.start();
            registerOptionalIntegrations(manager, config);
            registerCommand();
            long generation = runtimeGeneration.advance();
            manager.startEnabled();
            startConfiguredFollows(config, generation, manager);
            startConfiguredSchedules(config, generation, manager);
            startPresenceRules(config, generation, manager);
            startPrometheus(manager, config);
            startTabIntegration(manager, generation);
            Bots4VeloApiProvider.register(this);
            CommonBotApi convenienceApi = new CoreCommonBotApi(this);
            commonBotApi = convenienceApi;
            CommonBotApiProvider.register(convenienceApi);
            loadAddons();
            logger.info("bots4velo initialized with {} configured bot(s)", config.bots().size());
        }
        catch (Exception exception) {
            closeAddons();
            CommonBotApiProvider.clear(commonBotApi);
            commonBotApi = null;
            Bots4VeloApiProvider.clear(this);
            cancelRuntimeServicesForReload();
            VelocityBackendControlService control = backendControlClient;
            backendControlClient = null;
            if (control != null) {
                bestEffort("backend control", control::close);
            }
            BotManager active = manager;
            manager = null;
            if (active != null) {
                bestEffort("bot manager", active::close);
            }
            unregisterBackendControlChannel();
            // SnakeYAML marked exceptions can embed the complete offending
            // source line, including an inline password. Startup therefore
            // records only a diagnostic type, never the message/throwable.
            logger.error(initializationFailureDiagnostic(exception));
        }
    }

    static String initializationFailureDiagnostic(Throwable failure) {
        String type = failure == null ? "UnknownFailure" : failure.getClass().getSimpleName();
        return "bots4velo initialization failed (" + type
            + "); exception details were hidden to protect configuration secrets";
    }

    private void registerCommand() {
        CommandManager commandManager = proxy.getCommandManager();
        CommandMeta meta = commandManager.metaBuilder("vbot").plugin(this).build();
        commandManager.register(meta, new VBotCommand(this, backendControl));
    }

    private void loadAddons() {
        AddonLoader loader = new AddonLoader(dataDirectory, new CoreAddonBotService(this, proxy),
            new Slf4jAddonLogger(logger), BuildConstants.VERSION);
        addonLoader = loader;
        try {
            int count = loader.loadAll();
            logger.info("bots4velo loaded {} addon(s)", count);
        }
        catch (IOException failure) {
            logger.error("Could not scan the bots4velo addon directory", failure);
        }
    }

    private void closeAddons() {
        AddonLoader loader = addonLoader;
        addonLoader = null;
        if (loader != null) {
            bestEffort("addon loader", loader::close);
        }
    }

    /**
     * Parses and constructs a replacement manager before touching the live
     * manager. A malformed configuration therefore leaves live bots running.
     */
    public synchronized ReloadResult reload() throws IOException {
        PluginMessages replacementMessages = PluginMessages.loadStrict(dataDirectory, logger);
        ManagedBotStore replacementStore = ManagedBotStore.load(dataDirectory);
        BotPluginConfig replacementConfig = ConfigLoader.load(dataDirectory, replacementStore.definitions());
        BotManager replacement = null;
        VelocityBackendControlService replacementBackend = null;
        try {
            replacement = new BotManager(replacementConfig, logger,
                new VelocityBackendProtocolDetector(proxy), this::currentServerByUsername);
            replacement.pauseActivations();
            replacementBackend = createBackendControl(replacementConfig, replacement);
            registerOptionalIntegrations(replacement, replacementConfig);
        }
        catch (RuntimeException exception) {
            VelocityBackendControlService failedBackend = replacementBackend;
            BotManager failedReplacement = replacement;
            if (failedBackend != null) {
                bestEffort("replacement backend control", failedBackend::close);
            }
            if (failedReplacement != null) {
                bestEffort("replacement bot manager", failedReplacement::close);
            }
            throw exception;
        }

        BotManager previous = manager;
        VelocityBackendControlService previousBackend = backendControlClient;
        List<MaintenanceHoldSnapshot> previousHolds = previous == null
            ? List.of() : previous.holdSnapshots();
        List<String> currentPreviousUsernames = previous == null ? List.of() : previous.sessions().stream()
            .map(session -> session.definition().username()).toList();
        Set<String> previousUsernames = ReloadHandoffGate.mergePendingUsernames(
            reloadHandoffUsernames, currentPreviousUsernames);
        try {
            replacementBackend.start();
            restoreMaintenanceHolds(replacement, previousHolds);
        }
        catch (RuntimeException exception) {
            VelocityBackendControlService failedBackend = replacementBackend;
            BotManager failedReplacement = replacement;
            bestEffort("replacement backend control", failedBackend::close);
            bestEffort("replacement bot manager", failedReplacement::close);
            throw exception;
        }

        long replacementRuntimeGeneration = cancelRuntimeServicesForReload();
        // Commit the validated replacement before shutting down old state. Its
        // activation gate remains closed until Velocity has removed every old
        // bot Player, including asynchronous legacy transport disconnects.
        managedBotStore = replacementStore;
        messages = replacementMessages;
        config = replacementConfig;
        manager = replacement;
        backendControlClient = replacementBackend;
        if (previousBackend != null) {
            bestEffort("previous backend control", previousBackend::close);
        }
        if (previous != null) {
            bestEffort("previous bot manager", previous::close);
        }
        beginReloadHandoff(replacement, replacementConfig, previousUsernames,
            replacementRuntimeGeneration);
        return new ReloadResult(replacementConfig.bots().size(), replacementStore.definitions().size());
    }

    /** Parses the complete replacement configuration without mutating live state. */
    public synchronized ReloadCheckResult previewReload() throws IOException {
        PluginMessages candidateMessages = PluginMessages.loadStrict(dataDirectory, logger);
        ManagedBotStore candidateStore = ManagedBotStore.load(dataDirectory);
        BotPluginConfig candidateConfig = ConfigLoader.load(dataDirectory, candidateStore.definitions());
        ConfigChangePreview.Preview preview = ConfigChangePreview.compare(config, candidateConfig);
        return new ReloadCheckResult(preview, messages.language(), candidateMessages.language(),
            candidateConfig.bots().size(), candidateStore.definitions().size());
    }

    /** Serializes an operator hold with manager replacement during reload. */
    public synchronized boolean holdBot(String id, String reason) {
        return manager().hold(id, reason);
    }

    /** Serializes a timed operator hold with manager replacement during reload. */
    public synchronized boolean holdBot(String id, String reason, Duration ttl) {
        return manager().hold(id, reason, ttl);
    }

    public synchronized boolean holdBot(String id, String reason, String server) {
        return manager().hold(id, reason, server);
    }

    public synchronized boolean holdBot(String id, String reason, Duration ttl, String server) {
        return manager().hold(id, reason, ttl, server);
    }

    /** Serializes hold removal with manager replacement during reload. */
    public synchronized boolean resumeBot(String id) {
        return manager().resume(id);
    }

    private static void restoreMaintenanceHolds(BotManager replacement,
                                                List<MaintenanceHoldSnapshot> holds) {
        for (MaintenanceHoldSnapshot hold : holds) {
            replacement.restoreHold(hold);
        }
    }

    private long cancelRuntimeServicesForReload() {
        long replacementGeneration = runtimeGeneration.advance();
        cancelReloadHandoff();
        cancelTasks(followTasks, "follow");
        cancelTasks(scheduledActions, "scheduled action");
        cancelTasks(presenceTasks, "presence rule");
        bestEffort("Prometheus exporter", this::stopPrometheus);
        bestEffort("TAB integration", this::stopTabIntegration);
        return replacementGeneration;
    }

    private void beginReloadHandoff(BotManager replacement, BotPluginConfig replacementConfig,
                                    Set<String> previousUsernames, long expectedRuntimeGeneration) {
        reloadHandoffUsernames = Set.copyOf(previousUsernames);
        AtomicInteger waitingPolls = new AtomicInteger();
        ReloadHandoffGate gate = new ReloadHandoffGate(
            () -> !shuttingDown && manager == replacement,
            () -> previousUsernames.stream().noneMatch(username -> proxy.getPlayer(username).isPresent()),
            () -> startReplacementRuntime(replacement, replacementConfig, expectedRuntimeGeneration));
        try {
            if (gate.poll() != ReloadHandoffGate.PollResult.WAITING) {
                return;
            }

            logger.info("Reload handoff is waiting for {} old bot player(s) to leave Velocity",
                previousUsernames.stream().filter(username -> proxy.getPlayer(username).isPresent()).count());
            AtomicReference<ScheduledTask> scheduled = new AtomicReference<>();
            ScheduledTask task = proxy.getScheduler().buildTask(this, () -> {
                ReloadHandoffGate.PollResult result = gate.poll();
                if (result == ReloadHandoffGate.PollResult.WAITING) {
                    if (waitingPolls.incrementAndGet() % 100 == 0) {
                        List<String> lingering = previousUsernames.stream()
                            .filter(username -> proxy.getPlayer(username).isPresent())
                            .limit(10)
                            .toList();
                        logger.warn("Reload handoff is still waiting for old Velocity player(s): {}", lingering);
                    }
                    return;
                }
                ScheduledTask completed = scheduled.get();
                if (completed != null) {
                    bestEffort("reload handoff task", completed::cancel);
                    if (reloadHandoffTask == completed) {
                        reloadHandoffTask = null;
                    }
                }
            }).delay(Duration.ofMillis(100)).repeat(Duration.ofMillis(100)).schedule();
            scheduled.set(task);
            reloadHandoffTask = task;
        }
        catch (RuntimeException exception) {
            logger.error("Could not complete or schedule the safe reload player handoff; replacement activations "
                + "remain paused. Run /vbot reload again after checking Velocity player and scheduler state.",
                exception);
        }
    }

    private synchronized void startReplacementRuntime(BotManager replacement,
                                                       BotPluginConfig replacementConfig,
                                                       long expectedRuntimeGeneration) {
        if (!runtimeCurrent(expectedRuntimeGeneration, replacement)) {
            return;
        }
        reloadHandoffUsernames = Set.of();
        replacement.resumeActivations();
        replacement.startEnabled();
        logger.info("Reload handoff complete; replacement bot activations are enabled");
        try {
            startConfiguredFollows(replacementConfig, expectedRuntimeGeneration, replacement);
            startConfiguredSchedules(replacementConfig, expectedRuntimeGeneration, replacement);
            startPresenceRules(replacementConfig, expectedRuntimeGeneration, replacement);
            startPrometheus(replacement, replacementConfig);
            startTabIntegration(replacement, expectedRuntimeGeneration);
        }
        catch (RuntimeException exception) {
            logger.error("Bots4Velo reloaded bots successfully, but an optional integration failed to restart",
                exception);
        }
    }

    private void cancelReloadHandoff() {
        ScheduledTask task = reloadHandoffTask;
        reloadHandoffTask = null;
        if (task != null) {
            bestEffort("reload handoff task", task::cancel);
        }
    }

    private <K> void cancelTasks(ConcurrentMap<K, ScheduledTask> tasks, String label) {
        tasks.forEach((ignored, task) -> bestEffort(label + " task", task::cancel));
        tasks.clear();
    }

    private void bestEffort(String resource, Runnable action) {
        try {
            action.run();
        }
        catch (RuntimeException | LinkageError exception) {
            logger.warn("Could not close or cancel {} during lifecycle transition", resource, exception);
        }
    }

    private boolean runtimeCurrent(long expectedGeneration, BotManager expectedManager) {
        return !shuttingDown && runtimeGeneration.matches(expectedGeneration) && manager == expectedManager;
    }

    public BotPluginConfig validateConfiguration() throws IOException {
        ManagedBotStore store = ManagedBotStore.load(dataDirectory);
        return ConfigLoader.load(dataDirectory, store.definitions());
    }

    public synchronized ManagedCreateResult createManagedBot(String id, String username,
                                                               ManagedCredentialReference credential,
                                                               String targetServer) throws IOException {
        BotManager active = manager();
        ManagedBotStore store = managedBotStore;
        BotPluginConfig.BotDefinition requested = ManagedBotStore.createDefinition(
            id, username, credential, targetServer);
        if (active.find(requested.id()).isPresent()) {
            return ManagedCreateResult.ALREADY_EXISTS;
        }
        if (active.sessions().stream().anyMatch(session ->
            session.definition().username().equalsIgnoreCase(requested.username()))) {
            return ManagedCreateResult.ALREADY_EXISTS;
        }
        if (active.snapshots().size() >= active.maximumBots()) {
            return ManagedCreateResult.LIMIT_REACHED;
        }

        BotPluginConfig.BotDefinition definition = store.add(requested, credential);
        BotManager.CreateResult result;
        try {
            result = active.create(definition);
        }
        catch (RuntimeException | Error failure) {
            try {
                store.remove(definition.id());
            }
            catch (Exception rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
        if (result != BotManager.CreateResult.CREATED) {
            store.remove(definition.id());
            return result == BotManager.CreateResult.LIMIT_REACHED
                ? ManagedCreateResult.LIMIT_REACHED : ManagedCreateResult.ALREADY_EXISTS;
        }
        return ManagedCreateResult.CREATED;
    }

    /**
     * Source-compatible v2.x entry point. Every non-empty string is treated as
     * a legacy inline password and rejected without inspecting its contents;
     * credential references must use the typed overload.
     */
    @Deprecated
    public synchronized ManagedCreateResult createManagedBot(String id, String username,
                                                               String credentialToken,
                                                               String targetServer) throws IOException {
        if (credentialToken != null && !credentialToken.isBlank()) {
            throw new IllegalArgumentException(
                "Inline passwords are no longer accepted; use a credential reference");
        }
        return createManagedBot(id, username, ManagedCredentialReference.none(), targetServer);
    }

    public synchronized ManagedRemoveResult removeManagedBot(String id) throws IOException {
        BotManager active = manager();
        ManagedBotStore store = managedBotStore;
        if (!store.contains(id)) {
            return active.find(id).isPresent()
                ? ManagedRemoveResult.STATIC_BOT : ManagedRemoveResult.NOT_FOUND;
        }
        BotPluginConfig.BotDefinition definition = store.definitions().get(
            id == null ? "" : id.trim().toLowerCase(java.util.Locale.ROOT));
        String canonicalId = definition == null ? id : definition.id();
        store.remove(canonicalId);
        active.remove(canonicalId);
        bestEffort("managed bot follow state", () -> stopFollowing(canonicalId));
        VelocityBackendControlService control = backendControlClient;
        if (control != null) {
            bestEffort("managed bot backend state", () -> control.removeBot(canonicalId));
        }
        return ManagedRemoveResult.REMOVED;
    }

    @Subscribe
    public synchronized void onShutdown(ProxyShutdownEvent event) {
        shuttingDown = true;
        reloadHandoffUsernames = Set.of();
        closeAddons();
        CommonBotApiProvider.clear(commonBotApi);
        commonBotApi = null;
        Bots4VeloApiProvider.clear(this);
        cancelRuntimeServicesForReload();
        VelocityBackendControlService control = backendControlClient;
        backendControlClient = null;
        if (control != null) {
            bestEffort("backend control", control::close);
        }
        unregisterBackendControlChannel();
        BotManager active = manager;
        if (active != null) {
            bestEffort("bot manager", active::close);
        }
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        VelocityBackendControlService control = backendControlClient;
        if (control != null && control.handlePluginMessage(event)) {
            event.setResult(PluginMessageEvent.ForwardResult.handled());
        }
    }

    @Subscribe
    public void onServerPostConnect(ServerPostConnectEvent event) {
        VelocityBackendControlService control = backendControlClient;
        if (control != null) {
            control.handleServerPostConnect(event.getPlayer());
        }
    }

    public BotManager manager() {
        BotManager active = manager;
        if (active == null) {
            throw new IllegalStateException("Bot manager is not initialized");
        }
        return active;
    }

    @Override
    public List<BotSnapshot> bots() {
        return manager().snapshots();
    }

    @Override
    public Optional<BotSnapshot> bot(String id) {
        return manager().find(id).map(BotSession::snapshot);
    }

    @Override
    public boolean start(String id) {
        return manager().start(id);
    }

    @Override
    public boolean stop(String id) {
        return manager().stop(id);
    }

    @Override
    public boolean reconnect(String id) {
        return manager().reconnect(id);
    }

    @Override
    public void addEventListener(Consumer<BotEvent> listener) {
        manager().addEventListener(listener);
    }

    @Override
    public void removeEventListener(Consumer<BotEvent> listener) {
        manager().removeEventListener(listener);
    }

    public Logger logger() {
        return logger;
    }

    public PluginMessages messages() {
        PluginMessages active = messages;
        if (active == null) {
            throw new IllegalStateException("Messages are not initialized");
        }
        return active;
    }

    /** Persists and activates a UI language without restarting any bot session. */
    public synchronized PluginMessages changeLanguage(String requestedLanguage) throws IOException {
        PluginMessages replacement = PluginMessages.selectLanguage(dataDirectory, requestedLanguage, logger);
        messages = replacement;
        logger.info("Bots4Velo command language changed to {}", replacement.language());
        return replacement;
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public BackendControlService backendControl() {
        return backendControl;
    }

    public boolean backendControlEnabled() {
        VelocityBackendControlService control = backendControlClient;
        return control != null && control.enabled();
    }

    private BackendControlService activeBackendControl() {
        VelocityBackendControlService control = backendControlClient;
        return control == null ? BackendControlService.unavailable() : control;
    }

    private VelocityBackendControlService createBackendControl(BotPluginConfig configuration,
                                                                BotManager owningManager) {
        return new VelocityBackendControlService(proxy, this, logger, configuration, () -> owningManager);
    }

    private void unregisterBackendControlChannel() {
        if (backendControlChannelRegistered) {
            proxy.getChannelRegistrar().unregister(BACKEND_CONTROL_CHANNEL);
            backendControlChannelRegistered = false;
        }
    }

    public List<String> serverNames() {
        return proxy.getAllServers().stream()
            .map(server -> server.getServerInfo().getName())
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    /** Returns only IDs that can be removed through {@code /vbot remove}. */
    public List<String> managedBotIds() {
        ManagedBotStore store = managedBotStore;
        if (store == null) {
            return List.of();
        }
        return store.definitions().values().stream()
            .map(BotPluginConfig.BotDefinition::id)
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public List<BotSession> selectBots(String selector) {
        return selectBots(manager(), selector);
    }

    private List<BotSession> selectBots(BotManager owningManager, String selector) {
        List<BotSession> candidates = owningManager.select(selector);
        String normalized = selector == null ? "" : selector.trim();
        if (!normalized.regionMatches(true, 0, "@server:", 0, "@server:".length())) {
            return candidates;
        }
        String server = normalized.substring("@server:".length()).trim();
        if (server.isBlank()) {
            return List.of();
        }
        return candidates.stream().filter(session -> currentServer(owningManager, session.definition().id())
            .map(current -> current.equalsIgnoreCase(server)).orElse(false)).toList();
    }

    public Optional<String> currentServer(String botId) {
        return currentServer(manager(), botId);
    }

    private Optional<String> currentServer(BotManager owningManager, String botId) {
        return owningManager.find(botId)
            .flatMap(session -> currentServerByUsername(session.definition().username()));
    }

    private Optional<String> currentServerByUsername(String username) {
        return proxy.getPlayer(username)
            .flatMap(Player::getCurrentServer)
            .map(connection -> connection.getServerInfo().getName());
    }

    public CompletableFuture<BotServerSwitchResult> switchBotServer(String botId, String serverName) {
        return switchBotServer(manager(), botId, serverName);
    }

    private CompletableFuture<BotServerSwitchResult> switchBotServer(BotManager owningManager,
                                                                      String botId,
                                                                      String serverName) {
        Optional<BotSession> found = owningManager.find(botId);
        if (found.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_FOUND, botId, "", serverName, "unknown bot"));
        }

        BotSession session = found.get();
        Optional<RegisteredServer> destination = findServer(serverName);
        if (destination.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.SERVER_NOT_FOUND, session.definition().id(),
                session.definition().username(), serverName, "unknown Velocity server"));
        }
        String canonicalServerName = destination.get().getServerInfo().getName();
        if (!session.isPlayable()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_READY, session.definition().id(),
                session.definition().username(), canonicalServerName, "bot is not in PLAY"));
        }
        if (!session.isAuthenticationComplete()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.AUTHENTICATION_PENDING, session.definition().id(),
                session.definition().username(), canonicalServerName,
                "authentication has not completed"));
        }

        Optional<Player> onlineBot = proxy.getPlayer(session.definition().username());
        if (onlineBot.isEmpty()) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.BOT_NOT_READY, session.definition().id(),
                session.definition().username(), canonicalServerName, "bot is not visible to Velocity"));
        }

        session.prepareExternalServerSwitch();
        Player player = onlineBot.get();
        boolean alreadyConnected = player.getCurrentServer()
            .map(connection -> connection.getServerInfo().getName().equalsIgnoreCase(canonicalServerName))
            .orElse(false);
        if (alreadyConnected) {
            return CompletableFuture.completedFuture(new BotServerSwitchResult(
                BotServerSwitchStatus.ALREADY_CONNECTED, session.definition().id(),
                session.definition().username(), canonicalServerName, "already connected"));
        }

        return player.createConnectionRequest(destination.get()).connect().handle((result, throwable) -> {
            if (throwable != null) {
                logger.warn("Bot {} could not switch to server {}", session.definition().id(),
                    canonicalServerName, throwable);
                String detail = throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage();
                return new BotServerSwitchResult(BotServerSwitchStatus.FAILED,
                    session.definition().id(), session.definition().username(), canonicalServerName, detail);
            }
            ConnectionRequestBuilder.Status status = result.getStatus();
            if (status == ConnectionRequestBuilder.Status.SUCCESS) {
                return new BotServerSwitchResult(BotServerSwitchStatus.SWITCHED,
                    session.definition().id(), session.definition().username(), canonicalServerName, status.name());
            }
            if (status == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                return new BotServerSwitchResult(BotServerSwitchStatus.ALREADY_CONNECTED,
                    session.definition().id(), session.definition().username(), canonicalServerName, status.name());
            }
            return new BotServerSwitchResult(BotServerSwitchStatus.FAILED,
                session.definition().id(), session.definition().username(), canonicalServerName, status.name());
        });
    }

    public synchronized FollowResult startFollowing(String botId, String playerName) {
        BotManager expectedManager = manager();
        return startFollowing(botId, playerName, runtimeGeneration.current(), expectedManager);
    }

    private FollowResult startFollowing(String botId, String playerName, long expectedGeneration,
                                        BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return new FollowResult(false, "runtime is changing");
        }
        Optional<BotSession> found = expectedManager.find(botId);
        if (found.isEmpty()) {
            return new FollowResult(false, "unknown bot");
        }
        String target = playerName == null ? "" : playerName.trim();
        if (target.isBlank() || target.equalsIgnoreCase(found.get().definition().username())) {
            return new FollowResult(false, "a different online player name is required");
        }
        stopFollowing(botId);
        found.get().setFollowTarget(target);
        ScheduledTask task = proxy.getScheduler().buildTask(this, () -> followTick(
            found.get().definition().id(), target, expectedGeneration, expectedManager))
            .repeat(Duration.ofSeconds(3)).schedule();
        followTasks.put(found.get().definition().id().toLowerCase(java.util.Locale.ROOT), task);
        followTick(found.get().definition().id(), target, expectedGeneration, expectedManager);
        return new FollowResult(true, "following " + target);
    }

    public synchronized boolean stopFollowing(String botId) {
        Optional<BotSession> found = manager().find(botId);
        found.ifPresent(session -> session.setFollowTarget(""));
        ScheduledTask task = followTasks.remove(botId == null ? "" : botId.trim().toLowerCase(java.util.Locale.ROOT));
        if (task != null) {
            task.cancel();
        }
        return found.isPresent();
    }

    private void followTick(String botId, String playerName, long expectedGeneration,
                            BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return;
        }
        Optional<BotSession> session = expectedManager.find(botId);
        Optional<Player> target = proxy.getPlayer(playerName);
        if (session.isEmpty() || target.isEmpty() || !session.get().isPlayable()
            || !session.get().isAuthenticationComplete()) {
            return;
        }
        Optional<String> server = target.get().getCurrentServer().map(connection -> connection.getServerInfo().getName());
        if (server.isEmpty()) {
            return;
        }
        currentServer(expectedManager, botId).filter(current -> current.equalsIgnoreCase(server.get()))
            .ifPresentOrElse(current ->
            target.get().spoofChatInput("/minecraft:tp " + session.get().definition().username() + " "
                + target.get().getUsername()), () -> switchBotServer(expectedManager, botId, server.get())
                .thenAccept(result -> {
                if (runtimeCurrent(expectedGeneration, expectedManager) && result.successful()) {
                    proxy.getScheduler().buildTask(this, () -> {
                        if (runtimeCurrent(expectedGeneration, expectedManager)
                            && target.get().getCurrentServer().map(connection -> connection.getServerInfo().getName()
                            .equalsIgnoreCase(result.server())).orElse(false)) {
                            target.get().spoofChatInput("/minecraft:tp " + result.username() + " "
                                + target.get().getUsername());
                        }
                    }).delay(Duration.ofMillis(750)).schedule();
                }
            }));
    }

    private void startConfiguredFollows(BotPluginConfig candidate, long expectedGeneration,
                                        BotManager expectedManager) {
        candidate.bots().values().stream()
            .filter(definition -> definition.enabled()
                && definition.behavior().mode() == BotPluginConfig.BehaviorMode.FOLLOW
                && !definition.behavior().followPlayer().isBlank())
            .forEach(definition -> startFollowing(definition.id(), definition.behavior().followPlayer(),
                expectedGeneration, expectedManager));
    }

    private void startConfiguredSchedules(BotPluginConfig candidate, long expectedGeneration,
                                          BotManager expectedManager) {
        for (BotPluginConfig.ScheduledAction action : candidate.runtime().schedules()) {
            if (!runtimeCurrent(expectedGeneration, expectedManager)) {
                return;
            }
            if (action.runsDailyAtConfiguredTime()) {
                scheduleNextDailyAction(action, expectedGeneration, expectedManager);
                continue;
            }
            ScheduleTiming timing = scheduleTiming(action);
            ScheduledTask task = proxy.getScheduler().buildTask(this,
                () -> runScheduledAction(action, expectedGeneration, expectedManager))
                .delay(timing.initialDelay())
                .repeat(timing.interval()).schedule();
            scheduledActions.put(action.id().toLowerCase(java.util.Locale.ROOT), task);
        }
    }

    private ScheduleTiming scheduleTiming(BotPluginConfig.ScheduledAction action) {
        return new ScheduleTiming(Duration.ofMillis(action.initialDelayMillis()),
            Duration.ofMillis(action.intervalMillis()));
    }

    private synchronized void scheduleNextDailyAction(BotPluginConfig.ScheduledAction action,
                                                      long expectedGeneration,
                                                      BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return;
        }
        Duration delay = DailySchedule.delayUntilNext(action.at(), action.timezone(), Instant.now());
        ScheduledTask task = proxy.getScheduler().buildTask(this, () -> {
            if (!runtimeCurrent(expectedGeneration, expectedManager)) {
                return;
            }
            runScheduledAction(action, expectedGeneration, expectedManager);
            // Calculate the next local wall-clock occurrence after every run,
            // so a daylight-saving transition does not shift the configured time.
            scheduleNextDailyAction(action, expectedGeneration, expectedManager);
        }).delay(delay).schedule();
        scheduledActions.put(action.id().toLowerCase(java.util.Locale.ROOT), task);
    }

    private void runScheduledAction(BotPluginConfig.ScheduledAction action, long expectedGeneration,
                                    BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return;
        }
        List<BotSession> targets = selectBots(expectedManager, action.selector());
        if (targets.isEmpty()) {
            logger.debug("Scheduled action {} matched no bots", action.id());
            return;
        }
        for (BotSession session : targets) {
            if (!runtimeCurrent(expectedGeneration, expectedManager)
                || expectedManager.isHeld(session.definition().id())) {
                continue;
            }
            switch (action.action()) {
                case "start" -> expectedManager.startAutomatically(session.definition().id());
                case "stop" -> expectedManager.stop(session.definition().id());
                case "reconnect" -> expectedManager.reconnectAutomatically(session.definition().id());
                case "server" -> switchBotServer(expectedManager, session.definition().id(), action.server());
                default -> logger.warn("Ignoring unknown scheduled action {}", action.action());
            }
        }
    }

    private void registerOptionalIntegrations(BotManager candidate, BotPluginConfig candidateConfig) {
        if (!candidateConfig.runtime().webhookUrl().isBlank()) {
            try {
                candidate.addEventListener(new WebhookNotifier(candidateConfig.runtime().webhookUrl(), logger));
            }
            catch (IllegalArgumentException exception) {
                logger.warn("Ignoring invalid runtime.webhook-url", exception);
            }
        }
    }

    private void startPrometheus(BotManager candidate, BotPluginConfig candidateConfig) {
        int port = candidateConfig.runtime().prometheusPort();
        if (port <= 0) {
            return;
        }
        try {
            prometheusExporter = new PrometheusExporter(candidateConfig.runtime().prometheusAddress(), port,
                candidate::snapshots, logger);
        }
        catch (IOException exception) {
            logger.error("Could not start Prometheus metrics on {}:{}",
                candidateConfig.runtime().prometheusAddress(), port, exception);
        }
    }

    private void stopPrometheus() {
        PrometheusExporter active = prometheusExporter;
        prometheusExporter = null;
        if (active != null) {
            active.close();
        }
    }

    private void startTabIntegration(BotManager candidate, long expectedGeneration) {
        if (proxy.getPluginManager().getPlugin("tab").isEmpty()) {
            return;
        }
        try {
            TabIntegration integration = new TabIntegration(candidate, logger);
            tabIntegration = integration;
            Runnable apply = () -> {
                synchronized (this) {
                    if (runtimeCurrent(expectedGeneration, candidate)) {
                        integration.apply();
                    }
                }
            };
            tabTask = proxy.getScheduler().buildTask(this, apply)
                .repeat(Duration.ofSeconds(1)).schedule();
            apply.run();
            logger.info("TAB integration enabled; use {} in TAB formatting", TabIntegration.GROUP_PLACEHOLDER);
        }
        catch (RuntimeException | LinkageError exception) {
            logger.warn("TAB is installed but its API could not be initialized", exception);
        }
    }

    private void stopTabIntegration() {
        ScheduledTask task = tabTask;
        tabTask = null;
        if (task != null) {
            bestEffort("TAB refresh task", task::cancel);
        }
        TabIntegration active = tabIntegration;
        tabIntegration = null;
        if (active != null) {
            bestEffort("TAB integration", active::close);
        }
    }

    private void startPresenceRules(BotPluginConfig candidate, long expectedGeneration,
                                    BotManager expectedManager) {
        for (BotPluginConfig.PresenceRule rule : candidate.runtime().presenceRules()) {
            if (!runtimeCurrent(expectedGeneration, expectedManager)) {
                return;
            }
            ScheduledTask task = proxy.getScheduler().buildTask(this,
                () -> applyPresenceRule(rule, expectedGeneration, expectedManager))
                .repeat(Duration.ofMillis(rule.intervalMillis())).schedule();
            presenceTasks.put(rule.id().toLowerCase(java.util.Locale.ROOT), task);
            applyPresenceRule(rule, expectedGeneration, expectedManager);
        }
    }

    private void applyPresenceRule(BotPluginConfig.PresenceRule rule, long expectedGeneration,
                                   BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return;
        }
        Optional<RegisteredServer> backend = findServer(rule.server());
        if (backend.isEmpty()) {
            logger.warn("Presence rule {} refers to unknown server {}", rule.id(), rule.server());
            return;
        }
        // A registered Velocity backend can still be down for maintenance.
        // Wait for a successful ping instead of repeatedly moving bots into a
        // failed connection. The next scheduled tick resumes assignments, and
        // BotManager's shared connection limiter batches their re-entry.
        backend.get().ping().whenComplete((ignored, failure) -> {
            if (!runtimeCurrent(expectedGeneration, expectedManager)) {
                return;
            }
            if (failure != null) {
                logger.debug("Presence rule {} is waiting for backend {} to recover", rule.id(), rule.server());
                return;
            }
            applyPresenceRuleToHealthyBackend(rule, backend.get(), expectedGeneration, expectedManager);
        });
    }

    private void applyPresenceRuleToHealthyBackend(BotPluginConfig.PresenceRule rule, RegisteredServer backend,
                                                   long expectedGeneration, BotManager expectedManager) {
        if (!runtimeCurrent(expectedGeneration, expectedManager)) {
            return;
        }
        java.util.Set<String> botNames = expectedManager.snapshots().stream()
            .map(snapshot -> snapshot.username().toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        long humans = backend.getPlayersConnected().stream()
            .filter(player -> !botNames.contains(player.getUsername().toLowerCase(java.util.Locale.ROOT))).count();
        int desired = humans <= rule.maximumHumans() ? rule.minimumBots() : 0;
        List<BotSession> candidates = selectBots(expectedManager, rule.selector()).stream()
            .filter(session -> !expectedManager.isHeld(session.definition().id()))
            .toList();
        for (int index = 0; index < candidates.size(); index++) {
            if (!runtimeCurrent(expectedGeneration, expectedManager)) {
                return;
            }
            BotSession session = candidates.get(index);
            if (index < desired) {
                expectedManager.startAutomatically(session.definition().id());
                switchBotServer(expectedManager, session.definition().id(), backend.getServerInfo().getName());
            }
            else {
                expectedManager.stop(session.definition().id());
            }
        }
    }

    private Optional<RegisteredServer> findServer(String name) {
        String wanted = name == null ? "" : name.trim();
        if (wanted.isEmpty()) {
            return Optional.empty();
        }
        Optional<RegisteredServer> exact = proxy.getServer(wanted);
        if (exact.isPresent()) {
            return exact;
        }
        return proxy.getAllServers().stream()
            .filter(server -> server.getServerInfo().getName().equalsIgnoreCase(wanted))
            .findFirst();
    }

    public enum BotServerSwitchStatus {
        SWITCHED,
        ALREADY_CONNECTED,
        BOT_NOT_FOUND,
        BOT_NOT_READY,
        AUTHENTICATION_PENDING,
        SERVER_NOT_FOUND,
        FAILED
    }

    public record BotServerSwitchResult(
        BotServerSwitchStatus status,
        String botId,
        String username,
        String server,
        String detail
    ) {
        public boolean successful() {
            return status == BotServerSwitchStatus.SWITCHED
                || status == BotServerSwitchStatus.ALREADY_CONNECTED;
        }
    }

    public enum ManagedCreateResult {
        CREATED,
        ALREADY_EXISTS,
        LIMIT_REACHED
    }

    public record ReloadResult(int configuredBots, int managedBots) {
    }

    public record ReloadCheckResult(
        ConfigChangePreview.Preview preview,
        String currentLanguage,
        String candidateLanguage,
        int configuredBots,
        int managedBots
    ) {
    }

    private record ScheduleTiming(Duration initialDelay, Duration interval) {
    }

    public record FollowResult(boolean successful, String detail) {
    }

    public enum ManagedRemoveResult {
        REMOVED,
        STATIC_BOT,
        NOT_FOUND
    }
}
