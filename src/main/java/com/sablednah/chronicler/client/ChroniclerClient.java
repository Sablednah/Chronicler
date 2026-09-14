package com.sablednah.chronicler.client;

import com.sablednah.chronicler.Chronicler;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Client-only entrypoint: the journal panel and its key. Never loaded on a
 * dedicated server, so client classes are safe to name here (the family
 * pattern -- no {@code @OnlyIn}, no {@code DistExecutor}).
 *
 * <p>Everything here is sugar. A client without it plays the same quests from
 * the book, chat and the action bar.</p>
 */
@Mod(value = Chronicler.MODID, dist = Dist.CLIENT)
public final class ChroniclerClient {

    public ChroniclerClient(IEventBus modEventBus) {
        modEventBus.addListener(ChroniclerKeys::register);
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ChroniclerKeys.onClientTick());
        // Last server's journal is not this server's.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> ClientJournal.clear());
    }
}
