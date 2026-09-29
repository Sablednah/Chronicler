package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.network.HudPayload;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The quest tracker HUD's content, as plain data: the same quest names, objective wording and
 * counts the action bar and {@code /quest} already give, for a modded client to keep on the left of
 * the screen. Built here so every word still comes from {@code messages.yml}; the client draws it.
 *
 * <p>Sent only when it changes. A HUD is on screen the whole time, so resending an identical one
 * every second would be the one payload that costs something for nothing.</p>
 */
public final class Hud {

    /** What each player's client was last sent, so an unchanged HUD is not resent. */
    private static final Map<UUID, HudPayload> LAST = new ConcurrentHashMap<>();

    public static HudPayload build(ServerPlayer player) {
        QuestLog log = QuestEngine.journal(player);
        Identifier tracked = log.tracked().orElse(null);
        List<HudPayload.Quest> quests = new ArrayList<>();
        for (var e : log.activeView().entrySet()) {
            Identifier id = e.getKey();
            var found = QuestEngine.questFor(player, id);
            if (found.isEmpty()) continue;
            Quest quest = found.get();
            QuestLog.Entry entry = e.getValue();
            List<Component> lines = new ArrayList<>();
            List<ObjectiveSpec> objectives = QuestEngine.currentObjectives(quest, entry);
            for (int n = 0; n < objectives.size() && n < entry.targets.size(); n++) {
                String what = objectives.get(n).describe();
                int done = entry.progress.get(n), target = entry.targets.get(n);
                String key = entry.objectiveDone(n) ? "hud.objective_done" : target > 1 ? "hud.objective" : "hud.objective_single";
                lines.add(Feedback.colored(Lang.fmt(key, "objective", what, "done", Math.min(done, target), "target", target)));
            }
            if (entry.deadlineAt >= 0) {
                lines.add(Feedback.colored(Lang.fmt("hud.deadline",
                        "time", QuestEngine.clock(entry.deadlineAt - player.level().getGameTime()))));
            }
            HudPayload.Quest view = new HudPayload.Quest(id.toString(), Feedback.colored(Lang.fmt("hud.quest", "quest", quest.name())),
                    id.equals(tracked), lines);
            if (id.equals(tracked)) quests.addFirst(view); else quests.add(view);
        }
        int cap = com.sablednah.chronicler.ChroniclerConfig.HUD_MAX_QUESTS.get();
        Component more = Component.empty();
        if (quests.size() > cap) {
            more = Feedback.colored(Lang.fmt("hud.more", "count", quests.size() - cap));
            quests = new ArrayList<>(quests.subList(0, cap));
        }
        return new HudPayload(quests, more);
    }

    /** On the polled sweep: send this player's HUD if their client draws one and it has changed. */
    public static void sync(ServerPlayer player) {
        if (!Net.listening(player, HudPayload.TYPE)) return;
        HudPayload now = build(player);
        if (now.equals(LAST.get(player.getUUID()))) return;
        LAST.put(player.getUUID(), now);
        Net.sendIfAble(player, now);
    }

    public static void forget(UUID player) {
        LAST.remove(player);
    }

    private Hud() {}
}
