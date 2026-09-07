package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.data.Availability;

import net.minecraft.server.level.ServerPlayer;

/** Evaluate an {@link Availability} for a player: the lines that are NOT met, in prose. */
public final class Conditions {

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
