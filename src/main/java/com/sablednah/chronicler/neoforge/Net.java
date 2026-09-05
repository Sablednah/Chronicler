package com.sablednah.chronicler.neoforge;

import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The one true clientbound send. Written before any payload exists, because
 * the failure mode of forgetting it is vanilla clients being kicked during
 * login.
 *
 * <p>{@code PayloadRegistrar.optional()} makes the <em>handshake</em>
 * tolerant; it does NOT make sends droppable. {@code sendToPlayer} throws,
 * synchronously, on the server thread, for a payload the receiver never
 * negotiated -- and from a login handler that takes vanilla's login flow with
 * it ("Invalid player data"). Channels are agreed in the configuration phase,
 * so there is no later event that helps: guard permanently. Found by
 * LegendQuest, re-found by ZombieMod, written down by Standards.</p>
 */
public final class Net {

    public static void sendIfAble(ServerPlayer player, CustomPacketPayload payload) {
        // Null check: FakePlayers (other mods' automation, headless probes)
        // sit in the player list with no connection at all.
        if (player.connection != null && player.connection.hasChannel(payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /** Is this player running our client half? Vanilla says no. */
    public static boolean listening(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && player.connection.hasChannel(type);
    }

    private Net() {}
}
