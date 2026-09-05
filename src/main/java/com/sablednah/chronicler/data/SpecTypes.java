package com.sablednah.chronicler.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.sablednah.chronicler.Chronicler;

import net.minecraft.resources.Identifier;

/**
 * A registry of codec-dispatched spec types -- the shape ZombieMod uses for
 * goals, abilities and spawn conditions, and LegendQuest for skill effects.
 *
 * <p>Each objective or reward is a record whose fields mirror what it does,
 * with a {@link MapCodec} and an id; the JSON carries {@code "type"}. The
 * {@link #register} method is <b>public on purpose</b>: the interesting
 * objective types (a LegendQuest level, a ZombieMod genus kill, a CityWorld
 * district) live in the compat layer or in other mods entirely, and must not
 * become hard dependencies of this class.</p>
 *
 * <p>A bare {@code "type": "kill"} resolves to {@code chronicler:kill}; a
 * namespaced type is taken as written. Registering the same id twice is an
 * error, not a silent replacement.</p>
 */
public final class SpecTypes<T> {

    private final String what;
    private final Map<Identifier, MapCodec<? extends T>> byId = new LinkedHashMap<>();
    private final Codec<T> codec;

    public SpecTypes(String what, Function<T, MapCodec<? extends T>> codecOf) {
        this.what = what;
        Codec<MapCodec<? extends T>> typeCodec = Codec.STRING.comapFlatMap(
                this::lookup, this::nameOf);
        this.codec = typeCodec.dispatch("type", codecOf, c -> c);
    }

    private DataResult<MapCodec<? extends T>> lookup(String text) {
        return ChroniclerIds.parse(text).flatMap(id -> {
            MapCodec<? extends T> found;
            synchronized (this) { found = byId.get(id); }
            return found == null
                    ? DataResult.error(() -> "Unknown " + what + " type '" + id + "'. Known: " + known())
                    : DataResult.success(found);
        });
    }

    private String nameOf(MapCodec<? extends T> codec) {
        synchronized (this) {
            for (var e : byId.entrySet()) {
                if (e.getValue() == codec) return e.getKey().toString();
            }
        }
        throw new IllegalStateException("Unregistered " + what + " codec: " + codec);
    }

    private String known() {
        synchronized (this) { return String.join(", ", byId.keySet().stream().map(Identifier::toString).toList()); }
    }

    /** Add a type. Call during mod construction; a duplicate id is refused loudly. */
    public synchronized void register(Identifier id, MapCodec<? extends T> codec) {
        if (byId.containsKey(id)) {
            throw new IllegalArgumentException(what + " type '" + id + "' is already registered");
        }
        byId.put(id, codec);
        Chronicler.LOGGER.debug("Chronicler: {} type '{}' registered", what, id);
    }

    /** Shorthand for our own namespace. */
    public void register(String path, MapCodec<? extends T> codec) {
        register(ChroniclerIds.of(path), codec);
    }

    public Codec<T> codec() {
        return codec;
    }

    public synchronized int size() {
        return byId.size();
    }
}
