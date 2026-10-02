package dev.nulli0n.vbot.verify;

import dev.nulli0n.vbot.protocol.ProtocolVersion;
import dev.nulli0n.vbot.protocol.TransportRegistry;
import dev.nulli0n.vbot.transport.BotTransportFactory;

/**
 * Build-time smoke entry point. It is intentionally run from the final JAR so
 * relocation or missing version adapters fail the build before deployment.
 */
public final class ProtocolSmokeMain {
    private ProtocolSmokeMain() {
    }

    public static void main(String[] args) {
        TransportRegistry registry = new TransportRegistry();
        verify(registry, ProtocolVersion.MINECRAFT_1_16_5);
        verify(registry, ProtocolVersion.MINECRAFT_1_21_11);
        verify(registry, ProtocolVersion.MINECRAFT_26_1_2);
        verify(registry, ProtocolVersion.MINECRAFT_26_2);
        verify(registry, ProtocolVersion.MINECRAFT_26_3);
    }

    private static void verify(TransportRegistry registry, ProtocolVersion expected) {
        BotTransportFactory factory = registry.factory(expected);
        if (factory.protocolId() != expected.protocolId()) {
            throw new IllegalStateException("Protocol adapter mismatch for " + expected.displayName());
        }
        System.out.println("Embedded adapter: " + factory.versionName() + " / " + factory.protocolId());
    }
}
