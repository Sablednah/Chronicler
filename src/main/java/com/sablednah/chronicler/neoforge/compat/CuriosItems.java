package com.sablednah.chronicler.neoforge.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Pockets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

/**
 * Curios' extra slots as {@link Pockets}. The ONLY class that names Curios -- and it names it by
 * string, through method handles, because Curios is not one of ours and only one Minecraft line on
 * the dev box has its jar. {@code CuriosApi.getCuriosInventory(LivingEntity)}, then
 * {@code ICuriosItemHandler.getEquippedCurios()}, then {@code getSlots / getStackInSlot /
 * setStackInSlot} on whatever that returns (NeoForge's item handler up to 26.2; resolved by name,
 * so a line where that type moved still binds if the names survived).
 *
 * <p>Every handle is resolved at register, inside {@code optionalIntegration}: a Curios whose API has
 * moved costs one log line and no pockets, never a throw at count time.</p>
 */
public final class CuriosItems {

    private static final String API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String HANDLER = "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";

    public static void register() {
        MethodHandle inventory, equipped, size, get, set;
        try {
            var lookup = MethodHandles.publicLookup();
            Class<?> api = Class.forName(API);
            Class<?> handler = Class.forName(HANDLER);
            inventory = lookup.findStatic(api, "getCuriosInventory", MethodType.methodType(Optional.class, LivingEntity.class));
            // getEquippedCurios' declared return type is whatever this line's Curios says; the slot calls are found on it by name.
            Class<?> stacks = handler.getMethod("getEquippedCurios").getReturnType();
            equipped = lookup.findVirtual(handler, "getEquippedCurios", MethodType.methodType(stacks));
            size = lookup.findVirtual(stacks, "getSlots", MethodType.methodType(int.class));
            get = lookup.findVirtual(stacks, "getStackInSlot", MethodType.methodType(ItemStack.class, int.class));
            set = lookup.findVirtual(stacks, "setStackInSlot", MethodType.methodType(void.class, int.class, ItemStack.class));
        } catch (ReflectiveOperationException e) {
            Chronicler.LOGGER.warn("Chronicler: Curios is installed but its API is not the shape this build knows -- quest items in Curios slots "
                    + "will not count. ({})", e.toString());
            return;
        }
        Pockets.install(player -> slots(inventory, equipped, size, get, set, player));
        Chronicler.LOGGER.info("Chronicler: Curios pockets wired -- items in its slots count for collect and deliver");
    }

    private static Optional<Pockets.Slots> slots(MethodHandle inventory, MethodHandle equipped, MethodHandle size, MethodHandle get, MethodHandle set,
            ServerPlayer player) {
        try {
            @SuppressWarnings("unchecked")
            Optional<Object> curios = (Optional<Object>) inventory.invoke(player);
            if (curios.isEmpty()) return Optional.empty();
            Object stacks = equipped.invoke(curios.get());
            if (stacks == null) return Optional.empty();
            return Optional.of(new Pockets.Slots() {
                @Override public int size() { return call(() -> (int) size.invoke(stacks), 0); }
                @Override public ItemStack get(int slot) { return call(() -> (ItemStack) get.invoke(stacks, slot), ItemStack.EMPTY); }
                @Override public void set(int slot, ItemStack stack) { call(() -> { set.invoke(stacks, slot, stack); return null; }, null); }
            });
        } catch (Throwable t) {
            Chronicler.LOGGER.warn("Chronicler: could not read {}'s Curios slots ({})", player.getName().getString(), t.toString());
            return Optional.empty();
        }
    }

    private interface Call<T> { T run() throws Throwable; }

    private static <T> T call(Call<T> c, T fallback) {
        try {
            return c.run();
        } catch (Throwable t) {
            Chronicler.LOGGER.warn("Chronicler: a Curios slot call failed ({})", t.toString());
            return fallback;
        }
    }

    private CuriosItems() {}
}
