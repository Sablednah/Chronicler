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
        String key = key(level, pos.getX() >> 4, pos.getZ() >> 4);
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
        return CACHE.containsKey(key(level, chunkX, chunkZ));
    }

    private static String key(ServerLevel level, int chunkX, int chunkZ) {
        return level.dimension().identifier() + "|" + chunkX + "," + chunkZ;
    }

    /**
     * The lot without waiting for it (CityWorld 6.12+): a done future when its plan already exists, else a plan
     * started on CityWorld's own threads -- or null when {@code mayPlan} is false and it would have to start one.
     * A future completes off the server thread.
     */
    public interface Asker {
        java.util.concurrent.CompletableFuture<Optional<List<String>>> ask(ServerLevel level, BlockPos pos, boolean mayPlan);
    }

    private static volatile Asker asker;

    /**
     * Plans in flight, by an 8x8-chunk patch: a city's plan covers several chunks, and asking for each of its chunks
     * separately queued the same plan over and over on a pool of two to four threads. A handful at once, no more.
     */
    private static final java.util.Set<String> ASKING = java.util.concurrent.ConcurrentHashMap.newKeySet();
    static final int ASKING_MAX = 4;
    /** Bumped when the world goes: an answer arriving after it is about the old one and is dropped. */
    private static final java.util.concurrent.atomic.AtomicInteger GENERATION = new java.util.concurrent.atomic.AtomicInteger();

    /** Can a lot be asked about without the server thread waiting for CityWorld to plan it? */
    static boolean nonBlocking() { return asker != null; }

    /**
     * {@link #describe} that never waits: the answer, or empty while CityWorld plans the place off the server thread
     * (asked again later, it is there). Without the non-blocking API it is {@code describe}, waiting and all.
     */
    static Optional<Optional<List<String>>> peek(ServerLevel level, BlockPos pos) {
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        String key = key(level, cx, cz);
        var known = CACHE.get(key);
        if (known != null) return Optional.of(known);
        Asker a = asker;
        if (a == null) return Optional.of(describe(level, pos));
        String patch = level.dimension().identifier() + "|" + (cx >> 3) + "," + (cz >> 3);
        java.util.concurrent.CompletableFuture<Optional<List<String>>> f;
        try {
            f = a.ask(level, pos, !ASKING.contains(patch) && ASKING.size() < ASKING_MAX);
        } catch (RuntimeException e) {
            return Optional.of(Optional.empty()); // as describe: no answer, not remembered
        }
        if (f == null) return Optional.empty();
        if (f.isDone()) {
            if (f.isCompletedExceptionally()) return Optional.of(Optional.empty());
            Optional<List<String>> out = f.join().map(List::copyOf);
            CACHE.put(key, out);
            return Optional.of(out);
        }
        ASKING.add(patch);
        int generation = GENERATION.get();
        f.whenComplete((answer, failed) -> {
            ASKING.remove(patch);
            if (failed == null && answer != null && generation == GENERATION.get()) CACHE.put(key, answer.map(List::copyOf));
        });
        return Optional.empty();
    }

    static void clearSession() {
        GENERATION.incrementAndGet();
        CACHE.clear();
        ASKING.clear();
    }

    /** What answers now, both ways; the self-test puts it back after a fixture. */
    record Installed(BiFunction<ServerLevel, BlockPos, Optional<List<String>>> provider, Asker asker) {}

    static Installed current() { return new Installed(provider, asker); }

    static void install(Installed was) {
        install(was.provider());
        asker = was.asker();
    }

    static int asking() { return ASKING.size(); }

    static int sessionStateCount() { return CACHE.size() + ASKING.size(); }

    /** The blocking lookup alone; a non-blocking asker installed before it is dropped (it answers for another provider). */
    public static void install(BiFunction<ServerLevel, BlockPos, Optional<List<String>>> p) {
        provider = p;
        asker = null;
        clearSession();
    }

    /** With {@link #install}: the non-blocking way to ask the same provider. */
    public static void installAsker(Asker a) {
        asker = a;
    }

    private Lots() {}
}
