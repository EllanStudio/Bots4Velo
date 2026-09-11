package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.config.BotPluginConfig.AuthMode;
import dev.nulli0n.vbot.config.BotPluginConfig.RegistrationSecondArgument;
import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.transport.AuthenticationUiChallenge;
import dev.nulli0n.vbot.transport.AuthenticationUiInputPurpose;
import dev.nulli0n.vbot.transport.AuthenticationUiProvider;
import dev.nulli0n.vbot.transport.AuthenticationUiType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticationUiFlowTest {
    @Test
    void acceptsRulesThenRegistrationAsSeparateAuthMeUiActions() {
        AuthenticationUiFlow flow = new AuthenticationUiFlow();

        AuthenticationUiFlow.Presentation rules = flow.present(
            rules("accept-rules"), AuthMode.AUTO, true, "", false);
        assertThat(rules.decision()).isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);
        assertThat(rules.stage()).isEqualTo("AUTHME_UI RULES sequence=1");
        flow.markSubmitted(rules, false);

        AuthenticationUiFlow.Presentation register = flow.present(
            register("create-account", AuthenticationUiInputPurpose.CONFIRMATION),
            AuthMode.AUTO, true, "", false);
        assertThat(register.decision()).isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);
        flow.markSubmitted(register, false);

        assertThat(flow.active()).isTrue();
        assertThat(flow.submittedActionCount()).isEqualTo(2);
        assertThat(flow.credentialReadyOnPlay()).isTrue();
    }

    @Test
    void rejectsRepeatedCredentialActionAsServerRejection() {
        AuthenticationUiFlow flow = new AuthenticationUiFlow();
        AuthenticationUiChallenge challenge = login("login");
        AuthenticationUiFlow.Presentation first = flow.present(challenge, AuthMode.LOGIN, true, "", true);
        flow.markSubmitted(first, true);

        AuthenticationUiFlow.Presentation repeated = flow.present(challenge, AuthMode.LOGIN, true, "", true);

        assertThat(repeated.decision()).isEqualTo(AuthenticationUiFlow.Decision.REJECT);
        assertThat(repeated.reason()).contains("rejected");
    }

    @Test
    void repeatedRulesAfterRegistrationDoNotInvalidatePreJoinCredentialEvidence() {
        AuthenticationUiFlow flow = new AuthenticationUiFlow();
        AuthenticationUiFlow.Presentation rules = flow.present(
            rules("rules"), AuthMode.AUTO, true, "", false);
        flow.markSubmitted(rules, false);
        AuthenticationUiFlow.Presentation registration = flow.present(
            register("register", AuthenticationUiInputPurpose.CONFIRMATION),
            AuthMode.AUTO, true, "", false);
        flow.markSubmitted(registration, false);

        AuthenticationUiFlow.Presentation repeatedRules = flow.present(
            rules("rules"), AuthMode.AUTO, true, "", false);

        assertThat(repeatedRules.decision()).isEqualTo(AuthenticationUiFlow.Decision.IGNORE);
        assertThat(flow.credentialReadyOnPlay()).isTrue();
    }

    @Test
    void restrictsRulesToRegistrationModesAndExplicitConsent() {
        AuthenticationUiFlow loginFlow = new AuthenticationUiFlow();
        assertThat(loginFlow.present(rules("rules"), AuthMode.LOGIN, true, "", false).reason())
            .contains("incompatible");

        AuthenticationUiFlow optedOutFlow = new AuthenticationUiFlow();
        assertThat(optedOutFlow.present(rules("rules"), AuthMode.REGISTER, false, "", false).reason())
            .contains("accept-rules");

        AuthenticationUiFlow agreementlessFlow = new AuthenticationUiFlow();
        AuthenticationUiChallenge agreementless = new AuthenticationUiChallenge(
            AuthenticationUiType.RULES, AuthenticationUiProvider.AUTHME_UI, "rules", null, null,
            AuthenticationUiInputPurpose.NONE, null);
        assertThat(agreementlessFlow.present(
            agreementless, AuthMode.REGISTER, true, "", false).decision())
            .isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);
    }

    @Test
    void appliesExplicitAuthMeUiEmailSemanticsWithoutGuessingMandatoryEmail() {
        AuthenticationUiChallenge emailRegistration = register(
            "register-with-email", AuthenticationUiInputPurpose.EMAIL);

        AuthenticationUiFlow autoOptional = new AuthenticationUiFlow();
        assertThat(autoOptional.present(emailRegistration, AuthMode.REGISTER, true, "",
            RegistrationSecondArgument.AUTO, true).decision())
            .isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);

        AuthenticationUiFlow mandatoryEmail = new AuthenticationUiFlow();
        assertThat(mandatoryEmail.present(emailRegistration, AuthMode.REGISTER, true, "",
            RegistrationSecondArgument.EMAIL_MANDATORY, true).reason())
            .contains("mandatory");

        AuthenticationUiFlow configuredEmail = new AuthenticationUiFlow();
        assertThat(configuredEmail.present(
            emailRegistration, AuthMode.REGISTER, true, "bot@example.test",
            RegistrationSecondArgument.EMAIL_MANDATORY, true).decision())
            .isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);

        AuthenticationUiFlow preJoinOptionalWithoutEmail = new AuthenticationUiFlow();
        assertThat(preJoinOptionalWithoutEmail.present(
            emailRegistration, AuthMode.REGISTER, true, "",
            RegistrationSecondArgument.EMAIL_OPTIONAL, false).decision())
            .isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);

        AuthenticationUiFlow preJoinWithEmail = new AuthenticationUiFlow();
        assertThat(preJoinWithEmail.present(
            emailRegistration, AuthMode.REGISTER, true, "bot@example.test",
            RegistrationSecondArgument.EMAIL_OPTIONAL, false).reason())
            .contains("before entering PLAY");
    }

    @Test
    void postJoinCredentialNeverBecomesPreJoinSuccessEvidence() {
        AuthenticationUiFlow flow = new AuthenticationUiFlow();
        AuthenticationUiFlow.Presentation login = flow.present(login("login"), AuthMode.AUTO, true, "", true);
        flow.markSubmitted(login, true);

        assertThat(flow.credentialReadyOnPlay()).isFalse();
    }

    @Test
    void explicitSecondArgumentNormalizationOnlyChangesAuthMeUiRegistration() {
        AuthenticationUiChallenge authMeUi = register(
            "register", AuthenticationUiInputPurpose.CONFIRMATION);
        AuthenticationUiChallenge normalized = BotSession.normalizeAuthenticationUiChallenge(
            authMeUi, RegistrationSecondArgument.EMAIL_OPTIONAL);
        assertThat(normalized.secondaryInputPurpose()).isEqualTo(AuthenticationUiInputPurpose.EMAIL);

        AuthenticationUiChallenge builtInAuthMe = new AuthenticationUiChallenge(
            AuthenticationUiType.REGISTER, AuthenticationUiProvider.AUTHME,
            "authme:prejoin-register/submit", "password", "confirm",
            AuthenticationUiInputPurpose.CONFIRMATION, null);
        assertThat(BotSession.normalizeAuthenticationUiChallenge(
            builtInAuthMe, RegistrationSecondArgument.EMAIL_MANDATORY)).isSameAs(builtInAuthMe);

        AuthenticationUiChallenge builtInAuthMeEmail = new AuthenticationUiChallenge(
            AuthenticationUiType.REGISTER, AuthenticationUiProvider.AUTHME,
            "authme:prejoin-register/submit", "password", "email",
            AuthenticationUiInputPurpose.EMAIL, null);
        AuthenticationUiFlow builtInFlow = new AuthenticationUiFlow();
        assertThat(builtInFlow.present(
            builtInAuthMeEmail, AuthMode.REGISTER, true, "bot@example.test",
            RegistrationSecondArgument.EMAIL_MANDATORY, false).decision())
            .isEqualTo(AuthenticationUiFlow.Decision.SUBMIT);
    }

    @Test
    void modernProtocolsHonorUiGraceWhileLegacyKeepsItsOriginalDelay() {
        assertThat(BotSession.initialAuthenticationDelayMillis(
            ProtocolVersion.MINECRAFT_26_2, 1_000L, 3_000L)).isEqualTo(3_000L);
        assertThat(BotSession.initialAuthenticationDelayMillis(
            ProtocolVersion.MINECRAFT_26_2, 5_000L, 3_000L)).isEqualTo(5_000L);
        assertThat(BotSession.initialAuthenticationDelayMillis(
            ProtocolVersion.MINECRAFT_1_16_5, 1_000L, 3_000L)).isEqualTo(1_000L);

        Instant firstPlay = Instant.parse("2026-07-29T00:00:00Z");
        assertThat(BotSession.withinUiDetectionGrace(
            ProtocolVersion.MINECRAFT_26_2, 3_000L, firstPlay, firstPlay.plusMillis(2_999L))).isTrue();
        assertThat(BotSession.withinUiDetectionGrace(
            ProtocolVersion.MINECRAFT_26_2, 3_000L, firstPlay, firstPlay.plusMillis(3_000L))).isFalse();
        assertThat(BotSession.withinUiDetectionGrace(
            ProtocolVersion.MINECRAFT_1_16_5, 3_000L, firstPlay, firstPlay.plusMillis(1L))).isFalse();
    }

    @Test
    void uiPresenceBlocksChatFallbackAndNoneIgnoresStructuredUi() {
        assertThat(BotSession.shouldRunChatAuthentication(true, false, false)).isTrue();
        assertThat(BotSession.shouldRunChatAuthentication(true, false, true)).isFalse();
        assertThat(BotSession.authenticationTypeExpected(AuthMode.NONE, AuthenticationUiType.LOGIN)).isFalse();
        assertThat(BotSession.authenticationTypeExpected(AuthMode.LOGIN, AuthenticationUiType.LOGIN)).isTrue();
        assertThat(BotSession.authenticationTypeExpected(AuthMode.LOGIN, AuthenticationUiType.REGISTER)).isFalse();
        assertThat(BotSession.authenticationTypeExpected(AuthMode.AUTO, AuthenticationUiType.REGISTER)).isTrue();
        assertThat(BotSession.ignoresAuthenticationUi(AuthMode.NONE)).isTrue();
        assertThat(BotSession.ignoredAuthenticationUiStage("AUTHME_UI LOGIN"))
            .isEqualTo("AUTHME_UI LOGIN ignored mode=NONE");
    }

    @Test
    void preservesConfigurationPhaseSessionSuccessUntilFirstPlayAndClearsItOnReset() {
        BotSession.AuthenticationOutcomeGate outcome = new BotSession.AuthenticationOutcomeGate();

        assertThat(outcome.succeedBeforePlay()).isTrue();
        assertThat(outcome.succeeded()).isTrue();
        assertThat(outcome.consumePrePlaySuccess()).isTrue();
        assertThat(outcome.consumePrePlaySuccess()).isFalse();

        outcome.reset();
        assertThat(outcome.pending()).isTrue();
        assertThat(outcome.consumePrePlaySuccess()).isFalse();
    }

    @Test
    void authenticationSuccessAndFailureHaveOneAtomicWinner() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            BotSession.AuthenticationOutcomeGate outcome = new BotSession.AuthenticationOutcomeGate();
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                Future<Boolean> success = executor.submit(() -> {
                    start.await();
                    return outcome.succeed();
                });
                Future<Boolean> failure = executor.submit(() -> {
                    start.await();
                    return outcome.fail();
                });
                start.countDown();

                assertThat(success.get() ^ failure.get()).isTrue();
                assertThat(outcome.outcome()).isIn(
                    BotSession.AuthenticationOutcome.SUCCESS,
                    BotSession.AuthenticationOutcome.FAILURE);
            }
        }
    }

    @Test
    void commandUiTrackerAllowsOneSubmissionPerCredentialTypeAndResets() {
        BotSession.AuthenticationCommandUiTracker tracker =
            new BotSession.AuthenticationCommandUiTracker();

        tracker.recordSubmission(AuthenticationUiType.LOGIN, false);
        assertThat(tracker.wasSubmitted(AuthenticationUiType.LOGIN)).isFalse();
        tracker.recordSubmission(AuthenticationUiType.LOGIN, true);
        assertThat(tracker.wasSubmitted(AuthenticationUiType.LOGIN)).isTrue();
        assertThat(tracker.wasSubmitted(AuthenticationUiType.REGISTER)).isFalse();
        tracker.recordSubmission(AuthenticationUiType.REGISTER, true);
        assertThat(tracker.wasSubmitted(AuthenticationUiType.REGISTER)).isTrue();

        tracker.reset();
        assertThat(tracker.wasSubmitted(AuthenticationUiType.LOGIN)).isFalse();
        assertThat(tracker.wasSubmitted(AuthenticationUiType.REGISTER)).isFalse();
    }

    @Test
    void autoModeRequiresAnObservedAccountStateBeforeSubmittingCredentials() {
        assertThat(BotSession.autoAuthenticationType(null)).isEmpty();
        assertThat(BotSession.autoAuthenticationType(AuthenticationUiType.LOGIN))
            .contains(AuthenticationUiType.LOGIN);
        assertThat(BotSession.autoAuthenticationType(AuthenticationUiType.REGISTER))
            .contains(AuthenticationUiType.REGISTER);
    }

    @Test
    void failedPrePlayCommandSubmissionDoesNotLockTheSentFlag() {
        AtomicBoolean sent = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();

        assertThat(BotSession.submitAuthenticationCommand(sent, () -> {
            attempts.incrementAndGet();
            return false;
        })).isFalse();
        assertThat(sent).isFalse();

        assertThat(BotSession.submitAuthenticationCommand(sent, () -> {
            attempts.incrementAndGet();
            return true;
        })).isTrue();
        assertThat(sent).isTrue();
        assertThat(attempts).hasValue(2);
    }

    private static AuthenticationUiChallenge login(String action) {
        return new AuthenticationUiChallenge(AuthenticationUiType.LOGIN, AuthenticationUiProvider.AUTHME_UI,
            action, "password", null, AuthenticationUiInputPurpose.NONE, null);
    }

    private static AuthenticationUiChallenge register(String action, AuthenticationUiInputPurpose purpose) {
        return new AuthenticationUiChallenge(AuthenticationUiType.REGISTER, AuthenticationUiProvider.AUTHME_UI,
            action, "password", "secondary", purpose, null);
    }

    private static AuthenticationUiChallenge rules(String action) {
        return new AuthenticationUiChallenge(AuthenticationUiType.RULES, AuthenticationUiProvider.AUTHME_UI,
            action, null, null, AuthenticationUiInputPurpose.NONE, "accept");
    }
}
