package com.sablednah.chronicler.api;

import java.util.Optional;

import com.sablednah.chronicler.data.GiverSpec;
import com.sablednah.chronicler.data.GiverTypes;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.ObjectiveTypes;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardSpec;
import com.sablednah.chronicler.data.RewardTypes;
import com.sablednah.chronicler.neoforge.Givers;
import com.sablednah.chronicler.neoforge.QuestEngine;

import com.mojang.serialization.MapCodec;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The door for other mods -- StoryTeller's NPCs first. Stable surface: these
 * wrap the engine and will not change shape without a version bump.
 *
 * <p>Import this package from a guarded class only; Chronicler is meant to be
 * a soft dependency for you the way its siblings are for it.</p>
 */
public final class Quests {

    /** Why an accept was refused. Mirrors the engine's. */
    public enum Refusal { UNKNOWN, ALREADY_ACTIVE, ALREADY_COMPLETE, LOCKED, CONDITIONS }

    /** Offer a quest to a player as if a giver had: action bar + chat with Accept/Info. Cooldown applies. */
    public static boolean offer(ServerPlayer player, Identifier quest, String where) {
        var holder = QuestEngine.quest(player.level().getServer(), quest);
        if (holder.isEmpty() || !QuestEngine.available(player, quest, holder.get().value())) return false;
        Givers.offer(player, quest, holder.get().value(), where);
        return true;
    }

    /** Accept on the player's behalf. Empty means accepted. */
    public static Optional<Refusal> accept(ServerPlayer player, Identifier quest) {
        return QuestEngine.accept(player, quest).map(r -> Refusal.valueOf(r.name()));
    }

    public static boolean isActive(ServerPlayer player, Identifier quest) {
        return QuestEngine.journal(player).isActive(quest);
    }

    public static boolean isComplete(ServerPlayer player, Identifier quest) {
        return QuestEngine.journal(player).isComplete(quest);
    }

    public static boolean isAvailable(ServerPlayer player, Identifier quest) {
        var holder = QuestEngine.quest(player.level().getServer(), quest);
        return holder.isPresent() && QuestEngine.available(player, quest, holder.get().value());
    }

    /** World flags: what a questline has changed. */
    public static boolean flag(ServerPlayer player, String name) {
        return com.sablednah.chronicler.neoforge.FlagStore.get(player.level().getServer()).is(name);
    }

    public static void setFlag(ServerPlayer player, String name, boolean value) {
        com.sablednah.chronicler.neoforge.FlagStore.get(player.level().getServer()).set(name, value);
    }

    public static Optional<Quest> quest(ServerPlayer player, Identifier quest) {
        return QuestEngine.quest(player.level().getServer(), quest).map(h -> h.value());
    }

    // --- registries: add your own kinds, during mod construction ---

    public static void registerGiverType(Identifier id, MapCodec<? extends GiverSpec> codec) {
        GiverTypes.TYPES.register(id, codec);
    }

    public static void registerObjectiveType(Identifier id, MapCodec<? extends ObjectiveSpec> codec) {
        ObjectiveTypes.TYPES.register(id, codec);
    }

    public static void registerRewardType(Identifier id, MapCodec<? extends RewardSpec> codec) {
        RewardTypes.TYPES.register(id, codec);
    }

    private Quests() {}
}
