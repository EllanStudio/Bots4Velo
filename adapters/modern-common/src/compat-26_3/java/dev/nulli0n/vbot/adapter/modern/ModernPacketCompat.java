package dev.nulli0n.vbot.adapter.modern;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPunchPacket;

/**
 * Serverbound packets whose shape changed in 26.3: the hand swing became a
 * hand-less punch, teleport acknowledgements echo the resolved position, and
 * the server kicks a client that sends two position packets in one client
 * tick ("Invalid move player packet received").
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

    /** Ends the client tick after a position packet; null where not required. */
    static Packet clientTickEnd() {
        return ServerboundClientTickEndPacket.INSTANCE;
    }
}
