package com.sablednah.chronicler.data;

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

    static {
        TYPES.register("item", Item.MAP_CODEC);
        TYPES.register("command", Command.MAP_CODEC);
        TYPES.register("xp", Xp.MAP_CODEC);
        TYPES.register("money", Money.MAP_CODEC);
    }

    public static void init() {}

    private RewardTypes() {}
}
