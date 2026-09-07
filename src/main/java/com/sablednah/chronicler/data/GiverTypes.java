package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.neoforge.Lang;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;

/** The built-in giver kinds. Records only; {@code neoforge/Givers} does the work. */
public final class GiverTypes {

    public static final SpecTypes<GiverSpec> TYPES = new SpecTypes<>("giver", GiverSpec::codec);
    public static final Codec<GiverSpec> CODEC = TYPES.codec();

    /**
     * A block in the world -- a lectern, a sign, a chest, a door. Standing
     * within {@code radius} hears the offer; right-clicking the block accepts.
     * {@code label} is what the player is told ("Dr Okafor's desk").
     */
    public record Position(BlockPos at, Optional<Identifier> dimension, double radius, Optional<String> label)
            implements GiverSpec {
        public static final MapCodec<Position> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                BlockPos.CODEC.fieldOf("at").forGetter(Position::at),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(Position::dimension),
                Codec.DOUBLE.optionalFieldOf("radius", 4.0D).forGetter(Position::radius),
                Codec.STRING.optionalFieldOf("label").forGetter(Position::label))
                .apply(i, Position::new));

        @Override public MapCodec<Position> codec() { return MAP_CODEC; }
        @Override public String describe() {
            return label.map(l -> Lang.fmt("giver.position_label", "label", l))
                    .orElseGet(() -> Lang.fmt("giver.position", "x", at.getX(), "y", at.getY(), "z", at.getZ()));
        }
    }

    /** Ambient: being in a kind of place offers it. No coordinates, portable across seeds. */
    public record InPlace(Place place, Optional<String> label) implements GiverSpec {
        public static final MapCodec<InPlace> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Place.MAP_CODEC.forGetter(InPlace::place),
                Codec.STRING.optionalFieldOf("label").forGetter(InPlace::label))
                .apply(i, InPlace::new));

        @Override public MapCodec<InPlace> codec() { return MAP_CODEC; }
        @Override public String describe() {
            return label.map(l -> Lang.fmt("giver.place_label", "label", l))
                    .orElseGet(() -> Lang.fmt("giver.place", "place", place.describe()));
        }
    }

    /**
     * A person: a Cast NPC placed from this data the first time the server
     * starts with the quest loaded, standing at {@code at}. {@code skin} names
     * a real account (human only); {@code entity} makes it a creature instead.
     * Without Cast the quest still loads and lists; nobody is placed, and the
     * offer line says so.
     */
    public record NpcGiver(String name, Optional<String> skin, Optional<Identifier> entity,
            Optional<BlockPos> at, Optional<List<Integer>> nearSpawn, Optional<Identifier> dimension, float yaw,
            Optional<String> greeting, Optional<Identifier> of) implements GiverSpec {
        /**
         * {@code at} is a fixed block; {@code near_spawn: [dx, dz]} is an offset
         * from the world spawn, dropped onto the surface the first time the
         * server places it -- so a shipped questline can stand its camp on any
         * seed. {@code of} names another quest whose NPC also gives this one,
         * so one person can hand out a whole storyline. One of the three is required.
         */
        public static final MapCodec<NpcGiver> MAP_CODEC = RecordCodecBuilder.<NpcGiver>mapCodec(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(NpcGiver::name),
                Codec.STRING.optionalFieldOf("skin").forGetter(NpcGiver::skin),
                Identifier.CODEC.optionalFieldOf("entity").forGetter(NpcGiver::entity),
                BlockPos.CODEC.optionalFieldOf("at").forGetter(NpcGiver::at),
                Codec.INT.listOf(2, 2).optionalFieldOf("near_spawn").forGetter(NpcGiver::nearSpawn),
                Identifier.CODEC.optionalFieldOf("dimension").forGetter(NpcGiver::dimension),
                Codec.FLOAT.optionalFieldOf("yaw", 0F).forGetter(NpcGiver::yaw),
                Codec.STRING.optionalFieldOf("greeting").forGetter(NpcGiver::greeting),
                ChroniclerIds.CODEC.optionalFieldOf("of").forGetter(NpcGiver::of))
                .apply(i, NpcGiver::new)).validate(n -> n.at().isEmpty() && n.nearSpawn().isEmpty() && n.of().isEmpty()
                        ? com.mojang.serialization.DataResult.error(() -> "an npc giver needs 'at' or 'near_spawn'")
                        : com.mojang.serialization.DataResult.success(n));

        @Override public MapCodec<NpcGiver> codec() { return MAP_CODEC; }
        @Override public String describe() { return Lang.fmt("giver.npc", "name", name); }
    }

    static {
        TYPES.register("position", Position.MAP_CODEC);
        TYPES.register("npc", NpcGiver.MAP_CODEC);
        TYPES.register("place", InPlace.MAP_CODEC);
    }

    public static void init() {}

    private GiverTypes() {}
}
