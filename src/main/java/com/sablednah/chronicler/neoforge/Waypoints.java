package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.ObjectiveTypes;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.network.WaypointsPayload;
import com.sablednah.chronicler.network.WaypointsPayload.Mark;

import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where a player's quests are, as plain data -- a "go here" coordinate, a
 * giver waiting to be found, an escorted NPC's current spot. Nothing here
 * knows JourneyMap exists; that is the point (see {@code client/compat}).
 * This is the exact same data a page or the action bar already tells the
 * player in words, offered again for whatever draws a map.
 */
public final class Waypoints {

    public static WaypointsPayload build(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);
        Identifier here = player.level().dimension().identifier();
        List<Mark> marks = new ArrayList<>();

        for (var e : log.activeView().entrySet()) {
            Identifier id = e.getKey();
            var holder = QuestEngine.quest(server, id);
            if (holder.isEmpty()) continue;
            Quest q = QuestEngine.questFor(player, id).orElse(holder.get().value());
            String questName = Feedback.colored(q.name()).getString();
            for (ObjectiveSpec spec : QuestEngine.currentObjectives(q, e.getValue())) {
                if (spec instanceof ObjectiveTypes.Visit v) {
                    Identifier dim = v.dimension().orElse(here);
                    marks.add(new Mark(id + ":target", Lang.fmt("waypoint.target", "quest", questName, "what", v.describe()),
                            v.x(), v.y().orElse(player.getBlockY()), v.z(), dim, WaypointsPayload.TARGET));
                } else if (spec instanceof ObjectiveTypes.Escort esc) {
                    Identifier dim = esc.dimension().orElse(here);
                    marks.add(new Mark(id + ":target", Lang.fmt("waypoint.target", "quest", questName, "what", esc.describe()),
                            esc.to().getX(), esc.to().getY(), esc.to().getZ(), dim, WaypointsPayload.TARGET));
                    liveNpc(server, esc.who(), questName).ifPresent(marks::add);
                }
            }
        }

        QuestEngine.quests(server).listElements().forEach(h -> {
            Identifier id = h.key().identifier();
            if (!QuestEngine.visible(player, id, h.value())) return;
            if (JournalPanel.status(player, log, id, h.value()) != com.sablednah.chronicler.network.JournalPayload.AVAILABLE) return;
            Givers.positionOf(server, id).ifPresent(where -> {
                String questName = Feedback.colored(h.value().name()).getString();
                var pos = where.pos();
                marks.add(new Mark(id + ":giver", Lang.fmt("waypoint.giver", "quest", questName),
                        (int) pos.x, (int) pos.y, (int) pos.z, where.level(), WaypointsPayload.GIVER));
            });
        });

        return new WaypointsPayload(marks);
    }

    /** An escort's charge, wherever Cast says they are right now -- a mark that moves as they do. */
    private static java.util.Optional<Mark> liveNpc(MinecraftServer server, String who, String questName) {
        if (!Npcs.available()) return java.util.Optional.empty();
        UUID id;
        try { id = UUID.fromString(who); } catch (IllegalArgumentException e) { return java.util.Optional.empty(); }
        return Npcs.provider().get().byId(server, id).map(p -> new Mark(p.id().toString(),
                Lang.fmt("waypoint.npc", "name", p.name(), "quest", questName),
                (int) p.pos().x, (int) p.pos().y, (int) p.pos().z, p.dimension(), WaypointsPayload.NPC));
    }

    /** Sent once a second, same cadence as the polled objectives -- a marker is not journal text, it is not tied to the panel being open. */
    public static void sync(ServerPlayer player) {
        Net.sendIfAble(player, build(player));
    }

    private Waypoints() {}
}
