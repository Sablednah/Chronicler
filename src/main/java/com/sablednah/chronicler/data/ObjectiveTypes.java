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
     * whether or not a loot table exists. A mob this quest spawned that dies
     * to anything else -- a fall, sunlight, its own explosion -- still counts,
     * unless {@code own_kill} is set, in which case it is spawned again so the
     * player can keep trying (the Bloater you must kill before it blows). With a {@code tag}
     * and no {@code target}, only the tagged one counts; with neither, anything does.
     */
    public record Kill(List<String> targets, int count, Optional<String> tag, Optional<Drop> drop, boolean ownKill) implements ObjectiveSpec {
        public Kill(List<String> targets, int count, Optional<String> tag, Optional<Drop> drop) { this(targets, count, tag, drop, false); }
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
                TARGETS.optionalFieldOf("target", List.of()).forGetter(Kill::targets),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Kill::count),
                Codec.STRING.optionalFieldOf("tag").forGetter(Kill::tag),
                Drop.CODEC.optionalFieldOf("drop").forGetter(Kill::drop),
                Codec.BOOL.optionalFieldOf("own_kill", false).forGetter(Kill::ownKill))
                .apply(i, Kill::new));

        public Kill(String target, int count) { this(List.of(target), count, Optional.empty(), Optional.empty(), false); }
        /** The first named target, for text: the tag when only a tag was given (Phil), "any" when neither. */
        public String target() { return !targets.isEmpty() ? targets.getFirst() : tag.orElse("any"); }

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

    /**
     * Hand {@code count} of an item ({@code quest_item}, or a plain {@code item})
     * to the giver of quest {@code to}: right-click that person or block while
     * holding them, or -- with a {@code radius} -- just stand near. Nothing
     * counts until the hand-over, and the items go on completion. This is what
     * "bring it back" means; a bare {@code collect} is satisfied in your pack.
     *
     * <p>A mini quest hands over to what its slots found instead: {@code npc} (a person's
     * id, {@code "{elder.id}"}) or {@code at} (a block, {@code "{door.pos}"}). One of the
     * three is required. A delivery to a block also stops that block being used any other
     * way until it is done -- the jammed door stays jammed.</p>
     */
    public record Deliver(Optional<Identifier> questItem, Identifier item, Optional<Identifier> tag, int count, Optional<Identifier> to, double radius,
            Optional<String> label, Optional<String> npc, Optional<net.minecraft.core.BlockPos> at) implements ObjectiveSpec {
        public Deliver(Optional<Identifier> questItem, Identifier item, Optional<Identifier> tag, int count, Identifier to, double radius, Optional<String> label) {
            this(questItem, item, tag, count, Optional.of(to), radius, label, Optional.empty(), Optional.empty());
        }
        public static final MapCodec<Deliver> MAP_CODEC = RecordCodecBuilder.<Deliver>mapCodec(i -> i.group(
                ChroniclerIds.CODEC.optionalFieldOf("quest_item").forGetter(Deliver::questItem),
                Identifier.CODEC.optionalFieldOf("item", Identifier.withDefaultNamespace("air")).forGetter(Deliver::item),
                Identifier.CODEC.optionalFieldOf("tag").forGetter(Deliver::tag),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Deliver::count),
                ChroniclerIds.CODEC.optionalFieldOf("to").forGetter(Deliver::to),
                Codec.DOUBLE.optionalFieldOf("radius", 0D).forGetter(Deliver::radius),
                Codec.STRING.optionalFieldOf("label").forGetter(Deliver::label),
                Codec.STRING.optionalFieldOf("npc").forGetter(Deliver::npc),
                net.minecraft.core.BlockPos.CODEC.optionalFieldOf("at").forGetter(Deliver::at))
                .apply(i, Deliver::new)).validate(d -> d.to.isEmpty() && d.npc.isEmpty() && d.at.isEmpty()
                        ? com.mojang.serialization.DataResult.error(() -> "a deliver objective needs 'to' (a quest whose giver takes it), 'npc' or 'at'")
                        : com.mojang.serialization.DataResult.success(d));
        @Override public MapCodec<Deliver> codec() { return MAP_CODEC; }
        @Override public int required() { return count; }
        @Override public String describe() {
            return label.orElseGet(() -> Lang.fmt("obj.deliver", "count", count,
                    "item", questItem.map(QuestItem::displayName).orElseGet(() -> Lang.pretty(tag.map(Identifier::getPath).orElse(item.getPath()))),
                    "who", recipient()));
        }
        /** Who or what takes it, in words. */
        public String recipient() {
            if (to.isPresent()) return com.sablednah.chronicler.neoforge.Givers.nameOf(to.get());
            if (npc.isPresent()) return com.sablednah.chronicler.neoforge.Givers.npcName(npc.get());
            return Lang.get("giver.someone");
        }
    }

    /**
     * Bring a person somewhere alive and with you: {@code who} (a Cast NPC's id, usually
     * {@code "{charge.id}"}) must stand within {@code radius} of {@code to} while the player is
     * within {@code near} of them. The charge follows whoever is escorting it, on foot, along the
     * way they walk (Cast's follow); wander more than {@code escort.pickup} blocks off and it
     * stops and waits where it is until someone comes back for it. Latching.
     *
     * <p>{@code hits} (0, the default: off) makes the trip dangerous: monsters nearby go for the
     * charge, every blow is counted -- never damage -- and at that many the beat fails, as a
     * deadline does ({@code on_fail}, then {@code fail} or abandon).</p>
     */
    public record Escort(String who, Optional<String> name, net.minecraft.core.BlockPos to, Optional<Identifier> dimension,
            double radius, double near, Optional<String> label, int hits, int settle) implements ObjectiveSpec {
        public static final MapCodec<Escort> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("who").forGetter(Escort::who),
                Codec.STRING.optionalFieldOf("name").forGetter(Escort::name),
                net.minecraft.core.BlockPos.CODEC.fieldOf("to").forGetter(Escort::to),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(Escort::dimension),
                Codec.DOUBLE.optionalFieldOf("radius", 12.0D).forGetter(Escort::radius),
                Codec.DOUBLE.optionalFieldOf("near", 10.0D).forGetter(Escort::near),
                Codec.STRING.optionalFieldOf("label").forGetter(Escort::label),
                Codec.intRange(0, 1000).optionalFieldOf("hits", 0).forGetter(Escort::hits),
                Codec.intRange(0, 60).optionalFieldOf("settle", 0).forGetter(Escort::settle))
                .apply(i, Escort::new));
        @Override public MapCodec<Escort> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public int settleSeconds() { return settle; }
        @Override public String describe() {
            String whom = name.orElseGet(() -> com.sablednah.chronicler.neoforge.Givers.npcName(who));
            String line = Lang.fmt("obj.escort", "who", whom, "where", label.orElseGet(() -> Lang.fmt("obj.escort.coords", "x", to.getX(), "z", to.getZ())));
            return hits > 0 ? line + Lang.fmt("obj.escort.hits", "hits", hits) : line;
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
            Optional<Identifier> dimension, Optional<String> label, int settle) implements ObjectiveSpec {
        public static final MapCodec<Visit> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("x").forGetter(Visit::x),
                Codec.INT.optionalFieldOf("y").forGetter(Visit::y),
                Codec.INT.fieldOf("z").forGetter(Visit::z),
                Codec.DOUBLE.optionalFieldOf("radius", 8.0D).forGetter(Visit::radius),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(Visit::dimension),
                Codec.STRING.optionalFieldOf("label").forGetter(Visit::label),
                Codec.intRange(0, 60).optionalFieldOf("settle", 0).forGetter(Visit::settle))
                .apply(i, Visit::new));

        @Override public MapCodec<Visit> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public int settleSeconds() { return settle; }
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
    public record FlagSet(String name, boolean value, boolean player, Optional<String> label) implements ObjectiveSpec {
        public FlagSet(String name, boolean value, boolean player) { this(name, value, player, Optional.empty()); }
        /** {@code label} is the whole line the journal shows ("Found your faction: /f create <name>"); unsaid, the flag's name, prettified. */
        public static final MapCodec<FlagSet> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(FlagSet::name),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(FlagSet::value),
                Codec.BOOL.optionalFieldOf("player", false).forGetter(FlagSet::player),
                Codec.STRING.optionalFieldOf("label").forGetter(FlagSet::label))
                .apply(i, FlagSet::new));
        @Override public MapCodec<FlagSet> codec() { return MAP_CODEC; }
        @Override public int required() { return 1; }
        @Override public String describe() { return label.orElseGet(() -> Lang.fmt("obj.flag", "flag", Lang.pretty(name))); }
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
        TYPES.register("deliver", Deliver.MAP_CODEC);
        TYPES.register("escort", Escort.MAP_CODEC);
    }

    /** Touch the class so the static block has run before a codec is asked for. */
    public static void init() {}

    private ObjectiveTypes() {}
}
