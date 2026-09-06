package com.sablednah.chronicler.data;

import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.neoforge.Lang;

import net.minecraft.resources.Identifier;

/** The built-in reward types. Records only; see {@link ObjectiveTypes}. */
public final class RewardTypes {

    public static final SpecTypes<RewardSpec> TYPES = new SpecTypes<>("reward", RewardSpec::codec);
    public static final Codec<RewardSpec> CODEC = TYPES.codec();

    /** {@code count} of {@code item}, into the inventory or dropped at the feet. */
    public record Item(Identifier item, int count) implements RewardSpec {
        public static final MapCodec<Item> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.fieldOf("item").forGetter(Item::item),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Item::count))
                .apply(i, Item::new));

        @Override public MapCodec<Item> codec() { return MAP_CODEC; }
        @Override public String describe() {
            return Lang.fmt("rew.item", "count", count, "item", Lang.pretty(item.getPath()));
        }
    }

    /**
     * Run a server command with {@code {player}} substituted. The universal
     * escape hatch -- and the one that lets a quest drive Standards' explicit
     * {@code /fly Steve on}, or a LegendQuest {@code /lq admin level add}.
     */
    public record Command(String command, boolean silent) implements RewardSpec {
        public static final MapCodec<Command> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("command").forGetter(Command::command),
                Codec.BOOL.optionalFieldOf("silent", true).forGetter(Command::silent))
                .apply(i, Command::new));

        @Override public MapCodec<Command> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.get("rew.command"); }
    }

    /** Vanilla experience points (not levels). */
    public record Xp(int amount) implements RewardSpec {
        public static final MapCodec<Xp> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("amount").forGetter(Xp::amount))
                .apply(i, Xp::new));

        @Override public MapCodec<Xp> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("rew.xp", "amount", amount); }
    }

    /** Money through Standards' economy facade; a clean no-op with no economy. */
    public record Money(double amount) implements RewardSpec {
        public static final MapCodec<Money> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.DOUBLE.fieldOf("amount").forGetter(Money::amount))
                .apply(i, Money::new));

        @Override public MapCodec<Money> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("rew.money", "amount", amount); }
    }

    /**
     * Move a standing with a named group. Lives in Standards' reputation seam;
     * without it the reward says so and the rest of the packet still lands.
     */
    public record Reputation(String standing, int delta) implements RewardSpec {
        public static final MapCodec<Reputation> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("standing").forGetter(Reputation::standing),
                Codec.INT.fieldOf("delta").forGetter(Reputation::delta))
                .apply(i, Reputation::new));

        @Override public MapCodec<Reputation> codec() { return MAP_CODEC; }
        @Override public String describe() {
            return Lang.fmt(delta >= 0 ? "rew.reputation_up" : "rew.reputation_down",
                    "amount", Math.abs(delta), "standing", Lang.pretty(standing));
        }
    }

    /** A title card. An effect more than a reward: the scene changes. */
    public record Title(String title, Optional<String> subtitle) implements RewardSpec {
        public static final MapCodec<Title> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("title").forGetter(Title::title),
                Codec.STRING.optionalFieldOf("subtitle").forGetter(Title::subtitle))
                .apply(i, Title::new));

        @Override public MapCodec<Title> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.get("rew.title"); }
    }

    /** A line of narration in chat, or on the action bar. */
    public record Message(String text, boolean actionBar) implements RewardSpec {
        public static final MapCodec<Message> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("text").forGetter(Message::text),
                Codec.BOOL.optionalFieldOf("action_bar", false).forGetter(Message::actionBar))
                .apply(i, Message::new));

        @Override public MapCodec<Message> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.get("rew.message"); }
    }

    /**
     * Put creatures in the world near the player: a vanilla entity id, or a
     * ZombieMod genus (which goes through {@code /zombiemod spawn}, so it needs
     * no import and quietly does nothing without ZombieMod).
     */
    public record Spawn(Optional<Identifier> entity, Optional<String> genus, int count, double radius)
            implements RewardSpec {
        public static final MapCodec<Spawn> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Identifier.CODEC.optionalFieldOf("entity").forGetter(Spawn::entity),
                Codec.STRING.optionalFieldOf("genus").forGetter(Spawn::genus),
                Codec.INT.optionalFieldOf("count", 1).forGetter(Spawn::count),
                Codec.DOUBLE.optionalFieldOf("radius", 8.0D).forGetter(Spawn::radius))
                .apply(i, Spawn::new));

        @Override public MapCodec<Spawn> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.get("rew.spawn"); }
    }

    /** LegendQuest karma. */
    public record Karma(long delta) implements RewardSpec {
        public static final MapCodec<Karma> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.fieldOf("delta").forGetter(Karma::delta)).apply(i, Karma::new));
        @Override public MapCodec<Karma> codec() { return MAP_CODEC; }
        @Override public String describe() {
            return Lang.fmt(delta >= 0 ? "rew.karma_up" : "rew.karma_down", "amount", Math.abs(delta));
        }
    }

    /** LegendQuest class XP, into the main class. */
    public record ClassXp(long amount) implements RewardSpec {
        public static final MapCodec<ClassXp> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.LONG.fieldOf("amount").forGetter(ClassXp::amount)).apply(i, ClassXp::new));
        @Override public MapCodec<ClassXp> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("rew.class_xp", "amount", amount); }
    }

    /** Whole LegendQuest levels. */
    public record Levels(int count) implements RewardSpec {
        public static final MapCodec<Levels> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("count").forGetter(Levels::count)).apply(i, Levels::new));
        @Override public MapCodec<Levels> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("rew.levels", "count", count); }
    }

    /** LegendQuest skill points. */
    public record SkillPoints(int count) implements RewardSpec {
        public static final MapCodec<SkillPoints> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.INT.fieldOf("count").forGetter(SkillPoints::count)).apply(i, SkillPoints::new));
        @Override public MapCodec<SkillPoints> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("rew.skill_points", "count", count); }
    }

    /** Set or clear a flag -- the world's, or the player's own. */
    public record Flag(String name, boolean value, boolean player) implements RewardSpec {
        public static final MapCodec<Flag> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Flag::name),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(Flag::value),
                Codec.BOOL.optionalFieldOf("player", false).forGetter(Flag::player))
                .apply(i, Flag::new));
        @Override public MapCodec<Flag> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.get(value ? "rew.flag_set" : "rew.flag_clear"); }
    }

    static {
        TYPES.register("item", Item.MAP_CODEC);
        TYPES.register("karma", Karma.MAP_CODEC);
        TYPES.register("class_xp", ClassXp.MAP_CODEC);
        TYPES.register("levels", Levels.MAP_CODEC);
        TYPES.register("skill_points", SkillPoints.MAP_CODEC);
        TYPES.register("flag", Flag.MAP_CODEC);
        TYPES.register("title", Title.MAP_CODEC);
        TYPES.register("message", Message.MAP_CODEC);
        TYPES.register("spawn", Spawn.MAP_CODEC);
        TYPES.register("reputation", Reputation.MAP_CODEC);
        TYPES.register("command", Command.MAP_CODEC);
        TYPES.register("xp", Xp.MAP_CODEC);
        TYPES.register("money", Money.MAP_CODEC);
    }

    public static void init() {}

    private RewardTypes() {}
}
