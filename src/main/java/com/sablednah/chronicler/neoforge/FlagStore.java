package com.sablednah.chronicler.neoforge;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * World flags: named facts a quest sets and anything else reads --
 * {@code bridge_repaired}, {@code apocalypse_over}. SavedData, world-global.
 *
 * <p>Read from anywhere, including a worldgen thread through the ZombieMod
 * spawn condition, so the map is concurrent and {@link #cached()} answers
 * without touching the data storage -- which is only safe from the server
 * thread. The cache is primed on server start.</p>
 */
public final class FlagStore extends SavedData {

    private static final Codec<FlagStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.BOOL).fieldOf("flags").forGetter(s -> Map.copyOf(s.flags)))
            .apply(i, FlagStore::new));

    public static final SavedDataType<FlagStore> TYPE =
            new SavedDataType<>("chronicler_flags", FlagStore::new, CODEC, null);

    private static volatile FlagStore cached;

    private final Map<String, Boolean> flags;

    public FlagStore() {
        this(Map.of());
    }

    private FlagStore(Map<String, Boolean> flags) {
        this.flags = new ConcurrentHashMap<>(flags);
    }

    /** Server thread only. */
    public static FlagStore get(MinecraftServer server) {
        FlagStore store = server.overworld().getDataStorage().computeIfAbsent(TYPE);
        cached = store;
        return store;
    }

    /** Any thread; null before the server has started. */
    public static FlagStore cached() {
        return cached;
    }

    public static String normalise(String name) {
        return name.trim().toLowerCase(java.util.Locale.ROOT);
    }

    public boolean is(String name) {
        return flags.getOrDefault(normalise(name), false);
    }

    public void set(String name, boolean value) {
        if (value) flags.put(normalise(name), true); else flags.remove(normalise(name));
        setDirty();
    }

    public Map<String, Boolean> view() {
        return Collections.unmodifiableMap(flags);
    }
}
