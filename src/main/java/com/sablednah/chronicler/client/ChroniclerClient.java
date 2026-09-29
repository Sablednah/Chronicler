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

    public ChroniclerClient(IEventBus modEventBus, net.neoforged.fml.ModContainer modContainer) {
        modContainer.registerConfig(net.neoforged.fml.config.ModConfig.Type.CLIENT, ChroniclerClientConfig.SPEC);
        modEventBus.addListener(ChroniclerKeys::register);
        modEventBus.addListener(ClientHud::register);
        // Standards' action bar: its quest-tracker button toggles the HUD here rather than asking the
        // server (which would answer in chat), and is lit while the HUD is up. Guarded like every seam.
        if (net.neoforged.fml.ModList.get().isLoaded("standards")) {
            Chronicler.optionalIntegration("action bar HUD toggle",
                    com.sablednah.chronicler.client.compat.StandardsHudButton::register);
        }
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> ChroniclerKeys.onClientTick());
        // Last server's journal is not this server's.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            ClientJournal.clear();
            ClientHud.clear();
            ClientWaypoints.clear();
        });
    }
}
