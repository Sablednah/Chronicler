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
        if (listening(player, payload.type())) {
            PacketDistributor.sendToPlayer(player, payload);
        }
    }

    /**
     * Is this player running our client half? Vanilla says no, and so does a
     * fake player. Not just a null check: NeoForge's {@code FakePlayer} HAS a
     * connection, whose netty channel is null, and {@code hasChannel} throws on
     * it -- from whatever event asked. Found by the self-test's FakePlayers the
     * first time a panel send reached one; another mod's automation clicking a
     * journal would have found it on someone's server instead.
     *
     * <p>{@code isFakePlayer()} rather than an instanceof, so subclasses count;
     * {@code isConnected()} (a channel, and open) for a mod that hand-rolls a
     * fake ServerPlayer without NeoForge's class. The same guard ZombieMod
     * settled on when told of this (2026-09-14).</p>
     */
    public static boolean listening(ServerPlayer player, CustomPacketPayload.Type<?> type) {
        return player.connection != null && !player.isFakePlayer()
                && player.connection.getConnection().isConnected()
                && player.connection.hasChannel(type);
    }

    private Net() {}
}
