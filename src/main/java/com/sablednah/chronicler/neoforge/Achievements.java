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

    private static boolean achievementsOn() {
        try {
            return com.sablednah.chronicler.ChroniclerConfig.ACHIEVEMENTS.get();
        } catch (IllegalStateException e) {
            return true; // config not loaded yet: default on
        }
    }

    private Achievements() {}
}
