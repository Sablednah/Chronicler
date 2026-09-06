package com.sablednah.chronicler.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Op-placed givers: "this block offers that quest". SavedData, world-global,
 * because a giver belongs to the world and must answer for a player who is
 * not online. Keyed by dimension and position.
 *
 * <p>26.1 moves saved data into a namespaced folder; the version branch
 * carries the migration, and this class logs its count on start so an empty
 * store is distinguishable from a lost one.</p>
 */
public final class GiverStore extends SavedData {

    private static final Codec<GiverStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC).fieldOf("givers").forGetter(s -> s.byKey))
            .apply(i, GiverStore::new));

    public static final SavedDataType<GiverStore> TYPE =
            new SavedDataType<>("chronicler_givers", GiverStore::new, CODEC, null);

    private final Map<String, Identifier> byKey;

    public GiverStore() {
        this(Map.of());
    }

    private GiverStore(Map<String, Identifier> byKey) {
        this.byKey = new LinkedHashMap<>(byKey);
    }

    public static GiverStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    public Optional<Identifier> at(ServerLevel level, BlockPos pos) {
        return Optional.ofNullable(byKey.get(key(level, pos)));
    }

    public void set(ServerLevel level, BlockPos pos, Identifier quest) {
        byKey.put(key(level, pos), quest);
        setDirty();
    }

    public boolean remove(ServerLevel level, BlockPos pos) {
        boolean had = byKey.remove(key(level, pos)) != null;
        if (had) setDirty();
        return had;
    }

    public Map<String, Identifier> view() {
        return java.util.Collections.unmodifiableMap(byKey);
    }

    public int size() {
        return byKey.size();
    }
}
