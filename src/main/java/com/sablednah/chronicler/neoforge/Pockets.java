package com.sablednah.chronicler.neoforge;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Where else a player keeps things: the extra slots an accessory mod gives them (Curios, through
 * {@code compat/CuriosItems}). A quest item worn in a scarf slot, or a sample in a pocket slot, still
 * counts for {@code collect} and {@code deliver}, and is still taken when they consume. Without a
 * provider there are no pockets, and every answer is the main inventory's alone.
 *
 * <p>Taking goes main inventory first, then pockets: what is worn is what the player chose to keep
 * out of the way, so it goes last.</p>
 */
public final class Pockets {

    public interface Provider {
        /** The extra slots, as one handler; empty when this player has none. */
        Optional<IItemHandlerModifiable> handler(ServerPlayer player);
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

    private static Optional<IItemHandlerModifiable> of(ServerPlayer player) {
        Provider p = provider;
        return p == null ? Optional.empty() : p.handler(player);
    }

    /** How many matching items the pockets hold. */
    public static int count(ServerPlayer player, Predicate<ItemStack> match) {
        var h = of(player);
        if (h.isEmpty()) return 0;
        int total = 0;
        for (int i = 0; i < h.get().getSlots(); i++) {
            ItemStack s = h.get().getStackInSlot(i);
            if (!s.isEmpty() && match.test(s)) total += s.getCount();
        }
        return total;
    }

    /** Take up to {@code amount} matching items out of the pockets; how many were taken. */
    public static int take(ServerPlayer player, Predicate<ItemStack> match, int amount) {
        var h = of(player);
        if (h.isEmpty() || amount <= 0) return 0;
        int taken = 0;
        for (int i = 0; i < h.get().getSlots() && taken < amount; i++) {
            ItemStack s = h.get().getStackInSlot(i);
            if (s.isEmpty() || !match.test(s)) continue;
            int n = Math.min(s.getCount(), amount - taken);
            ItemStack left = s.copy();
            left.shrink(n);
            h.get().setStackInSlot(i, left);
            taken += n;
        }
        return taken;
    }

    private Pockets() {}
}
