package dev.nulli0n.vbot.adapter.modern;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;

/**
 * Serverbound packets whose shape changed in 26.3: the hand swing became a
 * hand-less punch and teleport acknowledgements echo the resolved position.
 */
final class ModernPacketCompat {
    private ModernPacketCompat() {
    }

    static Packet swingMainHand() {
        return ServerboundPunchPacket.INSTANCE;
    }

    static Packet acceptTeleportation(int id, double x, double y, double z, float yaw, float pitch) {
        return new ServerboundAcceptTeleportationPacket(id, x, y, z, yaw, pitch);
    }
}
