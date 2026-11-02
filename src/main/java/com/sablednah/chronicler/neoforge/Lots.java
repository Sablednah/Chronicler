package com.sablednah.chronicler.neoforge;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * "What kind of place is this?" -- the CityWorld bridge. Empty means nobody
 * can say (no CityWorld, or not a CityWorld level); a list of words means the
 * lot's context, class, interior, schematic and shop, for a {@code lot} match.
 * {@code compat/CityWorldLots} fills the slot.
 */
public final class Lots {

    private static volatile BiFunction<ServerLevel, BlockPos, Optional<List<String>>> provider =
            (level, pos) -> Optional.empty();

    /**
     * Answers already given, by level and chunk, for this server run. A lot is chunk-granular and fixed by the
     * seed, so an answer never goes stale -- and asking CityWorld about a chunk nobody has walked to makes it
     * plan the city there (plat maps, subway stations, the structures it reserves room for: villages, jigsaw
     * and all). A wild Bank Job searching 640 blocks did that thousands of times on one tick and froze ZARP
     * for 55 seconds. Bounded, oldest asked first out; cleared on ServerStopped with every other session map.
     */
    private static final int CACHE_MAX = 65536;
    private static final Map<String, Optional<List<String>>> CACHE = java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<>(1024, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Optional<List<String>>> e) { return size() > CACHE_MAX; }
            });

    public static Optional<List<String>> describe(ServerLevel level, BlockPos pos) {
        String key = level.dimension().identifier() + "|" + (pos.getX() >> 4) + "," + (pos.getZ() >> 4);
        var known = CACHE.get(key);
        if (known != null) return known;
        Optional<List<String>> out;
        try {
            out = provider.apply(level, pos).map(List::copyOf);
        } catch (RuntimeException e) {
            return Optional.empty(); // not remembered: it may answer next time
        }
        CACHE.put(key, out);
        return out;
    }

    /** Has this chunk been asked about already this run -- is asking again free? */
    static boolean known(ServerLevel level, int chunkX, int chunkZ) {
        return CACHE.containsKey(level.dimension().identifier() + "|" + chunkX + "," + chunkZ);
    }

    static void clearSession() { CACHE.clear(); }

    /** Whatever answers now; the self-test puts it back after a fixture. */
    static BiFunction<ServerLevel, BlockPos, Optional<List<String>>> current() { return provider; }

    static int sessionStateCount() { return CACHE.size(); }

    public static void install(BiFunction<ServerLevel, BlockPos, Optional<List<String>>> p) {
        provider = p;
        CACHE.clear();
    }

    private Lots() {}
}
