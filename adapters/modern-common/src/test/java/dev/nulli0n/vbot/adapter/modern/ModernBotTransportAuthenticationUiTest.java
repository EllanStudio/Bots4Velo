package dev.nulli0n.vbot.adapter.modern;

import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiInputPurpose;
import dev.nulli0n.vbot.transport.AuthenticationUiProvider;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import io.netty.util.concurrent.OrderedEventExecutor;
import org.cloudburstmc.nbt.NbtMap;
import org.cloudburstmc.nbt.NbtType;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.protocol.data.game.Holder;
import org.geysermc.mcprotocollib.protocol.data.game.chat.ChatFilterType;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.assertj.core.api.Assertions.assertThat;

class ModernBotTransportAuthenticationUiTest {

    @Test
    void keepsProtocolPacketHandlingOffTheSharedBotScheduler() {
        RecordingListener listener = new RecordingListener();
        ScheduledExecutorService sharedScheduler = Executors.newScheduledThreadPool(4);
        try {
            ClientSession session = transport(listener, sharedScheduler).createClientSession();

            assertThat(session.getPacketHandlerExecutor())
                .isNotSameAs(sharedScheduler)
                .isInstanceOf(OrderedEventExecutor.class);
        }
        finally {
            sharedScheduler.shutdownNow();
        }
    }

