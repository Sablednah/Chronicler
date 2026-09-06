package com.sablednah.chronicler.data;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.neoforge.Lang;

import net.minecraft.resources.Identifier;

/**
 * The built-in objective types. Records only -- nothing here touches a
 * level, an entity or an item stack, and that is deliberate: an
 * {@code ItemStack} cannot be built while a datapack registry is loading on
 * 26.x, so items are held as ids and resolved at use time.
 *
 * <p>Adding one: a record with a {@code MAP_CODEC} and a {@code register}
 * line below. Other mods add theirs through {@link #TYPES}.</p>
 */
public final class ObjectiveTypes {

    public static final SpecTypes<ObjectiveSpec> TYPES = new SpecTypes<>("objective", ObjectiveSpec::codec);
    public static final Codec<ObjectiveSpec> CODEC = TYPES.codec();

    /** Kill {@code count} of {@code target} -- an entity id, a {@code #tag}, or {@code any}. */
    public record Kill(String target, int count) implements ObjectiveSpec {
        public static final MapCodec<Kill> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("target", "any").forGetter(Kill::target),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Kill::count))
                .apply(i, Kill::new));

        @Override public MapCodec<Kill> codec() { return MAP_CODEC; }
        @Override public int required() { return count; }
        @Override public String describe() {
            return Lang.fmt("obj.kill", "count", count, "target", Lang.pretty(target));
        }
    }

    /** Hold {@code count} of {@code item}; {@code consume} takes them on completion. */
    public record Collect(Identifier item, int count, boolean consume) implements ObjectiveSpec {
        public static final MapCodec<Collect> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf("item").forGetter(Collect::item),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Collect::count),
                Codec.BOOL.optionalFieldOf("consume", true).forGetter(Collect::consume))
                .apply(i, Collect::new));

        @Override public MapCodec<Collect> codec() { return MAP_CODEC; }
        @Override public int required() { return count; }
        @Override public String describe() {
            return Lang.fmt(consume ? "obj.collect" : "obj.hold", "count", count,
                    "item", Lang.pretty(item.getPath()));
        }
    }

    /**
     * Stand within {@code radius} of a point. {@code label} is what the player
     * is told ("the hospital"), because coordinates are not a story.
     */
    public record Visit(int x, Optional<Integer> y, int z, double radius,
            Optional<Identifier> dimension, Optional<String> label) implements ObjectiveSpec {
        public static final MapCodec<Visit> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("x").forGetter(Visit::x),
                Codec.INT.optionalFieldOf("y").forGetter(Visit::y),
                Codec.INT.fieldOf("z").forGetter(Visit::z),
                Codec.DOUBLE.optionalFieldOf("radius", 8.0D).forGetter(Visit::radius),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(Visit::dimension),
                Codec.STRING.optionalFieldOf("label").forGetter(Visit::label))
                .apply(i, Visit::new));

        @Override public MapCodec<Visit> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() {
            return label.map(l -> Lang.fmt("obj.visit_label", "label", l))
                    .orElseGet(() -> Lang.fmt("obj.visit", "x", x, "z", z));
        }
    }

    /** Hold a standing of at least {@code at_least} with a group. Polled; not latching. */
    public record Reputation(String standing, int atLeast) implements ObjectiveSpec {
        public static final MapCodec<Reputation> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("standing").forGetter(Reputation::standing),
                Codec.INT.fieldOf("at_least").forGetter(Reputation::atLeast))
                .apply(i, Reputation::new));

        @Override public MapCodec<Reputation> codec() { return MAP_CODEC; }
        @Override public int required() { return Math.max(1, atLeast); }
        @Override public String describe() {
            return Lang.fmt("obj.reputation", "amount", atLeast, "standing", Lang.pretty(standing));
        }
    }

    /** Be in a kind of place -- a biome, a structure, a dimension, a CityWorld lot. Latching. */
    public record At(Place place, Optional<String> label) implements ObjectiveSpec {
        public static final MapCodec<At> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Place.MAP_CODEC.forGetter(At::place),
                Codec.STRING.optionalFieldOf("label").forGetter(At::label))
                .apply(i, At::new));

        @Override public MapCodec<At> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() {
            return Lang.fmt("obj.visit_label", "label", label.orElseGet(() -> place.describe()));
        }
    }

    static {
        TYPES.register("kill", Kill.MAP_CODEC);
        TYPES.register("place", At.MAP_CODEC);
        TYPES.register("reputation", Reputation.MAP_CODEC);
        TYPES.register("collect", Collect.MAP_CODEC);
        TYPES.register("visit", Visit.MAP_CODEC);
    }

    /** Touch the class so the static block has run before a codec is asked for. */
    public static void init() {}

    private ObjectiveTypes() {}
}
