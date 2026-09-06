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

    private Conditions() {}
}
