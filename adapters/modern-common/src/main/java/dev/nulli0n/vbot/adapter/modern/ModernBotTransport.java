package dev.nulli0n.vbot.adapter.modern;

import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiInputPurpose;
import dev.nulli0n.vbot.transport.AuthenticationUiProvider;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import dev.nulli0n.vbot.transport.BotPosition;
import dev.nulli0n.vbot.transport.BotTransport;
import dev.nulli0n.vbot.transport.TransportConfig;
import dev.nulli0n.vbot.transport.TransportListener;
import dev.nulli0n.vbot.transport.TransportState;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.nbt.NbtMap;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomClickActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundShowDialogConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundShowDialogGamePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundSetHealthPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetSubtitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetActionBarTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.title.ClientboundSetTitleTextPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class ModernBotTransport implements BotTransport {
    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();
    private static final String AUTHME_LOGIN_ACTION = "authme:prejoin-login/submit";
    private static final String AUTHME_REGISTER_ACTION = "authme:prejoin-register/submit";
    private static final String AUTHME_UI_LOGIN_ACTION = "authmeui:action/login";
    private static final String AUTHME_UI_REGISTER_ACTION = "authmeui:action/register";
    private static final String AUTHME_UI_RULES_ACTION = "authmeui:action/rules_confirm";
    private static final Map<String, CommandTemplateSpec> AUTHME_POST_JOIN_COMMANDS = Map.of(
        "login $(password)", new CommandTemplateSpec(AuthenticationUiType.LOGIN, Set.of("password")),
        "register $(password)", new CommandTemplateSpec(AuthenticationUiType.REGISTER, Set.of("password")),
        "register $(password) $(confirm)",
            new CommandTemplateSpec(AuthenticationUiType.REGISTER, Set.of("password", "confirm")),
        "register $(password) $(email)",
            new CommandTemplateSpec(AuthenticationUiType.REGISTER, Set.of("password", "email")),
        "register $(email)", new CommandTemplateSpec(AuthenticationUiType.REGISTER, Set.of("email")),
        "register $(email) $(confirm)",
            new CommandTemplateSpec(AuthenticationUiType.REGISTER, Set.of("email", "confirm"))
    );
    private static final Map<String, RecognizedAction> AUTHENTICATION_ACTIONS = Map.of(
        AUTHME_LOGIN_ACTION, new RecognizedAction(AuthenticationUiType.LOGIN, AuthenticationUiProvider.AUTHME),
        AUTHME_REGISTER_ACTION, new RecognizedAction(AuthenticationUiType.REGISTER, AuthenticationUiProvider.AUTHME),
        AUTHME_UI_LOGIN_ACTION, new RecognizedAction(AuthenticationUiType.LOGIN, AuthenticationUiProvider.AUTHME_UI),
        AUTHME_UI_REGISTER_ACTION, new RecognizedAction(AuthenticationUiType.REGISTER, AuthenticationUiProvider.AUTHME_UI),
        AUTHME_UI_RULES_ACTION, new RecognizedAction(AuthenticationUiType.RULES, AuthenticationUiProvider.AUTHME_UI)
    );

    private final TransportConfig config;
    private final TransportListener listener;
    private final ScheduledExecutorService executor;
    private final AtomicReference<TransportState> state = new AtomicReference<>(TransportState.LOGIN);
    private final AtomicBoolean respawnPending = new AtomicBoolean();
    private final AtomicBoolean enteredPlay = new AtomicBoolean();
    private final AtomicBoolean positionKnown = new AtomicBoolean();
    private final Object movementLock = new Object();
    private volatile ClientSession session;
    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    ModernBotTransport(TransportConfig config, TransportListener listener, ScheduledExecutorService executor) {
        this.config = config;
        this.listener = listener;
        this.executor = executor;
    }

    @Override
    public void connect() {
        ClientSession created = createClientSession();
        created.setFlag(MinecraftConstants.CLIENT_HOST, config.virtualHost());
        created.setFlag(MinecraftConstants.CLIENT_PORT, config.virtualPort());
        created.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        created.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, true);
        created.addListener(new PacketListener());
        session = created;
        created.connect(false);
    }

    ClientSession createClientSession() {
        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(config.uuid(), config.username()), null);
        // MCProtocolLib's default executor is a serial event loop selected for
        // this session. Login, configuration and PLAY state transitions must
        // never run concurrently on Bots4Velo's shared scheduling pool.
        return ClientNetworkSessionFactory.factory()
            .setAddress(config.address(), config.port())
            .setProtocol(protocol)
            .create();
    }

    @Override
    public void disconnect(String reason) {
        ClientSession active = session;
        if (active != null) {
            active.disconnect(reason);
            if (session == active) {
                session = null;
            }
        }
    }

    @Override
    public boolean isConnected() {
        ClientSession active = session;
        return active != null && active.isConnected();
    }

    @Override
    public boolean sendCommand(String command) {
        ClientSession active = session;
        if (active == null || !active.isConnected() || state.get() != TransportState.PLAY) {
            return false;
        }
        active.send(new ServerboundChatCommandPacket(command));
        return true;
    }

    @Override
    public boolean submitAuthenticationUi(AuthenticationUiChallenge challenge, String password) {
        return submitAuthenticationUi(challenge, password, null);
    }

    @Override
    public boolean submitAuthenticationUi(
        AuthenticationUiChallenge challenge,
        String password,
        String registrationEmail
    ) {
        RecognizedAction recognized = challenge == null
            ? null
            : AUTHENTICATION_ACTIONS.get(challenge.actionId());
        if (recognized == null || recognized.type() != challenge.type()
            || recognized.provider() != challenge.provider()) {
            listener.onDiagnostic("Refused an unrecognized authentication dialog action");
            return false;
        }
        ClientSession active = session;
        TransportState currentState = state.get();
        if (active == null || !active.isConnected()
            || (currentState != TransportState.CONFIGURATION && currentState != TransportState.PLAY)) {
            return false;
        }
        String safePassword = password == null ? "" : password;
        String safeEmail = registrationEmail == null ? "" : registrationEmail;
        NbtMap payload = authenticationPayload(challenge, safePassword, safeEmail);
        active.send(new ServerboundCustomClickActionPacket(Key.key(challenge.actionId()), payload));
        listener.onDiagnostic("Submitted " + challenge.description());
        return true;
    }

    static NbtMap authenticationPayload(
        AuthenticationUiChallenge challenge,
        String password,
        String registrationEmail
    ) {
        String safePassword = password == null ? "" : password;
        String safeEmail = registrationEmail == null ? "" : registrationEmail;
        var payload = NbtMap.builder();
        if (challenge.type() == AuthenticationUiType.LOGIN) {
            putStringIfPresent(payload, challenge.passwordInput(), safePassword);
        }
        else if (challenge.type() == AuthenticationUiType.REGISTER) {
            putStringIfPresent(payload, challenge.passwordInput(), safePassword);
            if (challenge.secondaryInputPurpose() == AuthenticationUiInputPurpose.CONFIRMATION) {
                putStringIfPresent(payload, challenge.secondaryInput(), safePassword);
            }
            else if (challenge.secondaryInputPurpose() == AuthenticationUiInputPurpose.EMAIL) {
                putStringIfPresent(payload, challenge.secondaryInput(), safeEmail);
            }
        }
        else if (challenge.type() == AuthenticationUiType.RULES
            && isSafeInputKey(challenge.agreementInput())) {
            payload.putBoolean(challenge.agreementInput(), true);
        }
        return payload.build();
    }

    private static void putStringIfPresent(
        org.cloudburstmc.nbt.NbtMapBuilder payload,
        String key,
        String value
    ) {
        if (isSafeInputKey(key)) {
            payload.putString(key, value);
        }
    }

    @Override
    public boolean moveTo(double newX, double newY, double newZ) {
        if (!Double.isFinite(newX) || !Double.isFinite(newY) || !Double.isFinite(newZ)) {
            return false;
        }
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            x = newX;
            y = newY;
            z = newZ;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean look(float newYaw, float newPitch) {
        if (!Float.isFinite(newYaw) || !Float.isFinite(newPitch) || newPitch < -90.0F || newPitch > 90.0F) {
            return false;
        }
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            yaw = newYaw;
            pitch = newPitch;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean swingMainHand() {
        ClientSession active = playableSession();
        if (active == null) {
            return false;
        }
        active.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
        return true;
    }

    @Override
    public boolean jump() {
        synchronized (movementLock) {
            ClientSession active = playableSession();
            if (active == null || !positionKnown.get()) {
                return false;
            }
            y += 0.42D;
            active.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
            return true;
        }
    }

    @Override
    public boolean setSneaking(boolean sneaking) {
        ClientSession active = playableSession();
        if (active == null) {
            return false;
        }
        active.send(new ServerboundPlayerInputPacket(false, false, false, false, false, sneaking, false));
        return true;
    }

    @Override
    public BotPosition position() {
        synchronized (movementLock) {
            return positionKnown.get() ? BotPosition.known(x, y, z, yaw, pitch) : BotPosition.unknown();
        }
    }

    private ClientSession playableSession() {
        ClientSession active = session;
        return active != null && active.isConnected() && state.get() == TransportState.PLAY ? active : null;
    }

    private void onPacket(Session source, Packet packet) {
        if (packet instanceof ClientboundLoginFinishedPacket) {
            changeState(TransportState.CONFIGURATION);
            source.send(clientInformation());
        }
        else if (packet instanceof ClientboundStartConfigurationPacket) {
            changeState(TransportState.CONFIGURATION);
        }
        else if (packet instanceof ClientboundFinishConfigurationPacket) {
            source.send(clientInformation());
            if (enteredPlay.get()) {
                changeState(TransportState.PLAY);
            }
        }
        else if (packet instanceof ClientboundLoginPacket) {
            enteredPlay.set(true);
            changeState(TransportState.PLAY);
        }
        else if (packet instanceof ClientboundCookieRequestPacket cookie) {
            source.send(new ServerboundCookieResponsePacket(cookie.getKey(), null));
        }
        else if (packet instanceof ClientboundShowDialogConfigurationPacket dialog) {
            handleAuthenticationDialog(dialog.getDialog());
        }
        else if (packet instanceof ClientboundShowDialogGamePacket dialog && dialog.getDialog().isCustom()) {
            handleAuthenticationDialog(dialog.getDialog().custom());
        }
        else if (packet instanceof ClientboundResourcePackPushPacket resourcePack) {
            handleResourcePack(source, resourcePack);
        }
        else if (packet instanceof ClientboundPingPacket ping) {
            // Vulcan and similar anti-cheats use the common Ping/Pong pair as
            // a confirmation packet.  MCProtocolLib's default client listener
            // handles KeepAlive, but intentionally leaves this packet to the
            // application.  A headless client must answer it or the backend
            // will disconnect the bot after the no-response timeout.
            source.send(new ServerboundPongPacket(ping.getId()));
        }
        else if (packet instanceof ClientboundPlayerPositionPacket position) {
            acknowledgeTeleport(source, position);
        }
        else if (packet instanceof ClientboundSetHealthPacket health
            && health.getHealth() <= 0.0F && config.autoRespawn()) {
            scheduleRespawn(source);
        }
        else {
            String message = authenticationMessage(packet);
            if (message != null) {
                listener.onSystemMessage(message);
            }
        }
    }

    /**
     * AuthMe may deliver the same success/prompt through system chat, signed
     * player chat, disguised chat, or a title/action-bar packet depending on
     * server version and chat decoration settings. Keep all of those packets
     * on the same plain-text path used by BotSession's configured matchers.
     */
    static String authenticationMessage(Packet packet) {
        if (packet instanceof ClientboundSystemChatPacket chat) {
            return PLAIN.serialize(chat.getContent());
        }
        if (packet instanceof ClientboundPlayerChatPacket chat) {
            if (chat.getUnsignedContent() != null) {
                String unsigned = PLAIN.serialize(chat.getUnsignedContent());
                if (!unsigned.isBlank()) {
                    return unsigned;
                }
            }
            String content = chat.getContent();
            return content == null || content.isBlank() ? null : content;
        }
        if (packet instanceof ClientboundDisguisedChatPacket chat) {
            return PLAIN.serialize(chat.getMessage());
        }
        if (packet instanceof ClientboundSetTitleTextPacket title) {
            return PLAIN.serialize(title.getText());
        }
        if (packet instanceof ClientboundSetSubtitleTextPacket subtitle) {
            return PLAIN.serialize(subtitle.getText());
        }
        if (packet instanceof ClientboundSetActionBarTextPacket actionBar) {
            return PLAIN.serialize(actionBar.getText());
        }
        return null;
    }

    void handleAuthenticationDialog(NbtMap dialog) {
        AuthenticationUiChallenge challenge = parseAuthenticationUi(dialog);
        if (challenge != null) {
            listener.onAuthenticationUi(challenge);
            return;
        }
        AuthenticationUiType commandType = parseAuthenticationCommandUi(dialog);
        if (commandType != null) {
            listener.onAuthenticationCommandUi(commandType);
        }
        else {
            listener.onDiagnostic("Ignored a dialog without a recognized AuthMe/AuthMeUI submit action");
        }
    }

    static AuthenticationUiChallenge parseAuthenticationUi(NbtMap dialog) {
        AuthenticationActionScan scan = scanAuthenticationActions(dialog);
        if (scan.totalPrimaryActions() != 1 || scan.customActionIds().size() != 1) {
            return null;
        }
        String actionId = scan.customActionIds().getFirst();
        RecognizedAction action = AUTHENTICATION_ACTIONS.get(actionId);

        List<DialogInputField> inputs = scan.inputs();
        String passwordInput = findPasswordInput(inputs);
        SecondaryInput secondary = action.type() == AuthenticationUiType.REGISTER
            ? findSecondaryInput(inputs)
            : SecondaryInput.none();
        String agreementInput = action.type() == AuthenticationUiType.RULES
            ? findAgreementInput(inputs)
            : null;
        return new AuthenticationUiChallenge(
            action.type(),
            action.provider(),
            actionId,
            passwordInput,
            secondary.key(),
            secondary.purpose(),
            agreementInput
        );
    }

    static AuthenticationUiType parseAuthenticationCommandUi(NbtMap dialog) {
        AuthenticationActionScan scan = scanAuthenticationActions(dialog);
        if (scan.totalPrimaryActions() != 1 || scan.commandActions().size() != 1) {
            return null;
        }
        CommandActionCandidate candidate = scan.commandActions().getFirst();
        return candidate.inputsDeclared() ? candidate.type() : null;
    }

    static String nonSensitiveDialogText(NbtMap dialog) {
        List<String> text = new ArrayList<>();
        collectNonSensitiveText(dialog, null, text);
        return String.join("\n", new LinkedHashSet<>(text));
    }

    private static AuthenticationActionScan scanAuthenticationActions(NbtMap dialog) {
        DialogStructure structure = dialogStructure(dialog);
        if (structure == null) {
            return AuthenticationActionScan.empty();
        }
        List<DialogInputField> inputs = collectDialogInputs(structure.inputs());
        Set<String> declaredInputKeys = new LinkedHashSet<>();
        inputs.forEach(input -> declaredInputKeys.add(input.key()));

        List<String> customActionIds = new ArrayList<>();
        List<CommandActionCandidate> commandActions = new ArrayList<>();
        for (Object button : structure.actions()) {
            if (!(button instanceof Map<?, ?> buttonMap)
                || !(buttonMap.get("action") instanceof Map<?, ?> actionMap)) {
                continue;
            }
            String actionType = stringEntry(actionMap, "type");
            if (isCustomActionType(actionType)) {
                String actionId = stringEntry(actionMap, "id");
                if (actionId != null && AUTHENTICATION_ACTIONS.containsKey(actionId)) {
                    customActionIds.add(actionId);
                }
                continue;
            }
            if (isRunCommandActionType(actionType)) {
                String template = stringEntry(actionMap, "template");
                CommandTemplateSpec spec = template == null ? null : AUTHME_POST_JOIN_COMMANDS.get(template);
                if (spec != null) {
                    commandActions.add(new CommandActionCandidate(
                        spec.type(), declaredInputKeys.containsAll(spec.requiredInputs())
                    ));
                }
            }
        }
        return new AuthenticationActionScan(customActionIds, commandActions, inputs);
    }

    private static DialogStructure dialogStructure(NbtMap dialog) {
        if (dialog == null || !isMultiActionDialogType(stringEntry(dialog, "type"))) {
            return null;
        }
        Object actionValue = dialog.get("actions");
        if (!(actionValue instanceof Collection<?> actions)) {
            return null;
        }
        Object inputValue = dialog.get("inputs");
        if (inputValue == null) {
            return new DialogStructure(actions, List.of());
        }
        return inputValue instanceof Collection<?> inputs
            ? new DialogStructure(actions, inputs)
            : null;
    }

    private static boolean isMultiActionDialogType(String value) {
        return "multi_action".equals(value) || "minecraft:multi_action".equals(value);
    }

    private static boolean isCustomActionType(String value) {
        return "dynamic/custom".equals(value) || "minecraft:dynamic/custom".equals(value);
    }

    private static boolean isRunCommandActionType(String value) {
        return "dynamic/run_command".equals(value)
            || "minecraft:dynamic/run_command".equals(value);
    }

    private static List<DialogInputField> collectDialogInputs(Collection<?> values) {
        List<DialogInputField> target = new ArrayList<>();
        for (Object value : values) {
            if (value instanceof Map<?, ?> map) {
                DialogInputKind kind = inputKind(stringEntry(map, "type"));
                if (kind == DialogInputKind.UNKNOWN) {
                    continue;
                }
                String key = stringEntry(map, "key");
                if (isSafeInputKey(key)) {
                    target.add(new DialogInputField(kind, key, inputLabel(map)));
                }
            }
        }
        return target;
    }

    private static DialogInputKind inputKind(String type) {
        if (type == null) {
            return DialogInputKind.UNKNOWN;
        }
        return switch (type) {
            case "text", "minecraft:text" -> DialogInputKind.TEXT;
            case "boolean", "minecraft:boolean" -> DialogInputKind.BOOLEAN;
            default -> DialogInputKind.UNKNOWN;
        };
    }

    private static String findPasswordInput(List<DialogInputField> inputs) {
        DialogInputField best = null;
        int bestScore = 0;
        for (DialogInputField input : inputs) {
            if (input.kind() != DialogInputKind.TEXT) {
                continue;
            }
            int score = passwordScore(input.key());
            if (score > bestScore) {
                best = input;
                bestScore = score;
            }
        }
        return best == null ? null : best.key();
    }

    private static int passwordScore(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        if (lower.contains("confirm") || lower.contains("repeat") || lower.contains("email")
            || lower.contains("mail")) {
            return 0;
        }
        if (lower.equals("password")) {
            return 100;
        }
        if (lower.equals("pass") || lower.equals("pwd")) {
            return 90;
        }
        return lower.contains("password") || lower.contains("passwd") ? 50 : 0;
    }

    private static SecondaryInput findSecondaryInput(List<DialogInputField> inputs) {
        DialogInputField bestEmail = null;
        int bestEmailScore = 0;
        DialogInputField bestConfirmation = null;
        int bestConfirmationScore = 0;
        for (DialogInputField input : inputs) {
            if (input.kind() != DialogInputKind.TEXT || passwordScore(input.key()) > 0) {
                continue;
            }
            int emailScore = emailScore(input);
            if (emailScore > bestEmailScore) {
                bestEmail = input;
                bestEmailScore = emailScore;
            }
            int confirmationScore = confirmationScore(input);
            if (confirmationScore > bestConfirmationScore) {
                bestConfirmation = input;
                bestConfirmationScore = confirmationScore;
            }
        }
        if (bestEmail != null) {
            return new SecondaryInput(bestEmail.key(), AuthenticationUiInputPurpose.EMAIL);
        }
        if (bestConfirmation != null) {
            return new SecondaryInput(bestConfirmation.key(), AuthenticationUiInputPurpose.CONFIRMATION);
        }
        return SecondaryInput.none();
    }

    private static int emailScore(DialogInputField input) {
        String key = input.key().toLowerCase(Locale.ROOT);
        String label = input.label().toLowerCase(Locale.ROOT);
        int score = key.equals("email") || key.equals("e_mail") || key.equals("e-mail") ? 100 : 0;
        if (score == 0 && (key.contains("email") || key.contains("mail"))) {
            score = 80;
        }
        if (containsEmailHint(label)) {
            score += 60;
        }
        return score;
    }

    private static boolean containsEmailHint(String value) {
        return value.contains("email") || value.contains("e-mail") || value.contains("e mail")
            || value.contains("mail address") || value.contains("邮箱") || value.contains("郵箱")
            || value.contains("电子邮件") || value.contains("電子郵件") || value.contains("correo")
            || value.contains("courriel");
    }

    private static int confirmationScore(DialogInputField input) {
        String key = input.key().toLowerCase(Locale.ROOT);
        String label = input.label().toLowerCase(Locale.ROOT);
        if (key.equals("confirm") || key.equals("confirmation") || key.equals("password_confirmation")) {
            return 100;
        }
        if (key.contains("confirm") || key.contains("repeat")) {
            return 70;
        }
        return label.contains("confirm") || label.contains("repeat") || label.contains("again")
            || label.contains("确认") || label.contains("確認") || label.contains("重复")
            || label.contains("重複") ? 40 : 0;
    }

    private static String findAgreementInput(List<DialogInputField> inputs) {
        DialogInputField best = null;
        int bestScore = Integer.MIN_VALUE;
        for (DialogInputField input : inputs) {
            if (input.kind() != DialogInputKind.BOOLEAN) {
                continue;
            }
            String searchable = (input.key() + " " + input.label()).toLowerCase(Locale.ROOT);
            int score = searchable.contains("agree") || searchable.contains("accept")
                || searchable.contains("rule") || searchable.contains("同意") || searchable.contains("接受")
                ? 10 : 0;
            if (best == null || score > bestScore) {
                best = input;
                bestScore = score;
            }
        }
        return best == null ? null : best.key();
    }

    private static String inputLabel(Map<?, ?> map) {
        Object label = map.get("label");
        if (label == null) {
            return "";
        }
        List<String> values = new ArrayList<>();
        collectNonSensitiveText(label, "label", values);
        return String.join(" ", values);
    }

    private static String stringEntry(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof String text ? text : null;
    }

    private static boolean isSafeInputKey(String value) {
        return value != null && value.matches("[A-Za-z0-9_]{1,64}");
    }

    private static void collectNonSensitiveText(Object value, String parentKey, List<String> target) {
        if (value instanceof String text) {
            String trimmed = text.strip();
            if (!trimmed.isEmpty() && isReadableDialogText(parentKey, trimmed)) {
                target.add(trimmed);
            }
        }
        else if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key) || isSensitiveDialogBranch(key)) {
                    continue;
                }
                collectNonSensitiveText(entry.getValue(), key, target);
            }
        }
        else if (value instanceof Collection<?> collection) {
            collection.forEach(entry -> collectNonSensitiveText(entry, parentKey, target));
        }
    }

    private static boolean isSensitiveDialogBranch(String key) {
        String lower = key.toLowerCase(Locale.ROOT);
        return lower.equals("action") || lower.equals("click_event") || lower.equals("click-event")
            || lower.equals("on_click") || lower.equals("initial") || lower.equals("default")
            || lower.equals("value") || lower.equals("template");
    }

    private static boolean isReadableDialogText(String parentKey, String value) {
        String key = parentKey == null ? "" : parentKey.toLowerCase(Locale.ROOT);
        if (key.equals("type") || key.equals("key") || key.equals("id") || key.equals("name")) {
            return false;
        }
        return !value.startsWith("authme:") && !value.startsWith("authmeui:")
            && !value.startsWith("minecraft:") && !value.startsWith("dynamic/");
    }

    private record RecognizedAction(AuthenticationUiType type, AuthenticationUiProvider provider) {
    }

    private record CommandTemplateSpec(AuthenticationUiType type, Set<String> requiredInputs) {
        private CommandTemplateSpec {
            requiredInputs = Set.copyOf(requiredInputs);
        }
    }

    private record CommandActionCandidate(AuthenticationUiType type, boolean inputsDeclared) {
    }

    private record DialogStructure(Collection<?> actions, Collection<?> inputs) {
    }

    private record AuthenticationActionScan(
        List<String> customActionIds,
        List<CommandActionCandidate> commandActions,
        List<DialogInputField> inputs
    ) {
        private AuthenticationActionScan {
            customActionIds = List.copyOf(customActionIds);
            commandActions = List.copyOf(commandActions);
            inputs = List.copyOf(inputs);
        }

        private int totalPrimaryActions() {
            return customActionIds.size() + commandActions.size();
        }

        private static AuthenticationActionScan empty() {
            return new AuthenticationActionScan(List.of(), List.of(), List.of());
        }
    }

    private enum DialogInputKind {
        TEXT,
        BOOLEAN,
        UNKNOWN
    }

    private record DialogInputField(DialogInputKind kind, String key, String label) {
    }

    private record SecondaryInput(String key, AuthenticationUiInputPurpose purpose) {
        private static SecondaryInput none() {
            return new SecondaryInput(null, AuthenticationUiInputPurpose.NONE);
        }
    }

    private void changeState(TransportState replacement) {
        state.set(replacement);
        listener.onStateChanged(replacement);
    }

    private ServerboundClientInformationPacket clientInformation() {
        return new ServerboundClientInformationPacket(
            "en_us", config.renderDistance(), ChatVisibility.FULL, true,
            List.of(SkinPart.values()), HandPreference.RIGHT_HAND, false, true, ParticleStatus.MINIMAL
        );
    }

    private void handleResourcePack(Session source, ClientboundResourcePackPushPacket resourcePack) {
        UUID id = resourcePack.getId();
        listener.onResourcePackStatus("OFFER_RECEIVED id=" + id);
        if (!config.acceptResourcePacksWithoutDownload()) {
            source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.DECLINED));
            listener.onResourcePackStatus("DECLINED id=" + id);
            return;
        }
        source.send(new ServerboundResourcePackPacket(id, ResourcePackStatus.ACCEPTED));
        listener.onResourcePackStatus("ACCEPTED id=" + id);
        executor.schedule(() -> sendResourceStatus(source, id, ResourcePackStatus.DOWNLOADED),
            config.resourcePackStepDelayMillis(), TimeUnit.MILLISECONDS);
        executor.schedule(() -> sendResourceStatus(source, id, ResourcePackStatus.SUCCESSFULLY_LOADED),
            config.resourcePackStepDelayMillis() * 2, TimeUnit.MILLISECONDS);
    }

    private void sendResourceStatus(Session source, UUID id, ResourcePackStatus status) {
        if (source.isConnected()) {
            source.send(new ServerboundResourcePackPacket(id, status));
            listener.onResourcePackStatus(status + " id=" + id);
        }
    }

    private void acknowledgeTeleport(Session source, ClientboundPlayerPositionPacket packet) {
        synchronized (movementLock) {
            Vector3d position = packet.getPosition();
            List<PositionElement> relative = packet.getRelatives();
            x = relative.contains(PositionElement.X) ? x + position.getX() : position.getX();
            y = relative.contains(PositionElement.Y) ? y + position.getY() : position.getY();
            z = relative.contains(PositionElement.Z) ? z + position.getZ() : position.getZ();
            yaw = relative.contains(PositionElement.Y_ROT) ? yaw + packet.getYRot() : packet.getYRot();
            pitch = relative.contains(PositionElement.X_ROT) ? pitch + packet.getXRot() : packet.getXRot();
            positionKnown.set(true);
            source.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
            source.send(new ServerboundMovePlayerPosRotPacket(false, false, x, y, z, yaw, pitch));
        }
    }

    private void scheduleRespawn(Session source) {
        if (!respawnPending.compareAndSet(false, true)) {
            return;
        }
        executor.schedule(() -> {
            if (source.isConnected() && state.get() == TransportState.PLAY) {
                // The enum constant was renamed from RESPAWN to PERFORM_RESPAWN
                // in 26.1, but the wire value remains the first entry.
                source.send(new ServerboundClientCommandPacket(ClientCommand.values()[0]));
            }
            respawnPending.set(false);
        }, 1, TimeUnit.SECONDS);
    }

    private final class PacketListener extends SessionAdapter {
        @Override
        public void connected(ConnectedEvent event) {
            changeState(TransportState.LOGIN);
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            onPacket(session, packet);
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            session = null;
            listener.onDisconnected(PLAIN.serialize(event.getReason()), event.getCause());
        }
    }
}
