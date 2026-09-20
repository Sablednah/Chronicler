package com.sablednah.chronicler.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.ObjectiveTypes;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

/**
 * How many hits an escorted charge has left, always on screen -- the action-bar line on the moment
 * of a hit ({@code msg.escort.hit}) is easy to miss in a fight, and there was otherwise no way to
 * find out short of hitting them again and reading the next one. Recomputed each poll, same idea as
 * {@link Waypoints}: a bar for an escort that is no longer active (complete, failed, abandoned) is
 * simply not produced next time and removed, no separate cleanup path to keep in step.
 */
public final class EscortBars {

    /** {@code playerUUID|npcUUID}, since a quest can have more than one charge in danger at once (Mum AND Liz). */
    private static final Map<String, ServerBossEvent> BARS = new HashMap<>();

    public static void sync(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);
        String prefix = player.getUUID() + "|";
        Set<String> wanted = new HashSet<>();

        for (var e : log.activeView().entrySet()) {
            Identifier id = e.getKey();
            var holder = QuestEngine.quest(server, id);
            if (holder.isEmpty()) continue;
            Quest q = QuestEngine.questFor(player, id).orElse(holder.get().value());
            for (ObjectiveSpec spec : QuestEngine.currentObjectives(q, e.getValue())) {
                if (!(spec instanceof ObjectiveTypes.Escort esc) || esc.hits() <= 0) continue;
                String key = prefix + esc.who();
                wanted.add(key);
                int taken = e.getValue().tallies.getOrDefault("hits:" + esc.who(), 0);
                String whom = esc.name().orElseGet(() -> Givers.npcName(esc.who()));
                ServerBossEvent bar = BARS.computeIfAbsent(key, k -> {
                    ServerBossEvent b = new ServerBossEvent(Feedback.colored(""), BossEvent.BossBarColor.RED, BossEvent.BossBarOverlay.NOTCHED_10);
                    b.addPlayer(player);
                    return b;
                });
                bar.setName(Feedback.colored(Lang.fmt("escort.bar", "who", whom, "taken", taken, "hits", esc.hits())));
                bar.setProgress(Math.max(0F, 1F - (float) taken / esc.hits()));
            }
        }

        BARS.entrySet().removeIf(en -> {
            if (!en.getKey().startsWith(prefix) || wanted.contains(en.getKey())) return false;
            en.getValue().removePlayer(player);
            return true;
        });
    }

    /** How many bars exist right now, across every player -- the self-test's window onto {@link #BARS}. */
    public static int activeCount() { return BARS.size(); }

    /** A player gone for good (logout): nothing left to show them. */
    public static void forget(ServerPlayer player) {
        String prefix = player.getUUID() + "|";
        BARS.entrySet().removeIf(en -> {
            if (!en.getKey().startsWith(prefix)) return false;
            en.getValue().removePlayer(player);
            return true;
        });
    }

    private EscortBars() {}
}
