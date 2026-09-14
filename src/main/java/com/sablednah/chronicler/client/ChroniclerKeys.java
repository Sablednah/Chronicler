package com.sablednah.chronicler.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.sablednah.chronicler.Chronicler;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * The journal key, in its own "Chronicler" section of Controls. The backtick:
 * where players already bind quest books (Sable, 2026-09-14). Not J -- J is
 * JourneyMap's full-screen map, and a mod that common wins any argument over a
 * key; ZombieMod moved its dex off J for the same reason. The backtick can meet
 * a vein-mine mod's bind; that is a rebind in Controls, and the lesser clash.
 */
public final class ChroniclerKeys {

    // Category before the mapping that names it: static fields initialise in order.
    private static final KeyMapping.Category CATEGORY =
            new KeyMapping.Category(Identifier.fromNamespaceAndPath(Chronicler.MODID, "main"));

    public static final KeyMapping JOURNAL =
            new KeyMapping("key.chronicler.journal", InputConstants.KEY_GRAVE, CATEGORY);

    static void register(RegisterKeyMappingsEvent event) {
        event.registerCategory(CATEGORY);
        event.register(JOURNAL);
    }

    static void onClientTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        while (JOURNAL.consumeClick()) {
            if (mc.screen == null) ClientJournal.open();
        }
    }

    private ChroniclerKeys() {}
}