    @Test
    void normalizesAllAuthMeModernTextPacketVariants() {
        String success = "*** 已成功登录 ***";

        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundSystemChatPacket(Component.text(success), false))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundPlayerChatPacket(0, UUID.randomUUID(), 0, new byte[0], success, 0L, 0L,
                List.of(), Component.text(success), ChatFilterType.PASS_THROUGH, Holder.ofId(0),
                Component.empty(), Component.empty()))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundDisguisedChatPacket(Component.text(success), Holder.ofId(0),
                Component.empty(), Component.empty()))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundSetTitleTextPacket(Component.text(success)))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundSetSubtitleTextPacket(Component.text(success)))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundSetActionBarTextPacket(Component.text(success)))).isEqualTo(success);
        assertThat(ModernBotTransport.authenticationMessage(
            new ClientboundSystemChatPacket(Component.text(success), true))).isEqualTo(success);
    }

    @Test
    void recognizesAuthMeUi134CustomClickCompound() {
        NbtMap dialog = dialog(
            "Welcome Back!",
            List.of(textInput("password", "Password")),
            List.of(action("authmeui:action/login", "Sign In"))
        );
        RecordingListener listener = new RecordingListener();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            transport(listener, executor).handleAuthenticationDialog(dialog);

            AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);

            assertThat(challenge).isNotNull();
            assertThat(challenge.actionId()).isEqualTo("authmeui:action/login");
            assertThat(challenge.passwordInput()).isEqualTo("password");
            assertThat(listener.challenges).containsExactly(challenge);
            assertThat(listener.commandTypes).isEmpty();
            assertThat(listener.systemMessages).isEmpty();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void ignoresUnknownDialogWithPlainActionStringWithoutForwardingItsLoginText() {
        NbtMap dialog = NbtMap.builder()
            .putString("title", "Please login immediately")
            .putList("inputs", NbtType.COMPOUND, List.of(textInput("password", "Password")))
            .putString("action", "authmeui:action/login")
            .build();
        RecordingListener listener = new RecordingListener();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            transport(listener, executor).handleAuthenticationDialog(dialog);

            assertThat(ModernBotTransport.parseAuthenticationUi(dialog)).isNull();
            assertThat(listener.systemMessages).isEmpty();
            assertThat(listener.challenges).isEmpty();
            assertThat(listener.commandTypes).isEmpty();
            assertThat(listener.diagnostics)
                .containsExactly("Ignored a dialog without a recognized AuthMe/AuthMeUI submit action");
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void recognizesOnlyOfficialAuthMePostJoinCommandTemplatesAcrossCodecVariants() {
        List<String> actionTypes = List.of("dynamic/run_command", "minecraft:dynamic/run_command");
        for (String actionType : actionTypes) {
            NbtMap login = dialog(
                "Custom title that is not used for detection",
                List.of(textInput("password", "Arbitrary label")),
                List.of(commandAction(actionType, "login $(password)", "Continue"))
            );
            assertThat(ModernBotTransport.parseAuthenticationCommandUi(login))
                .as("login action type %s", actionType)
                .isEqualTo(AuthenticationUiType.LOGIN);
        }

        List<String> registerTemplates = List.of(
            "register $(password)",
            "register $(password) $(confirm)",
            "register $(password) $(email)",
            "register $(email)",
            "register $(email) $(confirm)"
        );
        for (String template : registerTemplates) {
            NbtMap register = dialog(
                "Create account",
                inputsForTemplate(template),
                List.of(commandAction("dynamic/run_command", template, "Continue"))
            );
            assertThat(ModernBotTransport.parseAuthenticationCommandUi(register))
                .as("register template %s", template)
                .isEqualTo(AuthenticationUiType.REGISTER);
        }
    }

    @Test
    void reportsStructuredAuthMePostJoinIntentWithoutForwardingVisibleDialogText() {
        NbtMap dialog = dialog(
            "Please login with text that must never reach message matching",
            List.of(textInput("password", "Visible password label")),
            List.of(commandAction("dynamic/run_command", "login $(password)", "Login now"))
        );
        RecordingListener listener = new RecordingListener();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            transport(listener, executor).handleAuthenticationDialog(dialog);

            assertThat(listener.commandTypes).containsExactly(AuthenticationUiType.LOGIN);
            assertThat(listener.challenges).isEmpty();
            assertThat(listener.systemMessages).isEmpty();
            assertThat(listener.diagnostics).isEmpty();
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void rejectsRecoveryTwoFactorArbitraryAndAmbiguousCommandDialogs() {
        for (String unsafeTemplate : List.of(
            "2fa code $(code)",
            "email recover $(email)",
            "help login",
            "login $(password) extra",
            "/login $(password)",
            "LOGIN $(password)"
        )) {
            NbtMap unsafe = dialog(
                "Authentication",
                List.of(),
                List.of(commandAction("dynamic/run_command", unsafeTemplate, "Continue"))
            );
            assertThat(ModernBotTransport.parseAuthenticationCommandUi(unsafe))
                .as("unsafe template %s", unsafeTemplate)
                .isNull();
        }

        NbtMap staticCommand = dialog(
            "Login",
            List.of(),
            List.of(commandAction("run_command", "login $(password)", "Login"))
        );
        assertThat(ModernBotTransport.parseAuthenticationCommandUi(staticCommand)).isNull();

        NbtMap ambiguous = dialog(
            "Unexpected",
            List.of(),
            List.of(
                commandAction("dynamic/run_command", "login $(password)", "Login"),
                commandAction("dynamic/run_command", "register $(password)", "Register")
            )
        );
        assertThat(ModernBotTransport.parseAuthenticationCommandUi(ambiguous)).isNull();
    }

    @Test
    void rejectsNestedForgedActionsAndLegacyButtonsContainer() {
        NbtMap forgedClick = NbtMap.builder()
            .putString("type", "dynamic/custom")
            .putString("id", "authmeui:action/login")
            .build();
        NbtMap nestedAction = NbtMap.builder()
            .putString("label", "Login")
            .putCompound("tooltip", NbtMap.builder().putCompound("action", forgedClick).build())
            .build();
        NbtMap nestedDialog = dialog(
            "Login",
            List.of(textInput("password", "Password")),
            List.of(nestedAction)
        );

        NbtMap legacyButtonsDialog = NbtMap.builder()
            .putString("type", "minecraft:multi_action")
            .putString("title", "Login")
            .putList("inputs", NbtType.COMPOUND, List.of(textInput("password", "Password")))
            .putList("buttons", NbtType.COMPOUND, List.of(action("authmeui:action/login", "Login")))
            .build();

        assertThat(ModernBotTransport.parseAuthenticationUi(nestedDialog)).isNull();
        assertThat(ModernBotTransport.parseAuthenticationCommandUi(nestedDialog)).isNull();
        assertThat(ModernBotTransport.parseAuthenticationUi(legacyButtonsDialog)).isNull();
    }

    @Test
    void rejectsNonMinecraftCustomTypeAndActionFieldAliases() {
        NbtMap evilCustom = directAction(NbtMap.builder()
            .putString("type", "evil:custom")
            .putString("id", "authmeui:action/login")
            .build(), "Login");
        NbtMap aliasedCustom = directAction(NbtMap.builder()
            .putString("action", "dynamic/custom")
            .putString("identifier", "authmeui:action/login")
            .build(), "Login");
        NbtMap aliasedId = directAction(NbtMap.builder()
            .putString("type", "dynamic/custom")
            .putString("identifier", "authmeui:action/login")
            .build(), "Login");

        for (NbtMap invalidAction : List.of(evilCustom, aliasedCustom, aliasedId)) {
            NbtMap invalid = dialog(
                "Login",
                List.of(textInput("password", "Password")),
                List.of(invalidAction)
            );
            assertThat(ModernBotTransport.parseAuthenticationUi(invalid)).isNull();
        }
    }

    @Test
    void rejectsDuplicateInstancesOfTheSameAuthenticationAction() {
        NbtMap duplicate = dialog(
            "Login",
            List.of(textInput("password", "Password")),
            List.of(
                action("authmeui:action/login", "Login"),
                action("authmeui:action/login", "Login again")
            )
        );

        assertThat(ModernBotTransport.parseAuthenticationUi(duplicate)).isNull();
    }

    @Test
    void rejectsCommandTemplatesWhosePlaceholdersAreNotDeclaredByRootInputs() {
        NbtMap missingInput = dialog(
            "Login",
            List.of(),
            List.of(commandAction("dynamic/run_command", "login $(password)", "Login"))
        );
        NbtMap nestedInput = NbtMap.builder()
            .putString("type", "minecraft:multi_action")
            .putString("title", "Login")
            .putList("inputs", NbtType.COMPOUND, List.of())
            .putCompound("body", NbtMap.builder()
                .putList("inputs", NbtType.COMPOUND, List.of(textInput("password", "Password")))
                .build())
            .putList("actions", NbtType.COMPOUND,
                List.of(commandAction("dynamic/run_command", "login $(password)", "Login")))
            .build();

        assertThat(ModernBotTransport.parseAuthenticationCommandUi(missingInput)).isNull();
        assertThat(ModernBotTransport.parseAuthenticationCommandUi(nestedInput)).isNull();
    }

    @Test
    void ignoresInputKeysOutsideTheOfficialSafeSubset() {
        for (String invalidKey : List.of("password.with.dot", "password-with-dash", "密码")) {
            NbtMap invalid = dialog(
                "Login",
                List.of(textInput(invalidKey, "Password")),
                List.of(action("authmeui:action/login", "Login"))
            );

            assertThat(ModernBotTransport.parseAuthenticationUi(invalid).passwordInput())
                .as("invalid input key %s", invalidKey)
                .isNull();
        }
    }

    @Test
    void rejectsCrossTypeAuthenticationActionAmbiguity() {
        NbtMap ambiguous = dialog(
            "Login",
            List.of(textInput("password", "Password")),
            List.of(
                action("authmeui:action/login", "AuthMeUI login"),
                commandAction("dynamic/run_command", "login $(password)", "AuthMe login")
            )
        );
        RecordingListener listener = new RecordingListener();
        ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
        try {
            transport(listener, executor).handleAuthenticationDialog(ambiguous);

            assertThat(ModernBotTransport.parseAuthenticationUi(ambiguous)).isNull();
            assertThat(ModernBotTransport.parseAuthenticationCommandUi(ambiguous)).isNull();
            assertThat(listener.challenges).isEmpty();
            assertThat(listener.commandTypes).isEmpty();
            assertThat(listener.systemMessages).isEmpty();
            assertThat(listener.diagnostics)
                .containsExactly("Ignored a dialog without a recognized AuthMe/AuthMeUI submit action");
        }
        finally {
            executor.shutdownNow();
        }
    }

    @Test
    void selectsLoginWhenAuthMeUiActionsAreOutOfOrderAndNeverSelectsUnsafeActions() {
        NbtMap dialog = dialog(
            "Welcome",
            List.of(textInput("password", "Password")),
            List.of(
                action("authmeui:action/support", "Support"),
                action("authmeui:action/login_cancel", "Cancel"),
                action("authmeui:action/login_forgot", "Forgot"),
                action("authmeui:action/login", "Login"),
                action("authmeui:action/forgot", "Recover")
            )
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);

        assertThat(challenge).isNotNull();
        assertThat(challenge.actionId()).isEqualTo("authmeui:action/login");
        assertThat(challenge.type()).isEqualTo(AuthenticationUiType.LOGIN);
        assertThat(challenge.provider()).isEqualTo(AuthenticationUiProvider.AUTHME_UI);
        assertThat(challenge.passwordInput()).isEqualTo("password");
    }

    @Test
    void identifiesAuthMeUiFromExactActionDespiteChineseCustomTitleAndLabels() {
        NbtMap dialog = dialog(
            "欢迎回来，请验证身份",
            List.of(textInput("password", "请输入您的密码")),
            List.of(action("authmeui:action/login", "进入服务器"))
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);

        assertThat(challenge).isNotNull();
        assertThat(challenge.type()).isEqualTo(AuthenticationUiType.LOGIN);
        assertThat(challenge.provider()).isEqualTo(AuthenticationUiProvider.AUTHME_UI);
        assertThat(ModernBotTransport.nonSensitiveDialogText(dialog))
            .contains("欢迎回来，请验证身份", "请输入您的密码", "进入服务器")
            .doesNotContain("authmeui:action/login");
    }

    @Test
    void extractsCustomRulesCheckboxKeyAndWritesBooleanTrue() {
        NbtMap dialog = dialog(
            "Server rules",
            List.of(booleanInput("server_policy_accepted", "I accept the rules")),
            List.of(action("authmeui:action/rules_confirm", "Continue"))
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);
        NbtMap payload = ModernBotTransport.authenticationPayload(challenge, "must-not-leak", "also-secret");

        assertThat(challenge.type()).isEqualTo(AuthenticationUiType.RULES);
        assertThat(challenge.agreementInput()).isEqualTo("server_policy_accepted");
        assertThat(payload).containsOnlyKeys("server_policy_accepted");
        assertThat(payload.getBoolean("server_policy_accepted")).isTrue();
        assertThat(payload.toString()).doesNotContain("must-not-leak", "also-secret");
    }

    @Test
    void rulesWithoutAgreementFieldStillBuildsAnEmptyActionPayload() {
        NbtMap dialog = dialog(
            "Server rules",
            List.of(),
            List.of(action("authmeui:action/rules_confirm", "Continue"))
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);
        NbtMap payload = ModernBotTransport.authenticationPayload(challenge, "must-not-leak", "also-secret");

        assertThat(challenge.type()).isEqualTo(AuthenticationUiType.RULES);
        assertThat(challenge.agreementInput()).isNull();
        assertThat(payload).isEmpty();
    }

    @Test
    void registrationWithoutConfirmationOnlySendsTheExistingPasswordField() {
        NbtMap dialog = dialog(
            "Create account",
            List.of(textInput("password", "Choose a password")),
            List.of(action("authmeui:action/register", "Register"))
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);
        NbtMap payload = ModernBotTransport.authenticationPayload(challenge, "secret", "bot@example.test");

        assertThat(challenge.secondaryInput()).isNull();
        assertThat(challenge.secondaryInputPurpose()).isEqualTo(AuthenticationUiInputPurpose.NONE);
        assertThat(payload).containsOnlyKeys("password");
        assertThat(payload.getString("password")).isEqualTo("secret");
    }

    @Test
    void recognizesAuthMeUiEmailModeEvenThoughItsSecondFieldKeyIsConfirm() {
        NbtMap dialog = dialog(
            "注册账号",
            List.of(
                textInput("password", "设置密码"),
                textInput("confirm", "电子邮箱")
            ),
            List.of(action("authmeui:action/register", "注册"))
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);
        NbtMap payload = ModernBotTransport.authenticationPayload(challenge, "secret", "bot@example.test");

        assertThat(challenge.secondaryInput()).isEqualTo("confirm");
        assertThat(challenge.secondaryInputPurpose()).isEqualTo(AuthenticationUiInputPurpose.EMAIL);
        assertThat(payload.getString("password")).isEqualTo("secret");
        assertThat(payload.getString("confirm")).isEqualTo("bot@example.test");
    }

    @Test
    void preservesBuiltInAuthMePreJoinDialogContract() {
        NbtMap dialog = dialog(
            "Login",
            List.of(textInput("password", "Password")),
            List.of(
                action("authme:prejoin-login/cancel", "Cancel"),
                action("authme:prejoin-login/submit", "Login")
            )
        );

        AuthenticationUiChallenge challenge = ModernBotTransport.parseAuthenticationUi(dialog);

        assertThat(challenge.provider()).isEqualTo(AuthenticationUiProvider.AUTHME);
        assertThat(challenge.actionId()).isEqualTo("authme:prejoin-login/submit");
    }

    @Test
    void returnsNullForUnknownNamespaceAndDoesNotCreatePasswordPayload() {
        NbtMap dialog = dialog(
            "Login",
            List.of(textInput("password", "Password")),
            List.of(action("otherauth:action/login", "Login"))
        );

        assertThat(ModernBotTransport.parseAuthenticationUi(dialog)).isNull();
    }

    @Test
    void rejectsAmbiguousDialogsContainingTwoAllowedSubmitActions() {
        NbtMap dialog = dialog(
            "Unexpected",
            List.of(textInput("password", "Password")),
            List.of(
                action("authmeui:action/login", "Login"),
                action("authmeui:action/register", "Register")
            )
        );

        assertThat(ModernBotTransport.parseAuthenticationUi(dialog)).isNull();
    }

    @Test
    void nonSensitiveTextNeverForwardsInputInitialValues() {
        NbtMap passwordInput = NbtMap.builder()
            .putString("type", "minecraft:text")
            .putString("key", "password")
            .putString("label", "Password")
            .putString("initial", "server-side-secret")
            .build();
        NbtMap dialog = dialog(
            "Please log in",
            List.of(passwordInput),
            List.of(action("authmeui:action/login", "Login"))
        );

        assertThat(ModernBotTransport.nonSensitiveDialogText(dialog))
            .contains("Please log in", "Password", "Login")
            .doesNotContain("server-side-secret", "authmeui:action/login");
    }

    private static NbtMap dialog(String title, List<NbtMap> inputs, List<NbtMap> actions) {
        var builder = NbtMap.builder()
            .putString("type", "minecraft:multi_action")
            .putString("title", title);
        builder.putList("inputs", NbtType.COMPOUND, inputs);
        builder.putList("actions", NbtType.COMPOUND, actions);
        return builder.build();
    }

    private static List<NbtMap> inputsForTemplate(String template) {
        List<NbtMap> inputs = new ArrayList<>();
        if (template.contains("$(password)")) {
            inputs.add(textInput("password", "Password"));
        }
        if (template.contains("$(confirm)")) {
            inputs.add(textInput("confirm", "Confirm"));
        }
        if (template.contains("$(email)")) {
            inputs.add(textInput("email", "Email"));
        }
        return inputs;
    }

    private static NbtMap textInput(String key, String label) {
        return NbtMap.builder()
            .putString("type", "minecraft:text")
            .putString("key", key)
            .putString("label", label)
            .putString("initial", "")
            .build();
    }

    private static NbtMap booleanInput(String key, String label) {
        return NbtMap.builder()
            .putString("type", "minecraft:boolean")
            .putString("key", key)
            .putString("label", label)
            .putBoolean("initial", false)
            .build();
    }

    private static NbtMap action(String id, String label) {
        NbtMap click = NbtMap.builder()
            .putString("type", "dynamic/custom")
            .putString("id", id)
            .build();
        return directAction(click, label);
    }

    private static NbtMap directAction(NbtMap action, String label) {
        return NbtMap.builder()
            .putString("label", label)
            .putCompound("action", action)
            .build();
    }

    private static NbtMap commandAction(String type, String template, String label) {
        NbtMap command = NbtMap.builder()
            .putString("type", type)
            .putString("template", template)
            .build();
        return NbtMap.builder()
            .putString("label", label)
            .putCompound("action", command)
            .build();
    }

    private static ModernBotTransport transport(
        RecordingListener listener,
        ScheduledExecutorService executor
    ) {
        return new ModernBotTransport(
            new TransportConfig("FixtureBot", UUID.randomUUID(), "127.0.0.1", 25565,
                "localhost", 25565, 2, false, 0L, false),
            listener,
            executor
        );
    }

    private static final class RecordingListener implements TransportListener {
        private final List<String> systemMessages = new ArrayList<>();
        private final List<AuthenticationUiChallenge> challenges = new ArrayList<>();
        private final List<AuthenticationUiType> commandTypes = new ArrayList<>();
        private final List<String> diagnostics = new ArrayList<>();

        @Override
        public void onStateChanged(TransportState state) {
        }

        @Override
        public void onSystemMessage(String message) {
            systemMessages.add(message);
        }

        @Override
        public void onAuthenticationUi(AuthenticationUiChallenge challenge) {
            challenges.add(challenge);
        }

        @Override
        public void onAuthenticationCommandUi(AuthenticationUiType type) {
            commandTypes.add(type);
        }

        @Override
        public void onDisconnected(String reason, Throwable cause) {
        }

        @Override
        public void onDiagnostic(String message) {
            diagnostics.add(message);
        }
    }
}
