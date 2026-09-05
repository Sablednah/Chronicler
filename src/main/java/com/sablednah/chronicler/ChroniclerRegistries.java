package com.sablednah.chronicler;

import com.sablednah.chronicler.data.Chapter;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.neoforged.neoforge.registries.DataPackRegistryEvent;

/**
 * Registry keys owned by Chronicler.
 *
 * <p>Both are <em>datapack</em> registries: content is JSON at
 * {@code data/<pack>/chronicler/{chapter,quest}/<name>.json}, or YAML at
 * {@code config/chronicler/{chapters,quests}/<name>.yml} through the YAML
 * front door. Network codecs are supplied so definitions sync to modded
 * clients, which a future tracker HUD will want.</p>
 *
 * <p><b>Frozen at world load.</b> Like vanilla enchantments, a datapack
 * registry is built once when the world starts; {@code /reload} re-runs
 * recipes, tags and functions and never the registry loader. Content edits
 * land on RESTART. Text ({@code messages.yml}) is not a registry and does
 * reload.</p>
 */
public final class ChroniclerRegistries {

    public static final ResourceKey<Registry<Chapter>> CHAPTER =
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Chronicler.MODID, "chapter"));

    public static final ResourceKey<Registry<Quest>> QUEST =
            ResourceKey.createRegistryKey(Identifier.fromNamespaceAndPath(Chronicler.MODID, "quest"));

    /** Registered on the mod event bus. */
    static void register(DataPackRegistryEvent.NewRegistry event) {
        event.dataPackRegistry(CHAPTER, Chapter.CODEC, Chapter.CODEC);
        event.dataPackRegistry(QUEST, Quest.CODEC, Quest.CODEC);
    }

    private ChroniclerRegistries() {}
}
