package dev.nulli0n.vbot.addon.api;

public record AddonBotSnapshot(String id, String username, AddonBotState state,
                               boolean authenticationComplete) {
    /** Compatibility constructor for addons built against API 3.0.4 and earlier. */
    public AddonBotSnapshot(String id, String username, AddonBotState state) {
        this(id, username, state, false);
    }
}
