package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sablednah.chronicler.ChroniclerConfig;
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
                } else if (spec instanceof ObjectiveTypes.VisitGiver || spec instanceof ObjectiveTypes.Talk) {
                    Identifier giver = spec instanceof ObjectiveTypes.VisitGiver vg ? vg.giver() : ((ObjectiveTypes.Talk) spec).to();
                    Givers.whereGiven(server, giver).ifPresent(where -> marks.add(new Mark(id + ":target",
                            Lang.fmt("waypoint.target", "quest", questName, "what", spec.describe()),
                            (int) where.pos().x, (int) where.pos().y, (int) where.pos().z, where.level(), WaypointsPayload.TARGET)));
                } else if (spec instanceof ObjectiveTypes.Escort esc) {
                    Identifier dim = esc.dimension().orElse(here);
                    marks.add(new Mark(id + ":target", Lang.fmt("waypoint.target", "quest", questName, "what", esc.describe()),
                            esc.to().getX(), esc.to().getY(), esc.to().getZ(), dim, WaypointsPayload.TARGET));
                }
            }
            // Every person a mini quest has placed, for as long as it is active -- not only whichever
            // one the current beat's objective happens to name. Ed, met once at the start and never an
            // objective again, is exactly the case this covers: reported missing entirely from the map.
            for (var slotEntry : e.getValue().slots.entrySet()) {
                if (!slotEntry.getKey().endsWith(".id")) continue;
                String slotName = slotEntry.getKey().substring(0, slotEntry.getKey().length() - ".id".length());
                String label = e.getValue().slots.getOrDefault(slotName, slotName);
                liveNpc(server, slotEntry.getValue(), questName, label).ifPresent(marks::add);
            }
        }

        QuestEngine.quests(server).listElements().forEach(h -> {
            Identifier id = h.key().identifier();
            var at = Givers.positionOf(server, id); // a map lookup; most quests have no giver standing anywhere
            if (at.isEmpty()) return;
            if (!QuestEngine.visible(player, id, h.value())) return;
            if (JournalPanel.status(player, log, id, h.value()) != com.sablednah.chronicler.network.JournalPayload.AVAILABLE) return;
            at.ifPresent(where -> {
                String questName = Feedback.colored(h.value().name()).getString();
                var pos = where.pos();
                marks.add(new Mark(id + ":giver", Lang.fmt("waypoint.giver", "quest", questName),
                        (int) pos.x, (int) pos.y, (int) pos.z, where.level(), WaypointsPayload.GIVER));
            });
        });

        people(player, marks);
        return new WaypointsPayload(marks);
    }

    /**
     * The quests' people near this player, by the name over their heads, for a map to draw as a radar does -- a
     * phantom is no player JourneyMap's radar can find (it lists the tab, which Cast leaves), and a mob body's
     * name is off with the pack's villager and NPC names. Only Chronicler's own people (a lurker, a scene's
     * extra, anyone else Cast holds is not ours to point at), only within {@code tracker.mapPeopleRadius}, only
     * where the body is actually there, and not someone already marked as on a quest's errand.
     */
    private static void people(ServerPlayer player, List<Mark> marks) {
        double r = ChroniclerConfig.MAP_PEOPLE_RADIUS.get();
        if (r <= 0 || !Npcs.available()) return;
        MinecraftServer server = player.level().getServer();
        Identifier here = player.level().dimension().identifier();
        java.util.Set<String> marked = new java.util.HashSet<>();
        for (Mark m : marks) marked.add(m.id());
        QuestLog log = QuestEngine.journal(player);
        for (var e : GiverStore.get(server).people().entrySet()) {
            UUID id = e.getKey();
            if (marked.contains(id.toString())) continue;
            var placed = Npcs.provider().get().byId(server, id);
            if (placed.isEmpty() || !placed.get().dimension().equals(here)) continue;
            Npcs.Placed p = placed.get();
            if (player.position().distanceToSqr(p.pos()) > r * r) continue;
            if (!player.level().isLoaded(net.minecraft.core.BlockPos.containing(p.pos()))) continue;
            boolean offers = false;
            for (Identifier q : e.getValue()) {
                var h = QuestEngine.quest(server, q);
                if (h.isPresent() && QuestEngine.visible(player, q, h.get().value())
                        && JournalPanel.status(player, log, q, h.get().value()) == com.sablednah.chronicler.network.JournalPayload.AVAILABLE) {
                    offers = true;
                    break;
                }
            }
            marks.add(new Mark("person:" + id, Lang.fmt("waypoint.person", "name", Feedback.colored(p.name()).getString()),
                    (int) Math.floor(p.pos().x), (int) Math.floor(p.pos().y), (int) Math.floor(p.pos().z), p.dimension(),
                    offers ? WaypointsPayload.PERSON_OFFERS : WaypointsPayload.PERSON));
        }
    }

    /** A person the quest placed, wherever Cast says they are right now -- a mark that moves as they do. */
    private static java.util.Optional<Mark> liveNpc(MinecraftServer server, String who, String questName, String label) {
        if (!Npcs.available()) return java.util.Optional.empty();
        UUID id;
        try { id = UUID.fromString(who); } catch (IllegalArgumentException e) { return java.util.Optional.empty(); }
        return Npcs.provider().get().byId(server, id).map(p -> new Mark(p.id().toString(),
                Lang.fmt("waypoint.npc", "name", label, "quest", questName),
                (int) p.pos().x, (int) p.pos().y, (int) p.pos().z, p.dimension(), WaypointsPayload.NPC));
    }

    /** Sent once a second, same cadence as the polled objectives -- a marker is not journal text, it is not tied to the panel being open. */
    public static void sync(ServerPlayer player) {
        // Only when it changed, like the HUD: the same marks every second were a packet a second per player.
        WaypointsPayload now = build(player);
        if (now.equals(LAST.get(player.getUUID()))) return;
        LAST.put(player.getUUID(), now);
        Net.sendIfAble(player, now);
    }

    /** What each player was last sent. Session state: forgotten on logout (a rejoin is sent afresh) and on stop. */
    private static final java.util.Map<UUID, WaypointsPayload> LAST = new java.util.concurrent.ConcurrentHashMap<>();

    public static void forget(UUID player) { LAST.remove(player); }

    static void clearSession() { LAST.clear(); }

    static int sessionStateCount() { return LAST.size(); }

    private Waypoints() {}
}
