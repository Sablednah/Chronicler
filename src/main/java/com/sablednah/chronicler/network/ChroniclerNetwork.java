package com.sablednah.chronicler.network;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * Payload registration (mod bus). {@code optional()} because vanilla clients
 * join without these channels and get the book; every clientbound send still
 * goes through {@code Net.sendIfAble}, since optional makes the handshake
 * tolerant and not the send.
 */
public final class ChroniclerNetwork {

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").optional();
        // The client class is named only inside the enqueued lambda, so a dedicated server never loads it.
        registrar.playToClient(JournalPayload.TYPE, JournalPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> com.sablednah.chronicler.client.ClientJournal.accept(payload)));
        registrar.playToServer(JournalRequestPayload.TYPE, JournalRequestPayload.CODEC, (payload, context) ->
                context.enqueueWork(() -> {
                    if (context.player() instanceof ServerPlayer player) {
                        com.sablednah.chronicler.neoforge.JournalPanel.request(player, payload.action(), payload.arg());
                    }
                }));
    }

    private ChroniclerNetwork() {}
}
