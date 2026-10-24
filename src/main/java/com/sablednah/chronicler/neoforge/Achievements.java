package com.sablednah.chronicler.neoforge;

import java.util.Locale;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.ChroniclerIds;

import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Granting vanilla advancements Chronicler generated (or any other, for a quest that names one
 * explicitly). The generation itself is {@code yaml/AchievementPack}, a datapack page like any
 * other; this class only knows the ids that page's ids are built from, and how to award one.
 */
public final class Achievements {

    public static final Identifier ROOT = ChroniclerIds.of("root");

    public static Identifier chapterId(Identifier chapter) {
        return Identifier.fromNamespaceAndPath(Chronicler.MODID, "chapter/" + chapter.getNamespace() + "/" + chapter.getPath());
    }

    public static Identifier endingId(Identifier chapter, String ending) {
        String slug = ending.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
        return Identifier.fromNamespaceAndPath(Chronicler.MODID,
                "ending/" + chapter.getNamespace() + "/" + chapter.getPath() + "/" + slug);
    }

    /** Award every criterion of the advancement, so a multi-criterion one lands whole. False if it does not exist. */
    public static boolean grant(MinecraftServer server, ServerPlayer player, Identifier id) {
        AdvancementHolder holder = server.getAdvancements().get(id);
        if (holder == null) return false;
        var progress = player.getAdvancements();
        for (String criterion : holder.value().criteria().keySet()) {
            progress.award(holder, criterion);
        }
        return true;
    }

    /** As {@link #grant}, but says so when it did not find one -- a config mistake, not a world-killer. */
    public static void grantOrWarn(ServerPlayer player, Identifier id, String context) {
        if (!achievementsOn()) return;
        if (!grant(player.level().getServer(), player, id)) {
            Chronicler.LOGGER.warn("Chronicler: no advancement {} to grant ({})", id, context);
        }
    }

    /**
     * Grant everything this player's journal already says they earned: the root once a journal exists,
     * each chapter whose counting quests are all done, each ending the journal records. Idempotent
     * (awarding a done criterion is a no-op), quiet about advancements that do not exist (a chapter from
     * a third-party datapack has none -- the known gap), and run on every login and after a /reload.
     *
     * <p>Why: a chapter's achievement was granted only at the instant its last quest completed. A
     * world that gained the achievements pack later (ZARP's save: "Found new data pack
     * chronicler_achievements, loading it automatically" on a world with two chapters already done)
     * never got those two, and nothing ever looked again. Returns how many it found to grant.</p>
     */
    public static int sweep(ServerPlayer player) {
        if (!achievementsOn()) return 0;
        MinecraftServer server = player.level().getServer();
        com.sablednah.chronicler.core.QuestLog log = QuestEngine.journal(player);
        int granted = 0;
        if (log.journalGiven() || log.completedCount() > 0) {
            if (grant(server, player, ROOT)) granted++;
        }
        for (var key : QuestEngine.chapters(server).registryKeySet()) {
            Identifier chapter = key.identifier();
            QuestEngine.Progress p = QuestEngine.progress(player, java.util.Optional.of(chapter));
            if (p.total() > 0 && p.done() >= p.total() && grant(server, player, chapterId(chapter))) granted++;
            for (String ending : log.endings(chapter)) {
                if (grant(server, player, endingId(chapter, ending))) granted++;
            }
        }
        return granted;
    }

    private static boolean achievementsOn() {
        try {
            return com.sablednah.chronicler.ChroniclerConfig.ACHIEVEMENTS.get();
        } catch (IllegalStateException e) {
            return true; // config not loaded yet: default on
        }
    }

    private Achievements() {}
}
