package com.sablednah.chronicler.neoforge.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Pockets;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

/**
 * Curios' extra slots as {@link Pockets}. The ONLY class that names Curios -- and it names it by
 * string, through method handles, because Curios is not one of ours and only one Minecraft line on
 * the dev box has its jar: two calls ({@code CuriosApi.getCuriosInventory(LivingEntity)}, then
 * {@code ICuriosItemHandler.getEquippedCurios()}) hand back NeoForge's own
 * {@link IItemHandlerModifiable}, and from there nothing Curios-shaped is touched.
 *
 * <p>Wired through {@code optionalIntegration}; a Curios whose API has moved fails here, at register,
 * with a log line and no pockets -- never at count time.</p>
 */
public final class CuriosItems {

    private static final String API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String HANDLER = "top.theillusivec4.curios.api.type.capability.ICuriosItemHandler";

    public static void register() {
        MethodHandle inventory, equipped;
        try {
            var lookup = MethodHandles.publicLookup();
            Class<?> api = Class.forName(API);
            Class<?> handler = Class.forName(HANDLER);
            inventory = lookup.findStatic(api, "getCuriosInventory", MethodType.methodType(Optional.class, LivingEntity.class));
            equipped = lookup.findVirtual(handler, "getEquippedCurios", MethodType.methodType(IItemHandlerModifiable.class));
        } catch (ReflectiveOperationException e) {
            Chronicler.LOGGER.warn("Chronicler: Curios is installed but its API is not the shape this build knows -- quest items in Curios slots "
                    + "will not count. ({})", e.toString());
            return;
        }
        Pockets.install(player -> handler(inventory, equipped, player));
    }

    private static Optional<IItemHandlerModifiable> handler(MethodHandle inventory, MethodHandle equipped, ServerPlayer player) {
        try {
            @SuppressWarnings("unchecked")
            Optional<Object> curios = (Optional<Object>) inventory.invoke(player);
            if (curios.isEmpty()) return Optional.empty();
            return Optional.ofNullable((IItemHandlerModifiable) equipped.invoke(curios.get()));
        } catch (Throwable t) {
            Chronicler.LOGGER.warn("Chronicler: could not read {}'s Curios slots ({})", player.getName().getString(), t.toString());
            return Optional.empty();
        }
    }

    private CuriosItems() {}
}
