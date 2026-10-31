package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.data.Availability;

import net.minecraft.server.level.ServerPlayer;

/** Evaluate an {@link Availability} for a player: the lines that are NOT met, in prose. */
public final class Conditions {

    /**
     * The gates a player cannot grow through: race, class, world flags and player flags, as they stand
     * now. Karma, level and reputation are left out -- "you'll grow into it" is a path, not a wall --
     * and so are {@code requires}, which are the story's own order. What {@code progress} asks before a
     * quest is counted toward a chapter or the story: Sable's ZARP run sat at 90% with four Survivors
     * quests shut to his race and class for good, and prestige is New Game+, not a plan.
     */
    public static boolean gatesClosed(ServerPlayer player, Availability a) {
        if (!a.race().isEmpty() || !a.clazz().isEmpty()) {
            if (!Sheet.available()) return true; // nobody can be the race or class a quest wants with no sheet to be it on
            if (!a.race().isEmpty()) {
                var race = Sheet.race(player);
                if (race.isEmpty() || a.race().stream().noneMatch(want -> idMatches(race.get(), want))) return true;
            }
            if (!a.clazz().isEmpty()) {
                var classes = Sheet.classes(player);
                if (classes.stream().noneMatch(c -> a.clazz().stream().anyMatch(want -> idMatches(c, want)))) return true;
            }
        }
        FlagStore world = FlagStore.get(player.level().getServer());
        for (var e : a.flags().entrySet()) if (world.is(e.getKey()) != e.getValue()) return true;
        var log = QuestEngine.journal(player);
        for (var e : a.playerFlags().entrySet()) if (log.hasFlag(e.getKey()) != e.getValue()) return true;
        return false;
    }

    /**
     * Does every gate hold? A variant's and a {@code when} effect's test, asked on every read of a quest
     * that has variants, so the flag-only case touches no Lang at all.
     */
    public static boolean met(ServerPlayer player, Availability a) {
        if (gatesClosed(player, a)) return false;
        if (a.karmaMin().isEmpty() && a.karmaMax().isEmpty() && a.levelMin().isEmpty() && a.levelMax().isEmpty() && a.reputation().isEmpty()) return true;
        return unmet(player, a).isEmpty();
    }

    /** The effects and rewards this player would be given: a {@code when} that does not hold for them is left out. */
    public static List<com.sablednah.chronicler.data.RewardSpec> shownTo(ServerPlayer player, List<com.sablednah.chronicler.data.RewardSpec> rewards) {
        List<com.sablednah.chronicler.data.RewardSpec> out = new ArrayList<>(rewards.size());
        for (var r : rewards) {
            if (player != null && r instanceof com.sablednah.chronicler.data.RewardTypes.When w && !met(player, w.when())) continue;
            out.add(r);
        }
        return out;
    }

    public static List<String> unmet(ServerPlayer player, Availability a) {
        List<String> out = new ArrayList<>();
        if (a.needsCharacter() && !Sheet.available()) {
            out.add(Lang.get("cond.no_character"));
        } else {
            long karma = Sheet.karma(player).orElse(0L);
            int level = Sheet.level(player).orElse(0);
            a.karmaMin().ifPresent(v -> { if (karma < v) out.add(Lang.fmt("cond.karma_min", "value", v)); });
            a.karmaMax().ifPresent(v -> { if (karma > v) out.add(Lang.fmt("cond.karma_max", "value", v)); });
            a.levelMin().ifPresent(v -> { if (level < v) out.add(Lang.fmt("cond.level_min", "value", v)); });
            a.levelMax().ifPresent(v -> { if (level > v) out.add(Lang.fmt("cond.level_max", "value", v)); });
            if (!a.race().isEmpty()) {
                var race = Sheet.race(player);
                if (race.isEmpty() || a.race().stream().noneMatch(want -> idMatches(race.get(), want))) {
                    out.add(Lang.fmt("cond.race", "race", prettyAny(a.race())));
                }
            }
            if (!a.clazz().isEmpty()) {
                var classes = Sheet.classes(player);
                if (classes.stream().noneMatch(c -> a.clazz().stream().anyMatch(want -> idMatches(c, want)))) {
                    out.add(Lang.fmt("cond.class", "class", prettyAny(a.clazz())));
                }
            }
        }
        FlagStore world = FlagStore.get(player.level().getServer());
        a.flags().forEach((name, want) -> {
            if (world.is(name) != want) out.add(Lang.fmt(want ? "cond.flag_on" : "cond.flag_off", "flag", Lang.pretty(name)));
        });
        var log = QuestEngine.journal(player);
        a.playerFlags().forEach((name, want) -> {
            if (log.hasFlag(name) != want) out.add(Lang.fmt(want ? "cond.player_flag_on" : "cond.player_flag_off", "flag", Lang.pretty(name)));
        });
        if (!a.reputation().isEmpty() && !Rep.available()) {
            out.add(Lang.get("cond.no_reputation"));
        } else {
            a.reputation().forEach((standing, min) -> {
                if (Rep.get(player, standing) < min) out.add(Lang.fmt("cond.reputation", "value", min, "standing", Lang.pretty(standing)));
            });
        }
        return out;
    }

    /** {@code lq_apoc:mechanic} must match whole; a bare {@code mechanic} matches any namespace. */
    static boolean idMatches(net.minecraft.resources.Identifier id, String want) {
        String w = want.trim().toLowerCase(java.util.Locale.ROOT);
        return w.contains(":") ? id.toString().equals(w) : id.getPath().equals(w);
    }

    private static String prettyAny(List<String> ids) {
        List<String> names = new ArrayList<>();
        for (String id : ids) names.add(Lang.pretty(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id));
        return String.join(Lang.get("cond.or"), names);
    }

    private Conditions() {}
}
