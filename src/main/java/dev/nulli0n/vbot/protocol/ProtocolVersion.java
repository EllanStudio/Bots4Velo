package dev.nulli0n.vbot.protocol;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

public enum ProtocolVersion {
    MINECRAFT_1_16_5("1.16.5", 754),
    MINECRAFT_1_21_11("1.21.11", 774),
    MINECRAFT_26_1_2("26.1.2", 775),
    MINECRAFT_26_2("26.2", 776),
    MINECRAFT_26_3("26.3", 777);

    private final String displayName;
    private final int protocolId;

    ProtocolVersion(String displayName, int protocolId) {
        this.displayName = displayName;
        this.protocolId = protocolId;
    }

    public String displayName() {
        return displayName;
    }

    public int protocolId() {
        return protocolId;
    }

    public static Optional<ProtocolVersion> byProtocolId(int protocolId) {
        return Arrays.stream(values()).filter(version -> version.protocolId == protocolId).findFirst();
    }

    public static ProtocolVersion parse(String raw) {
        String normalized = raw.trim().toLowerCase(Locale.ROOT)
            .replace("minecraft_", "")
            .replace('_', '.');
        if (normalized.equals("26.1") || normalized.equals("26.1.1")) {
            normalized = "26.1.2";
        }
        final String requested = normalized;
        return Arrays.stream(values())
            .filter(version -> version.displayName.equalsIgnoreCase(requested))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException(
                "Unsupported protocol-version: " + raw + ". Expected AUTO, 1.16.5, 1.21.11, 26.1.2, 26.2 or 26.3"));
    }
}
