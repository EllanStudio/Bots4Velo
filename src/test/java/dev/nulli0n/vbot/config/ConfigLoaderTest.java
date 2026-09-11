package dev.nulli0n.vbot.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigLoaderTest {
    @Test
    void parsesMinimalBotAndAppliesDefaults() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
            """);

        assertThat(config.proxy().address()).isEqualTo("127.0.0.1");
        assertThat(config.proxy().protocol().automatic()).isTrue();
        assertThat(config.bots()).containsKey("farm01");
        assertThat(config.bots().get("farm01").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.AUTO);
        assertThat(config.bots().get("farm01").enabled()).isFalse();
        assertThat(config.bots().get("farm01").serverSwitchMaximumAttempts()).isZero();
        assertThat(config.bots().get("farm01").protocolDetectionServer()).isBlank();
        assertThat(config.runtime().maximumBots()).isEqualTo(32);
        assertThat(config.runtime().reconnect().stableResetSeconds()).isEqualTo(30);
        assertThat(config.runtime().prometheusPort()).isZero();
        assertThat(config.runtime().backendControl()).isEqualTo(
            BotPluginConfig.BackendControlConfig.disabled());
        assertThat(config.bots().get("farm01").auth().timeoutMillis()).isEqualTo(30_000L);
        assertThat(config.bots().get("farm01").auth().acceptRules()).isTrue();
        assertThat(config.bots().get("farm01").auth().registrationEmail()).isBlank();
        assertThat(config.bots().get("farm01").auth().registrationSecondArgument())
            .isEqualTo(BotPluginConfig.RegistrationSecondArgument.AUTO);
        assertThat(config.bots().get("farm01").auth().uiDetectionGraceMillis()).isEqualTo(3_000L);
        assertThat(config.bots().get("farm01").auth().successMessages()).anySatisfy(expression ->
            assertThat(java.util.regex.Pattern.compile(expression)
                .matcher("Logged-in due to Session Reconnection.").find()).isTrue());
        assertThat(config.bots().get("farm01").auth().successMessages()).anySatisfy(expression ->
            assertThat(java.util.regex.Pattern.compile(expression)
                .matcher("*** 已成功登录 ***").find()).isTrue());
        assertThat(config.bots().get("farm01").playerState()).isEqualTo(
            BotPluginConfig.PlayerStateConfig.unchanged());
    }

    @Test
    void preservesAnExplicitlyEmptyAuthenticationSuccessMessageList() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  success-messages: []
            """);

        assertThat(config.bots().get("farm01").auth().successMessages()).isEmpty();
    }

    @Test
    void rentalStyleExplicitSuccessMessagesRetainAuthMeSessionRestorationSignals() {
        BotPluginConfig config = parse("""
            bots:
              BOT_1:
                username: BOT_1
                password: secret
                auth:
                  success-messages:
                    - '(?i)custom success'
            """);

        BotPluginConfig.AuthConfig auth = config.bots().get("bot_1").auth();
        assertThat(auth.successMessages()).anySatisfy(expression ->
            assertThat(java.util.regex.Pattern.compile(expression)
                .matcher("Logged-in due to Session Reconnection.").find()).isTrue());
        assertThat(auth.loginPrompts()).anySatisfy(expression ->
            assertThat(java.util.regex.Pattern.compile(expression)
                .matcher("Please login with /login").find()).isTrue());
        assertThat(auth.registerPrompts()).anySatisfy(expression ->
            assertThat(java.util.regex.Pattern.compile(expression)
                .matcher("Please register").find()).isTrue());
    }

    @Test
    void resolvesBackendControlSecretFromEnvironmentBeforeLiteralValue() {
        BotPluginConfig config = parse("""
            runtime:
              backend-control:
                enabled: true
                secret: literal-fallback-at-least-32-bytes-long
                secret-env: BOTS4VELO_TEST_BACKEND_SECRET
                timeout-ms: 4500
            bots: {}
            """, Map.of("BOTS4VELO_TEST_BACKEND_SECRET", "environment-secret-at-least-32-bytes"));

        assertThat(config.runtime().backendControl()).satisfies(control -> {
            assertThat(control.enabled()).isTrue();
            assertThat(control.secret()).isEqualTo("environment-secret-at-least-32-bytes");
            assertThat(control.secretEnv()).isEqualTo("BOTS4VELO_TEST_BACKEND_SECRET");
            assertThat(control.timeoutMillis()).isEqualTo(4_500L);
        });

        BotPluginConfig fallback = parse("""
            runtime:
              backend-control:
                enabled: true
                secret: literal-fallback-at-least-32-bytes-long
                secret-env: BOTS4VELO_MISSING_BACKEND_SECRET
            bots: {}
            """, Map.of());
        assertThat(fallback.runtime().backendControl().secret())
            .isEqualTo("literal-fallback-at-least-32-bytes-long");
    }

    @Test
    void rejectsEnabledBackendControlWithoutSecretAndInvalidTimeout() {
        assertThatThrownBy(() -> parse("""
            runtime:
              backend-control:
                enabled: true
            bots: {}
            """, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("runtime.backend-control");

        assertThatThrownBy(() -> parse("""
            runtime:
              backend-control:
                enabled: true
                secret: too-short
            bots: {}
            """, Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("at least 32 bytes");

        assertThatThrownBy(() -> parse("""
            runtime:
              backend-control:
                timeout-ms: 100
            bots: {}
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeout-ms");
    }

    @Test
    void parsesAuthenticationTimeoutAndRejectsNegativeValue() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  timeout-ms: 12000
            """);

        assertThat(config.bots().get("farm01").auth().timeoutMillis()).isEqualTo(12_000L);
        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  timeout-ms: -1
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("timeout-ms");
    }

    @Test
    void parsesAuthMeUiConsentAndRegistrationEmail() {
        BotPluginConfig config = parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  mode: REGISTER
                  authmeui:
                    accept-rules: false
                    registration-email: bot@example.test
                    registration-second-argument: EMAIL_MANDATORY
                    ui-detection-grace-ms: 7500
            """);

        assertThat(config.bots().get("farm01").auth()).satisfies(auth -> {
            assertThat(auth.acceptRules()).isFalse();
            assertThat(auth.registrationEmail()).isEqualTo("bot@example.test");
            assertThat(auth.registrationSecondArgument())
                .isEqualTo(BotPluginConfig.RegistrationSecondArgument.EMAIL_MANDATORY);
            assertThat(auth.uiDetectionGraceMillis()).isEqualTo(7_500L);
        });
    }

    @Test
    void rejectsUnknownAuthMeUiRegistrationSecondArgument() {
        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  authmeui:
                    registration-second-argument: PASSWORD_AND_CAPTCHA
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("registration-second-argument");
    }

    @Test
    void validatesAuthMeUiDetectionGraceRange() {
        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                auth:
                  authmeui:
                    ui-detection-grace-ms: 60001
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ui-detection-grace-ms");
    }

    @Test
    void keepsPreAuthMeUiAuthConfigConstructorSourceCompatible() {
        BotPluginConfig.AuthConfig auth = new BotPluginConfig.AuthConfig(
            BotPluginConfig.AuthMode.AUTO, "login {password}", "register {password} {password}",
            1_000L, 2_500L, 1_500L, java.util.List.of(), java.util.List.of(),
            java.util.List.of(), java.util.List.of(), 30_000L);

        assertThat(auth.acceptRules()).isTrue();
        assertThat(auth.registrationEmail()).isBlank();
        assertThat(auth.registrationSecondArgument())
            .isEqualTo(BotPluginConfig.RegistrationSecondArgument.AUTO);
        assertThat(auth.uiDetectionGraceMillis()).isEqualTo(3_000L);
    }

    @Test
    void parsesPrometheusEndpointAndRejectsInvalidPort() {
        BotPluginConfig config = parse("""
            runtime:
              prometheus-address: 0.0.0.0
              prometheus-port: 9108
            bots: {}
            """);

        assertThat(config.runtime().prometheusAddress()).isEqualTo("0.0.0.0");
        assertThat(config.runtime().prometheusPort()).isEqualTo(9_108);
        assertThatThrownBy(() -> parse("""
            runtime:
              prometheus-port: 65536
            bots: {}
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("prometheus-port");
    }

    @Test
    void parsesReconnectStabilityAndValidatesItsRange() {
        BotPluginConfig disabled = parse("""
            runtime:
              reconnect:
                stable-reset-seconds: 0
            bots: {}
            """);
        BotPluginConfig maximum = parse("""
            runtime:
              reconnect:
                stable-reset-seconds: 86400
            bots: {}
            """);

        assertThat(disabled.runtime().reconnect().stableResetSeconds()).isZero();
        assertThat(maximum.runtime().reconnect().stableResetSeconds()).isEqualTo(86_400);
        assertThatThrownBy(() -> parse("""
            runtime:
              reconnect:
                stable-reset-seconds: 86401
            bots: {}
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stable-reset-seconds");
        assertThatThrownBy(() -> parse("""
            runtime:
              reconnect:
                stable-reset-seconds: -1
            bots: {}
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("stable-reset-seconds");
    }

    @Test
    void keepsLegacyReconnectConstructorSourceCompatible() {
        BotPluginConfig.ReconnectConfig reconnect = new BotPluginConfig.ReconnectConfig(
            1_000L, 60_000L, 2.0D, 0.15D, 0);

        assertThat(reconnect.stableResetSeconds()).isEqualTo(30);
    }

    @Test
    void acceptsManualProtocolVersion() {
        BotPluginConfig config = parse("""
            proxy:
              protocol-version: 1.16.5
            bots:
              Legacy:
                username: AFK_Legacy
                auth:
                  mode: NONE
            """);

        assertThat(config.proxy().protocol().automatic()).isFalse();
        assertThat(config.proxy().protocol().fixedVersion().protocolId()).isEqualTo(754);
    }

    @Test
    void rejectsInvalidOfflineUsername() {
        assertThatThrownBy(() -> parse("""
            bots:
              bad:
                username: name-with-dash
                password: secret
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("username");
    }

    @Test
    void allowsPasswordlessBotWhenAuthenticationIsDisabled() {
        BotPluginConfig config = parse("""
            bots:
              NoAuth:
                username: AFK_NoAuth
                auth:
                  mode: NONE
            """);

        assertThat(config.bots().get("noauth").auth().mode()).isEqualTo(BotPluginConfig.AuthMode.NONE);
    }

    @Test
    void rejectsTargetServerWithoutSwitchCommand() {
        assertThatThrownBy(() -> parse("""
            bots:
              Broken:
                username: AFK_Broken
                auth:
                  mode: NONE
                target-server: survival
                server-switch-command: ""
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("server-switch-command");
    }

    @Test
    void rejectsBotCountAboveConfiguredSafetyLimit() {
        assertThatThrownBy(() -> parse("""
            runtime:
              maximum-bots: 1
            bots:
              One:
                username: AFK_One
                auth:
                  mode: NONE
              Two:
                username: AFK_Two
                auth:
                  mode: NONE
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("maximum-bots");
    }

    @Test
    void rejectsBotIdsThatOnlyDifferByCase() {
        assertThatThrownBy(() -> parse("""
            bots:
              Farm:
                username: AFK_One
                auth:
                  mode: NONE
              farm:
                username: AFK_Two
                auth:
                  mode: NONE
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("ignoring case");
    }

    @Test
    void inheritsTemplatesAndLoadsPasswordFromSecretsFile(@TempDir Path directory) throws IOException {
        Files.writeString(directory.resolve("config.yml"), """
            templates:
              farm-auth:
                groups: [farm]
                tags: [backup, afk]
                protocol-version: 26.2
                display-name: "&eFarm Bot"
                tab-group: farm-bots
                auth:
                  mode: LOGIN
                  login-command: login {password}
                  authmeui:
                    accept-rules: true
                    registration-second-argument: EMAIL_OPTIONAL
                    ui-detection-grace-ms: 4200
            bots:
              Farm01:
                template: farm-auth
                username: AFK_Farm01
                password-secret: farm01
                auth:
                  authmeui:
                    accept-rules: false
            """);
        Files.writeString(directory.resolve("secrets.yml"), """
            passwords:
              farm01: secret-from-file
            """);

        BotPluginConfig config = ConfigLoader.load(directory, Map.of());
        BotPluginConfig.BotDefinition bot = config.bots().get("farm01");

        assertThat(bot.password()).isEqualTo("secret-from-file");
        assertThat(bot.groups()).containsExactly("farm");
        assertThat(bot.tags()).containsExactly("backup", "afk");
        assertThat(bot.protocolOverride().fixedVersion().protocolId()).isEqualTo(776);
        assertThat(bot.templateName()).isEqualTo("farm-auth");
        assertThat(bot.displayName()).isEqualTo("&eFarm Bot");
        assertThat(bot.tabGroup()).isEqualTo("farm-bots");
        assertThat(bot.auth().acceptRules()).isFalse();
        assertThat(bot.auth().registrationSecondArgument())
            .isEqualTo(BotPluginConfig.RegistrationSecondArgument.EMAIL_OPTIONAL);
        assertThat(bot.auth().uiDetectionGraceMillis()).isEqualTo(4_200L);
    }

    @Test
    void loadsLegacySecretAliasesThatPredateManagedCreateValidation(@TempDir Path directory) throws IOException {
        String longAlias = "legacy-" + "x".repeat(80);
        Files.writeString(directory.resolve("config.yml"), """
            bots:
              Slash:
                enabled: true
                username: AFK_Slash
                password-secret: "farm/one"
              Colon:
                enabled: true
                username: AFK_Colon
                password-secret: "team:bot"
              Unicode:
                enabled: true
                username: AFK_Unicode
                password-secret: "机器人"
              Long:
                enabled: true
                username: AFK_Long
                password-secret: "%s"
            """.formatted(longAlias));
        Files.writeString(directory.resolve("secrets.yml"), """
            passwords:
              "farm/one": slash-value
              "team:bot": colon-value
              "机器人": unicode-value
              "%s": long-value
            """.formatted(longAlias));

        BotPluginConfig config = ConfigLoader.load(directory, Map.of());

        assertThat(config.bots().get("slash").password()).isEqualTo("slash-value");
        assertThat(config.bots().get("colon").password()).isEqualTo("colon-value");
        assertThat(config.bots().get("unicode").password()).isEqualTo("unicode-value");
        assertThat(config.bots().get("long").password()).isEqualTo("long-value");
    }

    @Test
    void loadsLegacyEnvironmentReferenceWithoutApplyingCreateTokenRegex() {
        BotPluginConfig config = parse("""
            bots:
              LegacyEnv:
                enabled: true
                username: AFK_LegacyEnv
                password-env: "LEGACY-ENV/ONE"
            """, Map.of("LEGACY-ENV/ONE", "environment-value"));

        assertThat(config.bots().get("legacyenv").password()).isEqualTo("environment-value");
    }

    @Test
    void parsesBehaviorConfigurationFromTemplate() {
        BotPluginConfig config = parse("""
            templates:
              active-farm:
                behavior:
                  enabled: true
                  mode: FARM
                  interval-ms: 1000
                  movement-radius: 2.5
                  yaw-step: 30
                  random-yaw: true
                  jump: true
                  swing: true
                  sneak: true
                  path:
                    - {x: 10, y: 65, z: -10}
                  server-cycle: [lobby, survival]
                  server-cycle-every: 3
            bots:
              Farm01:
                template: active-farm
                username: AFK_Farm01
                password: secret
            """);

        BotPluginConfig.BehaviorConfig behavior = config.bots().get("farm01").behavior();
        assertThat(behavior.enabled()).isTrue();
        assertThat(behavior.mode()).isEqualTo(BotPluginConfig.BehaviorMode.FARM);
        assertThat(behavior.intervalMillis()).isEqualTo(1_000L);
        assertThat(behavior.movementRadius()).isEqualTo(2.5D);
        assertThat(behavior.yawStep()).isEqualTo(30.0F);
        assertThat(behavior.randomYaw()).isTrue();
        assertThat(behavior.jump()).isTrue();
        assertThat(behavior.swing()).isTrue();
        assertThat(behavior.sneak()).isTrue();
        assertThat(behavior.path()).containsExactly(new BotPluginConfig.BehaviorPoint(10, 65, -10));
        assertThat(behavior.serverCycle()).containsExactly("lobby", "survival");
        assertThat(behavior.serverCycleEvery()).isEqualTo(3);
    }

    @Test
    void inheritsAndOverridesPlayerStateConfiguration() {
        BotPluginConfig config = parse("""
            templates:
              protected-farm:
                player-state:
                  invulnerable: ENABLED
                  game-mode: SURVIVAL
                  apply-delay-ms: 1250
                  respawn-point:
                    mode: FIXED
                    world: world
                    x: 10.5
                    y: 64
                    z: -20.25
                    yaw: 90
            bots:
              Farm01:
                template: protected-farm
                username: AFK_Farm01
                password: secret
                player-state:
                  game-mode: CREATIVE
            """);

        BotPluginConfig.PlayerStateConfig state = config.bots().get("farm01").playerState();
        assertThat(state.invulnerability()).isEqualTo(BotPluginConfig.InvulnerabilityMode.ENABLED);
        assertThat(state.gameMode()).isEqualTo(BotPluginConfig.ManagedGameMode.CREATIVE);
        assertThat(state.applyDelayMillis()).isEqualTo(1_250L);
        assertThat(state.respawnPoint()).isEqualTo(new BotPluginConfig.RespawnPointConfig(
            BotPluginConfig.RespawnPointMode.FIXED, "world", 10.5D, 64.0D, -20.25D, 90.0F));
    }

    @Test
    void expandsAfkPresetsAfterTemplateMergingAndHonorsExplicitOverrides() {
        BotPluginConfig config = parse("""
            templates:
              safe-afk:
                player-state:
                  afk-preset: SAFE
                  game-mode: ADVENTURE
            bots:
              Safe01:
                template: safe-afk
                username: AFK_Safe01
                password: secret
                player-state:
                  invulnerable: DISABLED
                  pickup-items: ENABLED
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  afk-preset: FARM
              Normal01:
                username: AFK_Normal01
                password: secret
                player-state:
                  afk-preset: NORMAL
            """);

        BotPluginConfig.PlayerStateConfig safe = config.bots().get("safe01").playerState();
        assertThat(safe.afkPreset()).isEqualTo(BotPluginConfig.AfkPreset.SAFE);
        assertThat(safe.invulnerability()).isEqualTo(BotPluginConfig.InvulnerabilityMode.DISABLED);
        assertThat(safe.sleepingIgnored()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(safe.affectsSpawning()).isEqualTo(BotPluginConfig.ManagedFlag.KEEP);
        assertThat(safe.pickupItems()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(safe.collidable()).isEqualTo(BotPluginConfig.ManagedFlag.DISABLED);
        assertThat(safe.gameMode()).isEqualTo(BotPluginConfig.ManagedGameMode.ADVENTURE);

        BotPluginConfig.PlayerStateConfig farm = config.bots().get("farm01").playerState();
        assertThat(farm.afkPreset()).isEqualTo(BotPluginConfig.AfkPreset.FARM);
        assertThat(farm.invulnerability()).isEqualTo(BotPluginConfig.InvulnerabilityMode.ENABLED);
        assertThat(farm.sleepingIgnored()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(farm.affectsSpawning()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(farm.pickupItems()).isEqualTo(BotPluginConfig.ManagedFlag.DISABLED);
        assertThat(farm.collidable()).isEqualTo(BotPluginConfig.ManagedFlag.DISABLED);
        assertThat(farm.gameMode()).isEqualTo(BotPluginConfig.ManagedGameMode.KEEP);

        BotPluginConfig.PlayerStateConfig normal = config.bots().get("normal01").playerState();
        assertThat(normal.afkPreset()).isEqualTo(BotPluginConfig.AfkPreset.NORMAL);
        assertThat(normal.invulnerability()).isEqualTo(BotPluginConfig.InvulnerabilityMode.DISABLED);
        assertThat(normal.sleepingIgnored()).isEqualTo(BotPluginConfig.ManagedFlag.DISABLED);
        assertThat(normal.affectsSpawning()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(normal.pickupItems()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
        assertThat(normal.collidable()).isEqualTo(BotPluginConfig.ManagedFlag.ENABLED);
    }

    @Test
    void bundledConfigStartsWithoutStaticBots() throws IOException {
        String bundled;
        try (InputStream stream = ConfigLoaderTest.class.getClassLoader().getResourceAsStream("config.yml")) {
            assertThat(stream).isNotNull();
            bundled = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        BotPluginConfig config = parse(bundled);

        assertThat(config.bots()).isEmpty();
        assertThat(config.runtime().maximumBots()).isEqualTo(32);
        assertThat(config.runtime().backendControl().enabled()).isFalse();
    }

    @Test
    void keepsOriginalPlayerStateConstructorSourceCompatible() {
        BotPluginConfig.PlayerStateConfig state = new BotPluginConfig.PlayerStateConfig(
            BotPluginConfig.InvulnerabilityMode.ENABLED,
            BotPluginConfig.ManagedGameMode.SURVIVAL,
            250L,
            BotPluginConfig.RespawnPointConfig.unchanged());

        assertThat(state.afkPreset()).isEqualTo(BotPluginConfig.AfkPreset.NONE);
        assertThat(state.sleepingIgnored()).isEqualTo(BotPluginConfig.ManagedFlag.KEEP);
        assertThat(state.affectsSpawning()).isEqualTo(BotPluginConfig.ManagedFlag.KEEP);
        assertThat(state.pickupItems()).isEqualTo(BotPluginConfig.ManagedFlag.KEEP);
        assertThat(state.collidable()).isEqualTo(BotPluginConfig.ManagedFlag.KEEP);
    }

    @Test
    void validatesPlayerStateEnumsAndFixedRespawnPoint() {
        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  game-mode: BUILDER
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("player-state.game-mode");

        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  afk-preset: TURBO
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("player-state.afk-preset");

        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  pickup-items: SOMETIMES
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("player-state.pickup-items");

        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  respawn-point:
                    mode: FIXED
                    x: 10
                    y: 64
                    z: 10
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("respawn-point.world");

        assertThatThrownBy(() -> parse("""
            bots:
              Farm01:
                username: AFK_Farm01
                password: secret
                player-state:
                  respawn-point:
                    mode: FIXED
                    world: world
                    x: NaN
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("x must be between");
    }

    @Test
    void rejectsDuplicateBotUsernamesIgnoringCase() {
        assertThatThrownBy(() -> parse("""
            bots:
              First:
                username: AFK_Shared
                password: secret
              Second:
                username: afk_shared
                password: secret
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("same username ignoring case");
    }

    @Test
    void rejectsAmbiguousPasswordSources() {
        assertThatThrownBy(() -> parse("""
            bots:
              ambiguous:
                username: AFK_Ambiguous
                password: secret
                password-env: BOTS4VELO_PASSWORD
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("only one of password");
    }

    @Test
    void parsesRecurringOperationalSchedules() {
        BotPluginConfig config = parse("""
            runtime:
              schedules:
                - id: restart-farm
                  action: reconnect
                  selector: "@group:farm"
                  initial-delay-ms: 500
                  interval-ms: 60000
                - id: lobby-rotation
                  action: server
                  selector: "@tag:lobby"
                  server: survival
                  interval-ms: 120000
                - id: daily-shutdown
                  action: stop
                  selector: "@group:farm"
                  at: "03:30"
                  timezone: "Asia/Singapore"
              presence-rules:
                - id: keep-lobby-warm
                  server: lobby
                  selector: "@group:lobby"
                  minimum-bots: 1
                  maximum-humans: 0
                  interval-ms: 30000
            bots:
              Farm:
                username: AFK_Farm
                password: secret
            """);

        assertThat(config.runtime().schedules()).hasSize(3);
        assertThat(config.runtime().schedules().getFirst().selector()).isEqualTo("@group:farm");
        assertThat(config.runtime().schedules().get(1).server()).isEqualTo("survival");
        assertThat(config.runtime().schedules().get(2)).satisfies(schedule -> {
            assertThat(schedule.runsDailyAtConfiguredTime()).isTrue();
            assertThat(schedule.at()).isEqualTo("03:30");
            assertThat(schedule.timezone()).isEqualTo("Asia/Singapore");
        });
        assertThat(config.runtime().presenceRules()).singleElement().satisfies(rule -> {
            assertThat(rule.server()).isEqualTo("lobby");
            assertThat(rule.minimumBots()).isOne();
        });
    }

    @Test
    void rejectsInvalidDailyScheduleTimeAndTimezone() {
        assertThatThrownBy(() -> parse("""
            runtime:
              schedules:
                - id: invalid-time
                  action: start
                  selector: all
                  at: "3pm"
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid-time.at must use HH:mm");

        assertThatThrownBy(() -> parse("""
            runtime:
              schedules:
                - id: invalid-zone
                  action: start
                  selector: all
                  at: "03:00"
                  timezone: "Moon/Base"
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("invalid-zone.timezone is invalid");

        assertThatThrownBy(() -> parse("""
            runtime:
              schedules:
                - id: midnight-overflow
                  action: start
                  selector: all
                  at: "24:00"
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("midnight-overflow.at must use HH:mm");
    }

    private static BotPluginConfig parse(String yaml) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)));
    }

    private static BotPluginConfig parse(String yaml, Map<String, String> environment) {
        return ConfigLoader.parse(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), environment);
    }
}
