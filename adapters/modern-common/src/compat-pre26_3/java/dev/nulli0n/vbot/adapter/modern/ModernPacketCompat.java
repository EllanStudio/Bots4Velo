package dev.nulli0n.vbot.adapter.modern;

import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;

/**
 * Serverbound packets whose shape changed in 26.3. This variant is compiled
 * into the 1.21.11, 26.1.2 and 26.2 adapters.
 */
final class ModernPacketCompat {
    private ModernPacketCompat() {
    }

    static Packet swingMainHand() {
        return new ServerboundSwingPacket(Hand.MAIN_HAND);
    }

    static Packet acceptTeleportation(int id, double x, double y, double z, float yaw, float pitch) {
        return new ServerboundAcceptTeleportationPacket(id);
    }
}
