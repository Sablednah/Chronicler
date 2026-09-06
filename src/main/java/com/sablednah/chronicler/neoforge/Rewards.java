package com.sablednah.chronicler.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardSpec;
import com.sablednah.chronicler.data.RewardTypes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;

/**
 * How each reward type is <em>granted</em>. Same shape as {@link Trackers}:
 * data records say what, granters do it, and the registry is public so an
 * external reward type can register its own.
 *
 * <p>Every granter tells the player what landed, at the moment it lands. A
 * reward nobody was told about is indistinguishable from one that failed.</p>
 */
public final class Rewards {

    public interface Granter<R extends RewardSpec> {
        void grant(ServerPlayer player, R reward, Identifier questId, Quest quest);
    }

    private static final Map<Class<? extends RewardSpec>, Granter<?>> BY_CLASS = new LinkedHashMap<>();

    public static synchronized <R extends RewardSpec> void register(Class<R> type, Granter<R> granter) {
        BY_CLASS.put(type, granter);
    }

    @SuppressWarnings("unchecked")
    public static <R extends RewardSpec> void grant(ServerPlayer player, R reward, Identifier questId, Quest quest) {
        Granter<R> g;
        synchronized (Rewards.class) { g = (Granter<R>) BY_CLASS.get(reward.getClass()); }
        if (g == null) {
            Chronicler.LOGGER.warn("Chronicler: no granter for reward type {} on {} -- skipped",
                    reward.getClass().getSimpleName(), questId);
            return;
        }
        try {
            g.grant(player, reward, questId, quest);
        } catch (RuntimeException e) {
            // One bad reward must not cost the rest of the packet.
            Chronicler.LOGGER.error("Chronicler: reward {} on {} threw", reward.describe(), questId, e);
        }
    }

    static {
        register(RewardTypes.Item.class, (player, r, questId, quest) -> {
            var holder = BuiltInRegistries.ITEM.get(r.item());
            if (holder.isEmpty()) {
                Chronicler.LOGGER.warn("Chronicler: quest {} rewards unknown item {}", questId, r.item());
                Feedback.chat(player, Lang.fmt("msg.reward.unknown_item", "item", r.item()));
                return;
            }
            int left = r.count();
            while (left > 0) {
                int n = Math.min(left, holder.get().value().getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(holder.get(), n);
                player.getInventory().add(stack);
                if (!stack.isEmpty()) player.drop(stack, false); // a full pack drops it at the feet
                left -= n;
            }
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
        });

        register(RewardTypes.Xp.class, (player, r, questId, quest) -> {
            player.giveExperiencePoints(r.amount());
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
        });

        register(RewardTypes.Money.class, (player, r, questId, quest) -> {
            var paid = Money.pay(player, r.amount(), "chronicler:" + questId);
            if (paid.isPresent()) {
                Feedback.chat(player, Lang.fmt("msg.reward.given", "line",
                        Lang.fmt("rew.money_paid", "amount", paid.get())));
            } else {
                Feedback.chat(player, Lang.fmt("msg.reward.money_none", "amount", r.amount()));
            }
        });

        register(RewardTypes.Reputation.class, (player, r, questId, quest) -> {
            int before = Rep.get(player, r.standing());
            var landed = Rep.adjust(player, r.standing(), r.delta(), "chronicler:" + questId);
            if (landed.isEmpty()) {
                Feedback.chat(player, Lang.fmt("msg.reward.reputation_none", "line", r.describe()));
                return;
            }
            // The landed value, not the number in the file: "+10" when you were
            // already at the ceiling is a lie the player can check with /rep.
            int moved = landed.getAsInt() - before;
            String line = Lang.fmt(moved >= 0 ? "rew.reputation_up" : "rew.reputation_down",
                    "amount", Math.abs(moved), "standing", Lang.pretty(r.standing()));
            String band = Rep.band(r.standing(), landed.getAsInt())
                    .map(b -> Lang.fmt("msg.reward.reputation_band", "standing", Lang.pretty(r.standing()), "band", b))
                    .orElse("");
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", line + band));
        });

        register(RewardTypes.Command.class, (player, r, questId, quest) -> {
            String expanded = r.command()
                    .replace("{player}", player.getName().getString())
                    .replace("{uuid}", player.getUUID().toString())
                    .replace("{quest}", questId.toString());
            // Run AS the player with gamemaster permission, the way a LegendQuest
            // skill runs its commands: the quest is the authority, not the player's
            // own rank, so a reward may grant what the player could not ask for.
            var source = player.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            if (r.silent()) source = source.withSuppressedOutput();
            player.level().getServer().getCommands().performPrefixedCommand(source, expanded);
        });
    }

    public static void init() {}

    private Rewards() {}
}
