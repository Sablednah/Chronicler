package com.sablednah.chronicler.neoforge;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * Where else a player keeps things: the extra slots an accessory mod gives them (Curios, through
 * {@code compat/CuriosItems}). A quest item worn in a scarf slot, or a sample in a pocket slot, still
 * counts for {@code collect} and {@code deliver}, and is still taken when they consume. Without a
 * provider there are no pockets, and every answer is the main inventory's alone.
 *
 * <p>Taking goes main inventory first, then pockets: what is worn is what the player chose to keep
 * out of the way, so it goes last.</p>
 *
 * <p>{@link Slots} is deliberately ours, not NeoForge's item-handler type: that type was replaced on
 * 26.3, and a bridge that named it would need a shape per line. Three methods are all a pocket is.</p>
 */
public final class Pockets {

    /** The extra slots, read and written one at a time. */
    public interface Slots {
        int size();
        ItemStack get(int slot);
        void set(int slot, ItemStack stack);
    }

    public interface Provider {
        /** This player's extra slots; empty when they have none. */
        Optional<Slots> slots(ServerPlayer player);
    }

    private static volatile Provider provider;

    public static void install(Provider p) {
        provider = p;
    }

    /** The installed provider, for the self-test to put back after standing in for it. */
    public static Provider current() {
        return provider;
    }

    public static boolean available() {
        return provider != null;
    }

    /** This player's extra slots, for anyone who needs to look at what is worn (containers on a back). */
    public static Optional<Slots> slots(ServerPlayer player) {
        Provider p = provider;
        return p == null ? Optional.empty() : p.slots(player);
    }

    private static Optional<Slots> of(ServerPlayer player) {
        return slots(player);
    }

    /** How many matching items the pockets hold. */
    public static int count(ServerPlayer player, Predicate<ItemStack> match) {
        var h = of(player);
        if (h.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < h.get().size(); i++) {
            ItemStack s = h.get().get(i);
            if (!s.isEmpty() && match.test(s)) total += s.getCount();
        }
        return total;
    }

    /** Take up to {@code amount} matching items out of the pockets; how many were taken. */
    public static int take(ServerPlayer player, Predicate<ItemStack> match, int amount) {
        var h = of(player);
        if (h.isEmpty() || amount <= 0) return 0;
        int taken = 0;
        for (int i = 0; i < h.get().size() && taken < amount; i++) {
            ItemStack s = h.get().get(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int n = Math.min(s.getCount(), amount - taken);
            ItemStack left = s.copy();
            left.shrink(n);
            h.get().set(i, left);
            taken += n;
        }
        return taken;
    }

    private Pockets() {}
}
