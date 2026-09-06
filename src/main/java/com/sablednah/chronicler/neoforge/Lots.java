package com.sablednah.chronicler.neoforge;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * "What kind of place is this?" -- the CityWorld bridge. Empty means nobody
 * can say (no CityWorld, or not a CityWorld level); a list of words means the
 * lot's context, class, interior, schematic and shop, for a {@code lot} match.
 * {@code compat/CityWorldLots} fills the slot.
 */
public final class Lots {

    private static volatile BiFunction<ServerLevel, BlockPos, Optional<List<String>>> provider =
            (level, pos) -> Optional.empty();

    public static Optional<List<String>> describe(ServerLevel level, BlockPos pos) {
        try {
            return provider.apply(level, pos);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void install(BiFunction<ServerLevel, BlockPos, Optional<List<String>>> p) {
        provider = p;
    }

    private Lots() {}
}
