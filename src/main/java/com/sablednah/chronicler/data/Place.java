package com.sablednah.chronicler.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.neoforge.Lang;

import net.minecraft.resources.Identifier;

/**
 * A kind of place, described by what the world already knows how to name --
 * never a coordinate. Every field is optional and every present field must
 * match. Used by the {@code place} objective ("go there") and the {@code place}
 * giver ("you are there, here is a quest"): one mechanism, two ends.
 *
 * @param biome     a biome id or {@code #tag}
 * @param structure a structure id or {@code #tag} (a stronghold, an outpost)
 * @param dimension a dimension id
 * @param lot       a CityWorld lot word ("hospital", "industrial"); needs CityWorld
 */
public record Place(Optional<String> biome, Optional<String> structure,
        Optional<Identifier> dimension, Optional<String> lot, List<Place> any) {

    /** The four conditions AND together; {@code any} is a list of alternatives, one of which must hold (each with its own four). */

    public static final MapCodec<Place> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.STRING.optionalFieldOf("biome").forGetter(Place::biome),
            Codec.STRING.optionalFieldOf("structure").forGetter(Place::structure),
            Identifier.CODEC.optionalFieldOf("dimension").forGetter(Place::dimension),
            Codec.STRING.optionalFieldOf("lot").forGetter(Place::lot),
            Codec.lazyInitialized(() -> Place.CODEC).listOf().optionalFieldOf("any", List.of()).forGetter(Place::any))
            .apply(i, Place::new));

    public static final Codec<Place> CODEC = MAP_CODEC.codec();

    public boolean isEmpty() {
        return biome.isEmpty() && structure.isEmpty() && dimension.isEmpty() && lot.isEmpty() && any.isEmpty();
    }

    /** "the Nether", "a village", "a hospital" -- from the fields, for prose. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        lot.ifPresent(l -> parts.add(Lang.fmt("place.lot", "lot", Lang.pretty(l))));
        structure.ifPresent(s -> parts.add(Lang.fmt("place.structure", "structure", Lang.pretty(s))));
        biome.ifPresent(b -> parts.add(Lang.fmt("place.biome", "biome", Lang.pretty(b))));
        dimension.ifPresent(d -> parts.add(Lang.fmt("place.dimension", "dimension", Lang.pretty(d.getPath()))));
        if (parts.isEmpty() && !any.isEmpty()) {
            List<String> alts = new ArrayList<>();
            for (Place p : any) alts.add(p.describe());
            return String.join(Lang.get("place.or"), alts);
        }
        return parts.isEmpty() ? Lang.get("place.anywhere") : String.join(Lang.get("place.join"), parts);
    }
}
