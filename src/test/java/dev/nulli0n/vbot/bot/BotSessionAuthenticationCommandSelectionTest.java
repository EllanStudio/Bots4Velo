package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig;
import dev.nulli0n.vbot.protocol.ProtocolResolver;
import dev.nulli0n.vbot.protocol.ProtocolSelection;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

class BotSessionAuthenticationCommandSelectionTest {
    @Test
    void autoModeKeepsTheFirstSubmittedCommandWhenBothPromptsArrive() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.message("Please /login");
            fixture.message("Please /register");

            assertThat(fixture.transport.commands).containsExactly("login credential");
            assertThat(fixture.events()).anySatisfy(event -> {
                assertThat(event.type()).isEqualTo("AUTH_PROMPT_IGNORED");
                assertThat(event.detail()).isEqualTo("REGISTER already_submitted=LOGIN");
            });
        }
    }

    @Test
    void structuredPromptCannotSwitchAwayFromTheChatCommand() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.message("Please /register");
            fixture.commandUi("LOGIN");

            assertThat(fixture.transport.commands).containsExactly("register credential credential");
            assertThat(fixture.events()).anySatisfy(event -> {
                assertThat(event.type()).isEqualTo("AUTH_PROMPT_IGNORED");
                assertThat(event.detail()).isEqualTo("LOGIN already_submitted=REGISTER");
            });
        }
    }

    @Test
    void failedSubmissionReleasesTheCommandSelectionForRetry() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.transport.acceptCommands.set(false);
            fixture.message("Please /login");
            fixture.transport.acceptCommands.set(true);
            fixture.message("Please /register");

            assertThat(fixture.transport.commands).containsExactly("register credential credential");
        }
    }

    @Test
    void reconnectClearsTheCommandSelection() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.message("Please /login");
            fixture.resetConnectionState();
            fixture.message("Please /register");

            assertThat(fixture.transport.commands)
                .containsExactly("login credential", "register credential credential");
        }
    }

    private static final class Fixture implements AutoCloseable {
        final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        final RecordingTransport transport = new RecordingTransport();
        final BotSession session;

        Fixture() throws Exception {
            var auth = new BotPluginConfig.AuthConfig(
                BotPluginConfig.AuthMode.AUTO,
                "login {password}", "register {password} {password}",
                0L, 0L, 0L,
                List.of("login"), List.of("register"), List.of("success"), List.of(),
                30_000L, true, "", BotPluginConfig.RegistrationSecondArgument.AUTO, 0L);
            var endpoint = new BotPluginConfig.ProxyEndpoint("127.0.0.1", 9, "localhost", 9,
                ProtocolSelection.fixed(ProtocolVersion.MINECRAFT_1_16_5), 100);
            var runtime = new BotPluginConfig.RuntimeConfig(0L, 0L, 10, 100L, 0L,
                BotPluginConfig.ResourcePackMode.DECLINE, false,
                new BotPluginConfig.ReconnectConfig(0L, 100L, 1.0D, 0.0D, 3));
            var definition = new BotPluginConfig.BotDefinition("command-selection", true,
                "BOT", "credential", "spawn", "", 2, auth, "server {server}",
                1_000L, 0, List.of());
            session = new BotSession(definition, endpoint, runtime,
                new ProtocolResolver(endpoint, definition, (bot, proxy) -> null),
                new TransportRegistry(), new ConnectionRateLimiter(0L), executor,
                LoggerFactory.getLogger(BotSessionAuthenticationCommandSelectionTest.class));
            setField("transport", transport);
            setField("activeProtocolVersion", ProtocolVersion.MINECRAFT_1_16_5);
            ((AtomicBoolean) field("manualStop")).set(false);
            ((AtomicBoolean) field("playInitialized")).set(true);
            @SuppressWarnings("unchecked")
            var state = (java.util.concurrent.atomic.AtomicReference<BotState>) field("state");
            state.set(BotState.PLAY);
        }

        void message(String value) throws Exception {
            invoke("handleAuthMessage", new Class<?>[]{long.class, String.class}, 0L, value);
        }

        void commandUi(String value) throws Exception {
            invoke("handleAuthenticationCommandUi",
                new Class<?>[]{long.class, dev.nulli0n.vbot.transport.AuthenticationUiType.class},
                0L, dev.nulli0n.vbot.transport.AuthenticationUiType.valueOf(value));
        }

        void resetConnectionState() throws Exception {
            invoke("resetConnectionState", new Class<?>[0]);
        }

        List<BotEvent> events() {
            return session.snapshot().recentEvents();
        }

        private void invoke(String name, Class<?>[] types, Object... args) throws Exception {
            Method method = BotSession.class.getDeclaredMethod(name, types);
            method.setAccessible(true);
            method.invoke(session, args);
        }

        private void setField(String name, Object value) throws Exception {
            Field field = BotSession.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(session, value);
        }

        private Object field(String name) throws Exception {
            Field field = BotSession.class.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(session);
        }

        @Override
        public void close() {
            session.stop();
            executor.shutdownNow();
        }
    }

    private static final class RecordingTransport implements BotTransport {
        final List<String> commands = new ArrayList<>();
        final AtomicBoolean connected = new AtomicBoolean(true);
        final AtomicBoolean acceptCommands = new AtomicBoolean(true);

        @Override public void connect() { }
        @Override public void disconnect(String reason) { connected.set(false); }
        @Override public boolean isConnected() { return connected.get(); }
        @Override public boolean sendCommand(String command) {
            if (acceptCommands.get()) {
                commands.add(command);
            }
            return acceptCommands.get();
        }
        @Override public boolean moveTo(double x, double y, double z) { return false; }
        @Override public boolean look(float yaw, float pitch) { return false; }
        @Override public BotPosition position() { return BotPosition.unknown(); }
    }
}
