package com.sablednah.chronicler.neoforge;

import java.util.Optional;
import java.util.function.Predicate;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.transfer.ResourceHandler;
import net.neoforged.neoforge.transfer.access.ItemAccess;
import net.neoforged.neoforge.transfer.item.ItemResource;
import net.neoforged.neoforge.transfer.transaction.Transaction;

/**
 * What a player carries INSIDE things: a backpack (Sophisticated Backpacks), a shulker box, any item
 * that shares its contents through NeoForge's item capability ({@code Capabilities.Item.ITEM}, a
 * {@link ResourceHandler} of {@link ItemResource}). Nothing here names a backpack mod: the capability
 * is NeoForge's, present on every line this repo builds for, and whoever registers it is counted.
 *
 * <p>Looked in after the main inventory and the pockets, so a quest takes what is loose before it
 * opens a bag: containers carried in the inventory first, then containers worn in a pocket (a
 * backpack on the back, through {@link Pockets}; the worn stack is written back afterwards, since a
 * container's contents may live on the stack itself). One level deep: a bag in a bag is left alone.
 * A container that itself matches the objective (a quest for shulker boxes) is a stack already
 * counted, and is not opened.</p>
 *
 * <p>Sable, via ZARP (2026-10-24): "can quests see and use items inside backpacks?" -- a collect that
 * ignored a full backpack read as broken, and a consume that could not reach it stranded the quest.</p>
 */
public final class Containers {

    /** Matching items inside every container the player carries or wears. */
    public static int count(ServerPlayer player, Predicate<ItemStack> match) {
        long total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || match.test(s)) continue;
            total += count(handler(ItemAccess.forPlayerSlot(player, i)), match);
        }
        Optional<Pockets.Slots> worn = Pockets.slots(player);
        if (worn.isPresent()) {
            for (int i = 0; i < worn.get().size(); i++) {
                ItemStack s = worn.get().get(i);
                if (s.isEmpty() || match.test(s)) continue;
                total += count(handler(ItemAccess.forStack(s)), match);
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    /** Take up to {@code amount} matching items out of the containers; how many were taken. */
    public static int take(ServerPlayer player, Predicate<ItemStack> match, int amount) {
        if (amount <= 0) return 0;
        int taken = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize() && taken < amount; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty() || match.test(s)) continue;
            taken += take(handler(ItemAccess.forPlayerSlot(player, i)), match, amount - taken);
        }
        Optional<Pockets.Slots> worn = Pockets.slots(player);
        if (worn.isPresent()) {
            for (int i = 0; i < worn.get().size() && taken < amount; i++) {
                ItemStack s = worn.get().get(i);
                if (s.isEmpty() || match.test(s)) continue;
                int n = take(handler(ItemAccess.forStack(s)), match, amount - taken);
                if (n > 0) worn.get().set(i, s); // the contents may live on the stack: write it back where it was
                taken += n;
            }
        }
        return taken;
    }

    private static ResourceHandler<ItemResource> handler(ItemAccess access) {
        try {
            return access.getCapability(Capabilities.Item.ITEM);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static long count(ResourceHandler<ItemResource> h, Predicate<ItemStack> match) {
        if (h == null) return 0;
        long n = 0;
        for (int i = 0; i < h.size(); i++) {
            ItemResource r = h.getResource(i);
            if (r.isEmpty()) continue;
            if (match.test(r.toStack(1))) n += h.getAmountAsLong(i);
        }
        return n;
    }

    private static int take(ResourceHandler<ItemResource> h, Predicate<ItemStack> match, int amount) {
        if (h == null || amount <= 0) return 0;
        int taken = 0;
        try (Transaction tx = Transaction.openRoot()) {
            for (int i = 0; i < h.size() && taken < amount; i++) {
                ItemResource r = h.getResource(i);
                if (r.isEmpty() || !match.test(r.toStack(1))) continue;
                int want = (int) Math.min(amount - taken, h.getAmountAsLong(i));
                taken += h.extract(i, r, want, tx);
            }
            tx.commit();
        } catch (RuntimeException e) {
            return 0; // a handler that refuses mid-way takes nothing; the transaction is rolled back by close()
        }
        return taken;
    }

    private Containers() {}
}
