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
    public enum Refusal { UNKNOWN, ALREADY_ACTIVE, ALREADY_COMPLETE, LOCKED, CONDITIONS, COOLDOWN, UNRESOLVED }

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

    /**
     * The player's own flags: the store a {@code {"type": "flag", "player": true}} reward writes and
     * objective reads, names trimmed and lower-cased the same way. Set one from your mod (you founded
     * a faction, you claimed the hut) and a quest waiting on it completes on its next check. A flag
     * set here belongs to no chapter, so replaying a chapter does not take it back. Since 1.3.0.
     */
    public static boolean playerFlag(ServerPlayer player, String name) {
        return com.sablednah.chronicler.neoforge.QuestEngine.journal(player).hasFlag(name);
    }

    public static void setPlayerFlag(ServerPlayer player, String name, boolean value) {
        com.sablednah.chronicler.neoforge.QuestEngine.journal(player).setFlag(name, value);
    }

    public static Optional<Quest> quest(ServerPlayer player, Identifier quest) {
        return QuestEngine.questFor(player, quest);
    }

    // --- mini quests: small errands filled in where they start ---

    /** Every mini quest template loaded. */
    public static java.util.List<Identifier> minis(net.minecraft.server.MinecraftServer server) {
        return com.sablednah.chronicler.neoforge.Minis.templates(server);
    }

    public static boolean isMini(ServerPlayer player, Identifier quest) {
        return QuestEngine.quest(player.level().getServer(), quest).map(h -> h.value().mini().isPresent()).orElse(false);
    }

    /**
     * Start mini quest {@code template} for a player now, filled where they stand. {@code slots} hands
     * over values by slot name: a place as "x y z" or "dimension x y z", a person as a Cast NPC id
     * (your own NPC becomes the one who asks), a pick or a number as text. Empty means started;
     * otherwise the reason, which the player has already been told.
     */
    public static Optional<String> startMini(ServerPlayer player, Identifier template, java.util.Map<String, String> slots) {
        return com.sablednah.chronicler.neoforge.Minis.start(player, template, slots,
                com.sablednah.chronicler.neoforge.Minis.Anchor.of(player), false);
    }

    /** As {@link #startMini}, anchored somewhere else: its {@code here} and its searches start at {@code near}. */
    public static Optional<String> startMini(ServerPlayer player, Identifier template, java.util.Map<String, String> slots,
            net.minecraft.server.level.ServerLevel level, net.minecraft.core.BlockPos near) {
        return com.sablednah.chronicler.neoforge.Minis.start(player, template, slots,
                new com.sablednah.chronicler.neoforge.Minis.Anchor(level, near), false);
    }

    /** Put it to the player with Accept and Info instead; it stands for {@code minis.offerSeconds}. */
    public static Optional<String> offerMini(ServerPlayer player, Identifier template, java.util.Map<String, String> slots) {
        return com.sablednah.chronicler.neoforge.Minis.start(player, template, slots,
                com.sablednah.chronicler.neoforge.Minis.Anchor.of(player), true);
    }

    // --- registries: add your own kinds, during mod construction ---

    /**
     * Your mod ships a built-in datapack with Chronicler chapters in it (say {@code "/datapacks/zarp"},
     * from the jar root): give its chapters and endings the generated achievements Chronicler's own
     * packs get. {@code anchor} is any class of yours, so the path resolves in YOUR jar even when
     * another carries the same one. Call during mod construction or common setup, and only when you
     * register the pack. Since 1.2.0.
     */
    public static void registerAchievementSource(Class<?> anchor, String jarRootPath) {
        com.sablednah.chronicler.yaml.AchievementPack.registerSource(anchor, jarRootPath);
    }

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
