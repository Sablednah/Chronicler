package com.sablednah.chronicler.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.function.Predicate;

import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.ObjectiveTypes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * How each objective type is <em>measured</em>. The data records say what;
 * a tracker says how, and lives on the NeoForge side because measuring means
 * reading a level, an inventory or a death.
 *
 * <p>Keyed by spec class, with a public {@link #register} -- an external
 * objective type registers its record with {@link ObjectiveTypes} and its
 * tracker here. Two measurement styles: <b>polled</b> (recomputed on the
 * tracker tick -- inventory, position) and <b>event</b> (a counter the engine
 * bumps -- kills). A tracker may do both.</p>
 */
public final class Trackers {

    public interface Tracker<S extends ObjectiveSpec> {
        /** Recompute progress now; empty means "not a polled objective". */
        default OptionalInt poll(ServerPlayer player, S spec) { return OptionalInt.empty(); }

        /** Polled progress never goes down (a place, once reached, stays reached). */
        default boolean latching(S spec) { return false; }

        /** Does this kill count? */
        default boolean countsKill(ServerPlayer killer, LivingEntity victim, S spec) { return false; }

        /**
         * On completion, take up to {@code amount} of whatever was gathered from
         * this player; return how much was taken. Default takes nothing.
         */
        default int settle(ServerPlayer player, S spec, int amount) { return 0; }
    }

    private static final Map<Class<? extends ObjectiveSpec>, Tracker<?>> BY_CLASS = new LinkedHashMap<>();

    public static synchronized <S extends ObjectiveSpec> void register(Class<S> type, Tracker<S> tracker) {
        BY_CLASS.put(type, tracker);
    }

    @SuppressWarnings("unchecked")
    public static synchronized <S extends ObjectiveSpec> Tracker<S> of(S spec) {
        Tracker<?> t = BY_CLASS.get(spec.getClass());
        return (Tracker<S>) (t == null ? NONE : t);
    }

    private static final Tracker<ObjectiveSpec> NONE = new Tracker<>() {};

    // --- built-ins ---

    static {
        register(ObjectiveTypes.Kill.class, new Tracker<ObjectiveTypes.Kill>() {
            @Override
            public boolean countsKill(ServerPlayer killer, LivingEntity victim, ObjectiveTypes.Kill spec) {
                return matchesTarget(victim, spec.target());
            }
        });

        register(ObjectiveTypes.Collect.class, new Tracker<ObjectiveTypes.Collect>() {
            @Override
            public OptionalInt poll(ServerPlayer player, ObjectiveTypes.Collect spec) {
                return OptionalInt.of(count(player, spec.item()));
            }

            @Override
            public int settle(ServerPlayer player, ObjectiveTypes.Collect spec, int amount) {
                if (!spec.consume() || amount <= 0) return 0;
                Predicate<ItemStack> match = s -> !s.isEmpty() && spec.item().equals(BuiltInRegistries.ITEM.getKey(s.getItem()));
                return player.getInventory().clearOrCountMatchingItems(match, amount, player.inventoryMenu.getCraftSlots());
            }
        });

        register(ObjectiveTypes.Visit.class, new Tracker<ObjectiveTypes.Visit>() {
            @Override
            public OptionalInt poll(ServerPlayer player, ObjectiveTypes.Visit spec) {
                if (spec.dimension().isPresent()
                        && !spec.dimension().get().equals(player.level().dimension().identifier())) {
                    return OptionalInt.of(0);
                }
                double dx = player.getX() - (spec.x() + 0.5);
                double dz = player.getZ() - (spec.z() + 0.5);
                double dy = spec.y().map(y -> player.getY() - (y + 0.5)).orElse(0.0);
                double r = spec.radius();
                return OptionalInt.of(dx * dx + dy * dy + dz * dz <= r * r ? 1 : 0);
            }

            @Override
            public boolean latching(ObjectiveTypes.Visit spec) { return true; }
        });
    }

    static {
        register(ObjectiveTypes.Reputation.class, new Tracker<ObjectiveTypes.Reputation>() {
            @Override
            public OptionalInt poll(ServerPlayer player, ObjectiveTypes.Reputation spec) {
                return OptionalInt.of(Math.max(0, Rep.get(player, spec.standing())));
            }
        });
    }

    /** Items of this id across the main inventory (hotbar, storage, armour, offhand). */
    public static int count(ServerPlayer player, Identifier item) {
        int total = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty() && item.equals(BuiltInRegistries.ITEM.getKey(s.getItem()))) total += s.getCount();
        }
        return total;
    }

    /**
     * {@code any} (anything that is not a player), {@code #ns:tag}, an entity
     * type id -- or a ZombieMod genus id, read straight off the mob's
     * persistent data ({@code zombiemod:genus}) so "kill 3 Harvesters" works
     * with no dependency on ZombieMod at all.
     */
    public static boolean matchesTarget(LivingEntity victim, String target) {
        if ("any".equalsIgnoreCase(target)) return !(victim instanceof Player);
        if (target.startsWith("#")) {
            Identifier tag = Identifier.tryParse(target.substring(1));
            if (tag == null) return false;
            return BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(victim.getType())
                    .is(TagKey.create(Registries.ENTITY_TYPE, tag));
        }
        Identifier id = Identifier.tryParse(target);
        if (id == null) return false;
        if (id.equals(BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()))) return true;
        return victim.getPersistentData().getString("zombiemod:genus").map(target::equals).orElse(false);
    }

    /** Touch the class so the built-ins are registered before anything asks. */
    public static void init() {}

    private Trackers() {}
}
