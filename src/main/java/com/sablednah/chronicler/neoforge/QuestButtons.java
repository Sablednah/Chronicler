package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * The actions that make sense for one player and one quest -- never one that
 * would be refused. Chat turns them into clickable text, the journal panel
 * into buttons; both run the same {@code /quest} command, so the two surfaces
 * cannot drift.
 */
public final class QuestButtons {

    /** {@code command} carries its slash, the way a chat click runs it. */
    public record Action(String label, String command, String tip) {
        public Component chat() {
            return Feedback.button(label, command, tip);
        }
    }

    public static List<Action> forQuest(ServerPlayer player, Identifier id, Quest q) {
        QuestLog log = QuestEngine.journal(player);
        List<Action> out = new ArrayList<>();
        if (log.isActive(id)) {
            if (!log.tracked().map(id::equals).orElse(false)) {
                out.add(new Action(Lang.get("button.track"), "/quest track " + id, Lang.get("button.track.tip")));
            }
            out.add(new Action(Lang.get("button.abandon"), "/quest abandon " + id, Lang.get("button.abandon.tip")));
        } else if (QuestEngine.available(player, id, q)) {
            out.add(new Action(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")));
            out.add(new Action(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
        }
        return out;
    }

    private QuestButtons() {}
}
