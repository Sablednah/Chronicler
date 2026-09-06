package com.sablednah.chronicler.data;

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

    static {
        TYPES.register("position", Position.MAP_CODEC);
        TYPES.register("place", InPlace.MAP_CODEC);
    }

    public static void init() {}

    private GiverTypes() {}
}
