package com.sablednah.chronicler.data;

import java.util.List;
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

    /**
     * Kill {@code count} of {@code target} -- an entity id, a {@code #tag}, a
     * ZombieMod genus, or {@code any}; a list means any of them, so a file can
     * name the genus first and the vanilla stand-in second. {@code tag} also
     * counts anything a {@code spawn} effect tagged (a named mini-boss with no
     * ZombieMod behind it). {@code drop} makes each counted kill drop a quest
     * item with the given chance, so samples come off the things you hunt
     * whether or not a loot table exists.
     */
    public record Kill(List<String> targets, int count, Optional<String> tag, Optional<Drop> drop) implements ObjectiveSpec {
        public record Drop(Identifier questItem, double chance, int count) {
            public static final Codec<Drop> CODEC = RecordCodecBuilder.create(i -> i.group(
                    ChroniclerIds.CODEC.fieldOf("quest_item").forGetter(Drop::questItem),
                    Codec.DOUBLE.optionalFieldOf("chance", 1.0D).forGetter(Drop::chance),
                    Codec.INT.optionalFieldOf("count", 1).forGetter(Drop::count))
                    .apply(i, Drop::new));
        }
        private static final Codec<List<String>> TARGETS = Codec.either(Codec.STRING, Codec.STRING.listOf())
                .xmap(e -> e.map(List::of, l -> l), l -> l.size() == 1 ? com.mojang.datafixers.util.Either.left(l.getFirst()) : com.mojang.datafixers.util.Either.right(l));
        public static final MapCodec<Kill> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                TARGETS.optionalFieldOf("target", List.of("any")).forGetter(Kill::targets),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Kill::count),
                Codec.STRING.optionalFieldOf("tag").forGetter(Kill::tag),
                Drop.CODEC.optionalFieldOf("drop").forGetter(Kill::drop))
                .apply(i, Kill::new));

        public Kill(String target, int count) { this(List.of(target), count, Optional.empty(), Optional.empty()); }
        /** The first named target, for text. */
        public String target() { return targets.isEmpty() ? "any" : targets.getFirst(); }

        @Override public MapCodec<Kill> codec() { return MAP_CODEC; }
        @Override public int required() { return count; }
        @Override public String describe() {
            return Lang.fmt("obj.kill", "count", count, "target", Lang.pretty(target()));
        }
    }

    /**
     * Build something and light it: right-click {@code block} (holding {@code item},
     * if one is named) while every {@code pattern} block sits at its offset from
     * it. One click, one completion; the beat's {@code on_complete} is where the
     * boss comes out. A click on the right block with the pattern wrong says
     * which block is missing where, so nobody has to guess.
     */
    public record Ritual(String block, List<PatternBlock> pattern, Optional<Identifier> item, boolean consume,
            Optional<String> label) implements ObjectiveSpec {
        public record PatternBlock(List<Integer> offset, String block) {
            public static final Codec<PatternBlock> CODEC = RecordCodecBuilder.create(i -> i.group(
                    Codec.INT.listOf(3, 3).fieldOf("offset").forGetter(PatternBlock::offset),
                    Codec.STRING.fieldOf("block").forGetter(PatternBlock::block))
                    .apply(i, PatternBlock::new));
        }
        public static final MapCodec<Ritual> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("block").forGetter(Ritual::block),
                PatternBlock.CODEC.listOf().optionalFieldOf("pattern", List.of()).forGetter(Ritual::pattern),
                Identifier.CODEC.optionalFieldOf("item").forGetter(Ritual::item),
                Codec.BOOL.optionalFieldOf("consume", true).forGetter(Ritual::consume),
                Codec.STRING.optionalFieldOf("label").forGetter(Ritual::label))
                .apply(i, Ritual::new));
        @Override public MapCodec<Ritual> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() {
            return label.orElseGet(() -> item.map(it -> Lang.fmt("obj.ritual_item", "block", Lang.pretty(block), "item", Lang.pretty(it.getPath())))
                    .orElseGet(() -> Lang.fmt("obj.ritual", "block", Lang.pretty(block))));
        }
    }

    /** Let time pass: {@code seconds} of game time from entering the beat. Polled; latching. */
    public record Wait(int seconds, Optional<String> label) implements ObjectiveSpec {
        public static final MapCodec<Wait> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("seconds").forGetter(Wait::seconds),
                Codec.STRING.optionalFieldOf("label").forGetter(Wait::label))
                .apply(i, Wait::new));
        @Override public MapCodec<Wait> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() {
            return label.orElseGet(() -> Lang.fmt("obj.wait", "time", com.sablednah.chronicler.neoforge.QuestEngine.clock(seconds * 20L)));
        }
    }

    /** Hold {@code count} of {@code item}; {@code consume} takes them on completion. */
    public record Collect(Identifier item, int count, boolean consume, Optional<Identifier> questItem, Optional<Identifier> tag) implements ObjectiveSpec {
        public Collect(Identifier item, int count, boolean consume) { this(item, count, consume, Optional.empty(), Optional.empty()); }
        public static final MapCodec<Collect> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("item", Identifier.withDefaultNamespace("air")).forGetter(Collect::item),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Collect::count),
                Codec.BOOL.optionalFieldOf("consume", true).forGetter(Collect::consume),
                ChroniclerIds.CODEC.optionalFieldOf("quest_item").forGetter(Collect::questItem),
                Identifier.CODEC.optionalFieldOf("tag").forGetter(Collect::tag))
                .apply(i, Collect::new));

        @Override public MapCodec<Collect> codec() { return MAP_CODEC; }
        @Override public int required() { return count; }
        @Override public String describe() {
            return Lang.fmt(consume ? "obj.collect" : "obj.hold", "count", count,
                    "item", questItem.map(QuestItem::displayName).orElseGet(() -> Lang.pretty(tag.map(Identifier::getPath).orElse(item.getPath()))));
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

    /** Wait for a flag -- the world's, or the player's own -- to hold. Polled; latching. */
    public record FlagSet(String name, boolean value, boolean player) implements ObjectiveSpec {
        public static final MapCodec<FlagSet> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(FlagSet::name),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(FlagSet::value),
                Codec.BOOL.optionalFieldOf("player", false).forGetter(FlagSet::player))
                .apply(i, FlagSet::new));
        @Override public MapCodec<FlagSet> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() { return Lang.fmt("obj.flag", "flag", Lang.pretty(name)); }
    }

    static {
        TYPES.register("kill", Kill.MAP_CODEC);
        TYPES.register("flag", FlagSet.MAP_CODEC);
        TYPES.register("place", At.MAP_CODEC);
        TYPES.register("reputation", Reputation.MAP_CODEC);
        TYPES.register("collect", Collect.MAP_CODEC);
        TYPES.register("visit", Visit.MAP_CODEC);
        TYPES.register("ritual", Ritual.MAP_CODEC);
        TYPES.register("wait", Wait.MAP_CODEC);
    }

    /** Touch the class so the static block has run before a codec is asked for. */
    public static void init() {}

    private ObjectiveTypes() {}
}
