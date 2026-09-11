package dev.nulli0n.vbot.bot;

import dev.nulli0n.vbot.transport.BotPosition;

import java.time.Instant;
import java.util.List;

public record BotSnapshot(
    String id,
    String username,
    String protocolVersion,
    String protocolSource,
    BotState state,
    int reconnectAttempts,
    Instant connectedAt,
    long playEntries,
    long disconnects,
    long resourcePacksLoaded,
    Instant lastPlayAt,
    Instant lastDisconnectAt,
    BotPosition position,
    String authenticationUi,
    long authenticationUiPresentations,
    long authenticationUiSubmissions,
    String lastDisconnectReason,
    BehaviorSnapshot behavior,
    long onlineSeconds,
    FailureCategory failureCategory,
    List<BotEvent> recentEvents,
    boolean authenticationComplete
) {
    /**
     * Source-compatible constructor for addons compiled against the pre-3.0.5
     * snapshot shape.  Authentication is deliberately fail-closed when an
     * older producer does not expose the new state bit.
     */
    public BotSnapshot(
        String id,
        String username,
        String protocolVersion,
        String protocolSource,
        BotState state,
        int reconnectAttempts,
        Instant connectedAt,
        long playEntries,
        long disconnects,
        long resourcePacksLoaded,
        Instant lastPlayAt,
        Instant lastDisconnectAt,
        BotPosition position,
        String authenticationUi,
        long authenticationUiPresentations,
        long authenticationUiSubmissions,
        String lastDisconnectReason,
        BehaviorSnapshot behavior,
        long onlineSeconds,
        FailureCategory failureCategory,
        List<BotEvent> recentEvents
    ) {
        this(id, username, protocolVersion, protocolSource, state, reconnectAttempts,
            connectedAt, playEntries, disconnects, resourcePacksLoaded, lastPlayAt,
            lastDisconnectAt, position, authenticationUi, authenticationUiPresentations,
            authenticationUiSubmissions, lastDisconnectReason, behavior, onlineSeconds,
            failureCategory, recentEvents, false);
    }
}
