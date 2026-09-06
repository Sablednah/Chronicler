package com.sablednah.chronicler.neoforge;

import java.util.Locale;
import java.util.Optional;

import com.sablednah.chronicler.data.Place;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.levelgen.structure.Structure;

/** Is the player in a {@link Place}? Reads the level; every present field must agree. */
public final class Places {

    public static boolean isAt(ServerPlayer player, Place place) {
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();
        if (place.dimension().isPresent() && !place.dimension().get().equals(level.dimension().identifier())) {
            return false;
        }
        if (place.biome().isPresent() && !biomeMatches(level.getBiome(pos), place.biome().get())) {
            return false;
        }
        if (place.structure().isPresent() && !structureMatches(level, pos, place.structure().get())) {
            return false;
        }
        if (place.lot().isPresent()) {
            Optional<java.util.List<String>> tokens = Lots.describe(level, pos);
            if (tokens.isEmpty()) return false; // no CityWorld, or not a CityWorld level
            String want = place.lot().get().toLowerCase(Locale.ROOT);
            if (tokens.get().stream().noneMatch(t -> t.toLowerCase(Locale.ROOT).contains(want))) return false;
        }
        return true;
    }

    private static boolean biomeMatches(Holder<Biome> biome, String want) {
        if (want.startsWith("#")) {
            Identifier tag = Identifier.tryParse(want.substring(1));
            return tag != null && biome.is(TagKey.create(Registries.BIOME, tag));
        }
        Identifier id = Identifier.tryParse(want);
        return id != null && biome.is(ResourceKey.create(Registries.BIOME, id));
    }

    private static boolean structureMatches(ServerLevel level, BlockPos pos, String want) {
        java.util.function.Predicate<Holder<Structure>> test;
        if (want.startsWith("#")) {
            Identifier tag = Identifier.tryParse(want.substring(1));
            if (tag == null) return false;
            TagKey<Structure> key = TagKey.create(Registries.STRUCTURE, tag);
            test = h -> h.is(key);
        } else {
            Identifier id = Identifier.tryParse(want);
            if (id == null) return false;
            ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE, id);
            test = h -> h.is(key);
        }
        return level.structureManager().getStructureWithPieceAt(pos, test).isValid();
    }

    private Places() {}
}
