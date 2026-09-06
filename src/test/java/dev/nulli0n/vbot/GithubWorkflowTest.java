package dev.nulli0n.vbot;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GithubWorkflowTest {
    @Test
    void releaseDefaultsArePreparedForVersion303() throws Exception {
        String build = Files.readString(Path.of("build.gradle.kts")).replace("\r\n", "\n");
        String verifier = Files.readString(Path.of("scripts/verify-local-integration.ps1")).replace("\r\n", "\n");
        String integration = Files.readString(Path.of("scripts/ci/run-network-integration.sh")).replace("\r\n", "\n");
        String readme = Files.readString(Path.of("README.md")).replace("\r\n", "\n");
        String chineseReadme = Files.readString(Path.of("README.zh-CN.md")).replace("\r\n", "\n");

        assertThat(build).contains("orElse(\"3.0.3\")");
        assertThat(build)
            .contains("\":transport-api:check\"")
            .contains("\":backend-protocol:check\"")
            .contains("\":paper-companion:check\"")
            .contains("\":adapters:legacy-1_16_5:check\"")
            .contains("\":adapters:modern-1_21_11:check\"")
            .contains("\":adapters:modern-26_1:check\"")
            .contains("\":adapters:modern-26_2:check\"");
        assertThat(verifier).contains("$ExpectedPluginVersion = \"3.0.3\"");
        assertThat(integration).contains("bots4velo-integration/3.0.3");
        assertThat(readme)
            .contains("[中文文档 / Chinese documentation](README.zh-CN.md)")
            .contains("`bots4velo.jar`")
            .contains("`bots4velo-paper.jar`")
            .contains("git tag vX.0.0")
            .contains("/vbot create <id> <username> <secret:name|env:NAME|-> [target-server|-]")
            .contains("templates: {}")
            .contains("bots: {}")
            .contains("/vbot create Farm01 AFK_Farm01 secret:farm01 survival")
            .contains("/vbot create Observer AFK_Observer - lobby")
            .contains("bots4velo.create")
            .contains("Unknown protocol detection backend: afk")
            .contains("AuthMeUI 1.3.4")
            .contains("/vbot reload --check")
            .contains("git push origin vX.0.0");
        assertThat(chineseReadme).contains("[English documentation](README.md)");
    }

    @Test
    void majorReleaseWorkflowIsValidYamlAndKeepsReleaseGuards() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/build-major-release.yml")).replace("\r\n", "\n");
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(workflow);

        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(workflow)
            .contains("- \"v[0-9]+.0.0\"")
            .contains("permissions:\n  contents: write")
            .contains("java-version: \"21\"")
            .contains("run: chmod +x gradlew")
            .contains("quality-gate:\n    uses: ./.github/workflows/build.yml")
            .contains("needs: quality-gate")
            .contains("run: ./gradlew clean test shadowJar -PpluginVersion=\"${GITHUB_REF_NAME#v}\"")
            .contains("run: ./gradlew verifyShadowJar -PpluginVersion=\"${GITHUB_REF_NAME#v}\"")
            .contains("run: ./gradlew writeArtifactChecksum -PpluginVersion=\"${GITHUB_REF_NAME#v}\"")
            .contains("path: build/libs/*")
            .contains("GH_TOKEN: ${{ github.token }}")
            .contains("gh release create \"${GITHUB_REF_NAME}\" build/libs/*.jar --title \"${GITHUB_REF_NAME}\" --verify-tag --generate-notes")
            .contains("gh release upload \"${GITHUB_REF_NAME}\" build/libs/*.sha256 --clobber");
    }

    @Test
    void normalBuildWorkflowValidatesCommitsAndPullRequests() throws Exception {
        String workflow = Files.readString(Path.of(".github/workflows/build.yml")).replace("\r\n", "\n");
        Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(workflow);

        assertThat(parsed).isInstanceOf(Map.class);
        assertThat(workflow)
            .contains("pull_request:")
            .contains("workflow_call:")
            .contains("branches:")
            .contains("java-version: \"21\"")
            .contains("run: chmod +x gradlew")
            .contains("run: ./gradlew clean check shadowJar writeArtifactChecksum")
            .contains("protocol-contract:")
            .contains("protocol: [\"1.16.5\", \"1.21.11\", \"26.1.2\", \"26.2\"]")
            .contains("run: ./gradlew :test --tests dev.nulli0n.vbot.protocol.ProtocolVersionTest "
                + "-PciProtocol=\"${{ matrix.protocol }}\"")
            .contains("integration-network:")
            .contains("minecraft: \"1.16.5\"")
            .contains("server-java: \"16\"")
            .contains("minecraft: \"1.21.11\"")
            .contains("minecraft: \"26.1.2\"")
            .contains("minecraft: \"26.2\"")
            .contains("VELOCITY_JAVA=\"${JAVA_HOME_21_X64}/bin/java\"")
            .contains("scripts/ci/run-network-integration.sh");
    }

    @Test
    void integrationScriptUsesAValidVelocityAndPresenceConfiguration() throws Exception {
        String script = Files.readString(Path.of("scripts/ci/run-network-integration.sh")).replace("\r\n", "\n");

        assertThat(script)
            .contains("[forced-hosts]")
            .contains("VELOCITY_JAVA")
            .contains("VELOCITY_PLUGIN_JAR")
            .contains("PAPER_COMPANION_JAR")
            .contains("bots4velo-[0-9]*.jar")
            .contains("bots4velo-paper-*.jar")
            .contains("Expected exactly one Velocity plugin JAR")
            .contains("Expected exactly one Paper companion JAR")
            .contains("compgen -G \"$VELOCITY_PLUGIN_JAR\"")
            .contains("compgen -G \"$PAPER_COMPANION_JAR\"")
            .contains("plugins/Bots4VeloPaper/config.yml")
            .contains("shared-secret: \"$BACKEND_SHARED_SECRET\"")
            .contains("Listening for authenticated Bots4Velo policies on bots4velo:control")
            .contains("backend-control:")
            .contains("player-state:")
            .contains("afk-preset: \"FARM\"")
            .contains("invulnerable: \"ENABLED\"")
            .contains("Paper backend control APPLY_POLICY_EXT for bot IntegrationBot on afk: OK")
            .contains("--retry-all-errors")
            .contains("ARTIFACT %s | bytes=%s | sha256=%s | path=%s")
            .contains("RESOLVED %s | url=%s")
            .contains("artifact-manifest.log")
            .contains("download_authme '5.6.0' '-legacy.jar'")
            .contains("AUTHMEUI_VERSION=\"1.3.4\"")
            .contains("PYTHON=\"${PYTHON:-python3}\"")
            .contains("AUTHMEUI_JAR")
            .contains("https://cdn.modrinth.com/data/xwRjZuDG/versions/8tYeXZL1/AuthMeUI-1.3.4.jar")
            .contains("562aff394d756326850cb2fc7ef1a2bcc1082c22310bc5a66ce06ae926fa0475")
            .contains("AuthMeUI $AUTHMEUI_VERSION SHA-256 mismatch")
            .contains("$WORK_ROOT/lobby/plugins/AuthMeUI.jar")
            .contains("plugins/AuthMeUI/config.yml")
            .contains("write_authmeui_config true false")
            .contains("write_authmeui_config false false")
            .contains("write_authmeui_config true true")
            .contains("use-configuration-phase: $use_configuration_phase")
            .contains("configuration-phase-respect-authme-sessions: $respect_authme_sessions")
            .contains("configuration-phase-fastlogin-compatibility: false")
            .contains("rules-dialog:")
            .contains("checkbox-key: \"ci_rules_accepted\"")
            .contains("disable_authme_dialogs")
            .contains("preJoin:\n        enable: false")
            .contains("postJoin:\n        enable: false")
            .contains("if [[ \"$MINECRAFT_VERSION\" != \"1.16.5\" ]]")
            .contains("AUTHME_UI RULES")
            .contains("AUTHME_UI REGISTER")
            .contains("AUTHME_UI LOGIN")
            .contains("Mode: Configuration Phase \\(pre-join authentication\\)")
            .contains("Mode: In-Game \\(post-join authentication\\)")
            .contains("Bot IntegrationBot submitted AUTHME_UI LOGIN")
            .contains("Bot IntegrationBot matched an authentication success message")
            .contains("post_join_play_line")
            .contains("post_join_submit_line")
            .contains("post_join_success_line")
            .contains("RulesDeclined stopped at AUTHME_UI RULES")
            .contains("accept-rules: false")
            .contains("Bot IntegrationBot submitting its registration command")
            .contains("INTEGRATION_LOGIN_COMMAND='login {password}'")
            .contains("INTEGRATION_REGISTER_COMMAND='register {password} {password}'")
            .contains("login-command: \"$INTEGRATION_LOGIN_COMMAND\"")
            .contains("fallback-register-delay-ms: 1500")
            .contains("after-auth-delay-ms: 1500")
            .contains("ui-detection-grace-ms: 3000")
            .contains("Modern AuthMeUI pre-join fixture unexpectedly used chat-command authentication")
            .contains("Modern AuthMeUI post-join fixture unexpectedly used chat-command authentication")
            .contains("settings:\n  sessions:\n    enabled: $sessions_enabled")
            .contains("restart_lobby_with_authmeui_pre_join_session")
            .contains("honor an AuthMe session during AuthMeUI pre-join")
            .contains("session_afk_log_lines")
            .contains("AuthMeUI session-respect fixture unexpectedly submitted credentials")
            .contains("AUTHMEUI_FAILURE_FIXTURE_SELECTOR")
            .contains("reset_authme_fixture_state")
            .contains("\"$PYTHON\" -m zipfile -t \"$shared_mojang_cache\"")
            .contains("reset_authme_fixture_state\nshopt -s nullglob")
            .contains("authme.db authme.db-journal authme.db-wal authme.db-shm")
            .contains("Refusing to reset AuthMe fixture state outside WORK_ROOT")
            .contains("refusing to reset a possibly active AuthMe fixture")
            .contains("rm -f -- \"$authme_directory_real/$database_file\"")
            .doesNotContain("rm -rf")
            .contains("successfully registered")
            .contains("logged-in due to session reconnection")
            .contains("start-rules-declined-fixture")
            .contains("initial-delay-ms: 15000")
            .contains("login-delay-ms: 5000")
            .contains("presence-rules:")
            .contains("interval-ms: 1000")
            .contains("-> afk has connected")
            .contains("wait_for_new_log")
            .contains("stop_tracked_process")
            .doesNotContain("$WORK_ROOT/afk/plugins/AuthMeUI")
            .doesNotContain("INTEGRATION_LOGIN_COMMAND=\"b4vnoop\"");
    }

    @Test
    void integrationScriptHasValidBashSyntax() throws Exception {
        Path script = Path.of("scripts/ci/run-network-integration.sh").toAbsolutePath();
        Process process = new ProcessBuilder(findBash(), "-n", script.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(process.waitFor()).as("bash -n output: %s", output).isZero();
    }

    private static String findBash() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return "bash";
        }
        List<Path> candidates = new ArrayList<>();
        addGitBashCandidate(candidates, System.getenv("ProgramFiles"));
        addGitBashCandidate(candidates, System.getenv("LOCALAPPDATA") == null
            ? null
            : Path.of(System.getenv("LOCALAPPDATA"), "Programs").toString());
        return candidates.stream()
            .filter(Files::isExecutable)
            .map(Path::toString)
            .findFirst()
            .orElse("bash");
    }

    private static void addGitBashCandidate(List<Path> candidates, String parent) {
        if (parent != null && !parent.isBlank()) {
            candidates.add(Path.of(parent, "Git", "bin", "bash.exe"));
        }
    }
}
