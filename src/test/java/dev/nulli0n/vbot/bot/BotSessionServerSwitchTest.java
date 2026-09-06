package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.TransportState;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionServerSwitchTest {
    @Test
    void alreadyOnTargetAfterAuthenticationCompletesWithoutSendingSameServerCommand() throws Exception {
        try (Fixture fixture = new Fixture("SPAWN", 0)) {
            fixture.authenticate();
            fixture.assertCompleted(0);
            fixture.executor.runAll();
            assertThat(fixture.transport.commands).containsExactly("say ready");
        }
    }

    @Test
    void arrivingBetweenRetriesCompletesWithoutAnotherProtocolTransitionOrCommand() throws Exception {
        try (Fixture fixture = new Fixture("login", 0)) {
            fixture.authenticate();
            assertThat(fixture.transport.commands).containsExactly("server spawn");
            fixture.backend.set(Optional.of("spawn"));
            fixture.executor.runAll();
            fixture.assertCompleted(1);
            assertThat(fixture.transport.commands).containsExactly("server spawn", "say ready");
        }
    }

    @Test
    void unrelatedPlayTransitionDoesNotConfirmTargetOrRunAfterLoginCommands() throws Exception {
        try (Fixture fixture = new Fixture("lobby", 0)) {
            fixture.authenticate();
            fixture.state(TransportState.CONFIGURATION);
            fixture.state(TransportState.PLAY);
            assertThat(fixture.pending()).isTrue();
            assertThat(fixture.session.snapshot().recentEvents())
                .noneMatch(event -> event.type().equals("SERVER_SWITCHED"));
            assertThat(fixture.transport.commands).containsExactly("server spawn");
            fixture.backend.set(Optional.of("spawn"));
            fixture.state(TransportState.CONFIGURATION);
            fixture.state(TransportState.PLAY);
            fixture.assertCompleted(1);
            fixture.state(TransportState.PLAY);
            fixture.executor.runAll();
            assertThat(fixture.transport.commands).containsExactly("server spawn", "say ready");
        }
    }

    @Test
    void preAuthenticationTransitionToWrongServerDoesNotSkipTargetRequest() throws Exception {
        try (Fixture fixture = new Fixture("lobby", 0)) {
            ((AtomicInteger) field(fixture.session, "playTransitionsThisConnection")).set(2);
            fixture.authenticate();
            assertThat(fixture.pending()).isTrue();
            assertThat(fixture.transport.commands).containsExactly("server spawn");
        }
    }

    @Test
    void missingProxyPlayerDoesNotFallBackToProtocolOnlyConfirmation() throws Exception {
        try (Fixture fixture = new Fixture(null, 0)) {
            fixture.authenticate();
            fixture.state(TransportState.CONFIGURATION);
            fixture.state(TransportState.PLAY);
            assertThat(fixture.pending()).isTrue();
            assertThat(fixture.transport.commands).containsExactly("server spawn");
        }
    }

    @Test
    void targetArrivalAtRetryLimitStillCompletesSuccessfully() throws Exception {
        try (Fixture fixture = new Fixture("login", 1)) {
            fixture.authenticate();
            fixture.backend.set(Optional.of("spawn"));
            fixture.executor.runAll();
            fixture.assertCompleted(1);
        }
    }

    @Test
    void retryLimitFailureDoesNotRunAfterLoginCommands() throws Exception {
        try (Fixture fixture = new Fixture("login", 1)) {
            fixture.authenticate();
            fixture.executor.runAll();
            assertThat(fixture.pending()).isFalse();
            assertThat(fixture.session.snapshot().lastDisconnectReason()).contains("exhausted 1 attempts");
            assertThat(fixture.transport.commands).containsExactly("server spawn");
        }
    }

    @Test
    void targetLocationAloneDoesNotAuthenticateTheBot() throws Exception {
        try (Fixture fixture = new Fixture("spawn", 0)) {
            fixture.state(TransportState.CONFIGURATION);
            fixture.state(TransportState.PLAY);
            assertThat(fixture.session.isAuthenticationComplete()).isFalse();
            assertThat(fixture.transport.commands).isEmpty();
        }
    }

    @Test
    void cancelledRetryCannotCompleteAfterStop() throws Exception {
        try (Fixture fixture = new Fixture("login", 0)) {
            fixture.authenticate();
            fixture.session.stop();
            fixture.backend.set(Optional.of("spawn"));
            fixture.executor.runAll();
            assertThat(fixture.session.snapshot().recentEvents())
                .noneMatch(event -> event.type().equals("SERVER_SWITCHED"));
            assertThat(fixture.transport.commands).containsExactly("server spawn");
        }
    }

    @Test
    void repeatedRequestsUseDebugAndPeriodicWarningsInsteadOfInfoSpam() throws Exception {
        try (Fixture fixture = new Fixture("login", 0)) {
            fixture.authenticate();
            for (int attempt = 2; attempt <= 20; attempt++) {
                fixture.executor.tasks.removeFirst().run();
            }
            assertThat(fixture.logs.stream().filter(log -> log.startsWith("info:Bot {} requested")))
                .hasSize(1);
            assertThat(fixture.logs.stream().filter(log -> log.startsWith("debug:Bot {} requested")))
                .hasSize(19);
            assertThat(fixture.logs.stream().filter(log -> log.startsWith("warn:Bot {} is still waiting")))
                .hasSize(1);
            fixture.backend.set(Optional.of("spawn"));
            fixture.executor.runAll();
            fixture.assertCompleted(20);
        }
    }

    @Test
    void legacyPlayTransitionAlsoRequiresTheActualTargetWhenProxyLookupIsAvailable() throws Exception {
        try (Fixture fixture = new Fixture("lobby", 0)) {
            setField(fixture.session, "activeProtocolVersion", ProtocolVersion.MINECRAFT_1_16_5);
            fixture.authenticate();
            fixture.state(TransportState.PLAY);
            assertThat(fixture.pending()).isTrue();
            fixture.backend.set(Optional.of("spawn"));
            fixture.state(TransportState.PLAY);
            fixture.assertCompleted(1);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ManualExecutor executor = new ManualExecutor();
        final RecordingTransport transport = new RecordingTransport();
        final List<String> logs = new ArrayList<>();
        final AtomicReference<Optional<String>> backend;
        final BotSession session;

        Fixture(String currentServer, int maximumAttempts) throws Exception {
            backend = new AtomicReference<>(Optional.ofNullable(currentServer));
            var auth = new BotPluginConfig.AuthConfig(BotPluginConfig.AuthMode.LOGIN,
                "login {password}", "", 0L, 0L, 0L,
                List.of(), List.of(), List.of("success"));
            var endpoint = new BotPluginConfig.ProxyEndpoint("127.0.0.1", 9, "localhost", 9,
                ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
            var runtime = new BotPluginConfig.RuntimeConfig(0L, 0L, 10, 100L, 0L,
                BotPluginConfig.ResourcePackMode.DECLINE, false,
                new BotPluginConfig.ReconnectConfig(1_000L, 10_000L, 2.0D, 0.0D, 3));
            var definition = new BotPluginConfig.BotDefinition("issue3", true, "Jail_Test",
                "test-credential", "spawn", "", 2, auth, "server {server}", 3_000L,
                maximumAttempts, List.of("say ready"));
            session = new BotSession(definition, endpoint, runtime,
                new ProtocolResolver(endpoint, definition, (bot, proxy) -> null), new TransportRegistry(),
                new ConnectionRateLimiter(0L), executor, (Logger) Proxy.newProxyInstance(
                    Logger.class.getClassLoader(), new Class<?>[]{Logger.class}, (proxy, method, args) -> {
                        if (args != null && args.length > 0) {
                            logs.add(method.getName() + ":" + args[0]);
                        }
                        return method.getReturnType() == boolean.class ? false : null;
                    }),
                ignored -> { }, () -> false, backend::get);
            setField(session, "transport", transport);
            setField(session, "activeProtocolVersion", ProtocolVersion.MINECRAFT_26_2);
            ((AtomicBoolean) field(session, "manualStop")).set(false);
            ((AtomicBoolean) field(session, "playInitialized")).set(true);
            state(TransportState.PLAY);
        }

        void authenticate() throws Exception {
            ((BotSession.AuthenticationOutcomeGate) field(session, "authenticationOutcome")).succeed();
            invoke("applyAuthenticationSuccess", new Class<?>[]{long.class}, 0L);
        }

        boolean pending() throws Exception {
            return ((AtomicBoolean) field(session, "serverSwitchPending")).get();
        }

        void state(TransportState state) throws Exception {
            invoke("onTransportState", new Class<?>[]{long.class, TransportState.class}, 0L, state);
        }

        void invoke(String name, Class<?>[] types, Object... arguments) throws Exception {
            Method method = BotSession.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(session, arguments);
        }

        void assertCompleted(int attempts) throws Exception {
            assertThat(pending()).isFalse();
            assertThat(((AtomicInteger) field(session, "serverSwitchAttempts")).get()).isEqualTo(attempts);
            assertThat(session.snapshot().recentEvents().stream()
                .filter(event -> event.type().equals("SERVER_SWITCHED"))).hasSize(1);
        }

        @Override public void close() {
            session.stop();
            executor.shutdownNow();
        }
    }

    private static Object field(Object target, String name) throws Exception {
        Field field = BotSession.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = BotSession.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class ManualExecutor extends ScheduledThreadPoolExecutor {
        private final List<Runnable> tasks = new ArrayList<>();
        ManualExecutor() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            tasks.add(command);
            return super.schedule(command, 1, TimeUnit.DAYS);
        }
        void runAll() {
            int count = 0;
            while (!tasks.isEmpty()) {
                assertThat(++count).as("scheduler must quiesce").isLessThan(20);
                tasks.removeFirst().run();
            }
        }
    }

    private static final class RecordingTransport implements BotTransport {
        final List<String> commands = new ArrayList<>();
        @Override public void connect() { }
        @Override public void disconnect(String reason) { }
        @Override public boolean isConnected() { return true; }
        @Override public boolean sendCommand(String command) { commands.add(command); return true; }
        @Override public boolean moveTo(double x, double y, double z) { return false; }
        @Override public boolean look(float yaw, float pitch) { return false; }
        @Override public BotPosition position() { return BotPosition.unknown(); }
    }
}
