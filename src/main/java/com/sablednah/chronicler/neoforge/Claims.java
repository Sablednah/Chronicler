package com.sablednah.chronicler.neoforge;

import java.util.function.BiPredicate;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * "Is this somebody's land?" -- the claims bridge. False means nobody can say (no Standards, or no
 * claim provider registered with it) or nobody owns it; true means a group holds the chunk.
 * {@code compat/StandardsClaims} fills the slot from Standards' Claims seam (Factions ReForged and
 * anything else that registers there). Asked by a mini quest's {@code block} slot, so the wild never
 * picks the door of a player's own hut (Sable, offered the Rusted Door on his own front door, could
 * not open it).
 */
public final class Claims {

    private static volatile BiPredicate<ServerLevel, BlockPos> provider = (level, pos) -> false;
    private static volatile String providerName = "none";

    public static boolean claimed(ServerLevel level, BlockPos pos) {
        try {
            return provider.test(level, pos);
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static void install(String name, BiPredicate<ServerLevel, BlockPos> p) {
        provider = p;
        providerName = name;
    }

    /** The self-test stands in for a provider and puts the real one back. */
    public static BiPredicate<ServerLevel, BlockPos> current() { return provider; }

    public static String providerName() { return providerName; }

    private Claims() {}
}
