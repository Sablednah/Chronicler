package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.Stage;
import com.sablednah.chronicler.network.JournalPayload;
import com.sablednah.chronicler.network.JournalRequestPayload;

import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The journal as a panel, for a player whose client runs Chronicler's half:
 * chapters with their quests under them, a map of what leads to what, and a
 * page per quest. It is the book's content and {@code /quests}' rules, resolved
 * here and shipped whole -- the client draws and never decides.
 *
 * <p>Vanilla first still holds: the book is untouched and is what every other
 * client gets, and every button here runs the {@code /quest} command a chat
 * button would. {@code journal.panel = false} gives everyone the book.</p>
 */
public final class JournalPanel {

    // Collections first: a static final declared after the code that fills it is null when it runs.
    private static final Map<UUID, Long> LAST_REFRESH = new HashMap<>();

    /** The panel asks every two seconds while open; anything faster than this is dropped. */
    private static final int REFRESH_TICKS = 10;

    /** Every label the client draws that is not part of a chapter or quest. */
    private static final List<String> LABELS = List.of(
            "panel.back", "panel.close", "panel.empty", "panel.to_map", "panel.requires",
            "panel.map.hint", "panel.map.none",
            "panel.glyph.locked", "panel.glyph.available", "panel.glyph.active", "panel.glyph.complete",
            "panel.glyph.cooldown", "panel.glyph.tracked", "panel.glyph.open", "panel.glyph.closed",
            "status.locked", "status.available", "status.active", "status.complete");

    /** Panel rather than book for this player? */
    public static boolean wants(ServerPlayer player) {
        return ChroniclerConfig.JOURNAL_PANEL.get() && Net.listening(player, JournalPayload.TYPE);
    }

    public static void send(ServerPlayer player, boolean open, String focus) {
        Net.sendIfAble(player, build(player, open, focus));
    }

    /** What the client asked for, decided here. */
    public static void request(ServerPlayer player, int action, String arg) {
        switch (action) {
            case JournalRequestPayload.OPEN -> {
                if (wants(player)) send(player, true, arg);
                else Journal.openBook(player); // the key still opens something when the owner turned the panel off
            }
            case JournalRequestPayload.RUN -> {
                // Only the panel's own buttons, and only as the player: the same command they could type,
                // with the same permissions and the same answer in chat.
                if (!arg.startsWith("quest ")) return;
                player.level().getServer().getCommands().performPrefixedCommand(player.createCommandSourceStack(), arg);
                send(player, false, "");
            }
            default -> {
                long now = player.level().getGameTime();
                Long last = LAST_REFRESH.get(player.getUUID());
                if (last != null && now >= last && now - last < REFRESH_TICKS) return;
                LAST_REFRESH.put(player.getUUID(), now);
                send(player, false, "");
            }
        }
    }

    public static void forget(UUID player) {
        LAST_REFRESH.remove(player);
    }

    // --- building ---

    public static JournalPayload build(ServerPlayer player, boolean open, String focus) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);

        Map<String, Component> labels = new LinkedHashMap<>();
        for (String key : LABELS) labels.put(key, Feedback.colored(Lang.get(key)));

        Map<Identifier, Holder.Reference<Quest>> visible = new HashMap<>();
        Map<Identifier, List<Holder.Reference<Quest>>> byChapter = new TreeMap<>(QuestEngine.chapterOrder(server));
        QuestEngine.quests(server).listElements().forEach(h -> {
            if (!QuestEngine.visible(player, h.key().identifier(), h.value())) return;
            visible.put(h.key().identifier(), h);
            byChapter.computeIfAbsent(h.value().chapter(), k -> new ArrayList<>()).add(h);
        });

        List<JournalPayload.ChapterView> chapters = new ArrayList<>();
        for (var entry : byChapter.entrySet()) {
            Identifier chapterId = entry.getKey();
            var chapter = QuestEngine.chapters(server).get(ResourceKey.create(ChroniclerRegistries.CHAPTER, chapterId));
            List<Holder.Reference<Quest>> mine = entry.getValue();
            mine.sort(Comparator.comparingInt((Holder.Reference<Quest> h) -> h.value().order())
                    .thenComparing(h -> h.key().identifier().toString()));

            List<JournalPayload.QuestView> quests = new ArrayList<>();
            List<JournalPayload.External> externals = new ArrayList<>();
            Set<Identifier> external = new HashSet<>();
            for (var h : mine) {
                Identifier id = h.key().identifier();
                Quest q = h.value();
                List<String> requires = new ArrayList<>();
                for (Identifier r : q.requires()) {
                    var req = visible.get(r);
                    if (req == null) continue; // a prerequisite the player cannot see yet is not drawn
                    requires.add(r.toString());
                    if (!req.value().chapter().equals(chapterId) && external.add(r)) {
                        externals.add(new JournalPayload.External(r.toString(), Feedback.colored(req.value().name()),
                                Feedback.colored(Lang.fmt("panel.external", "chapter", chapterName(server, req.value().chapter()))),
                                status(player, log, r, req.value()), iconOf(server, req.value())));
                    }
                }
                byte status = status(player, log, id, q);
                boolean tracked = log.isActive(id) && log.tracked().map(id::equals).orElse(false);
                quests.add(new JournalPayload.QuestView(id.toString(), Feedback.colored(q.name()), status, iconOf(server, q),
                        tracked, requires, lines(player, log, id, q, status), buttons(player, log, id, q)));
            }

            List<String> text = new ArrayList<>();
            chapter.flatMap(c -> c.value().description()).ifPresent(d -> text.add(Lang.fmt("cmd.info.description", "description", d)));
            var progress = QuestEngine.progress(player, Optional.of(chapterId));
            if (progress.total() > 0) {
                text.add(Lang.fmt("panel.chapter.progress", "done", progress.done(), "total", progress.total(), "percent", progress.percent()));
            }
            int endings = QuestEngine.endingsOf(server, chapterId).size();
            if (endings > 0) text.add(Lang.fmt("panel.chapter.endings", "found", log.endings(chapterId).size(), "total", endings));
            List<JournalPayload.Button> chapterButtons = new ArrayList<>();
            boolean touched = mine.stream().anyMatch(h -> log.isActive(h.key().identifier()) || log.isComplete(h.key().identifier()));
            if (chapter.map(c -> c.value().replayable()).orElse(false) && touched) {
                chapterButtons.add(button(Lang.get("button.replay"), "/quest replay " + chapterId, Lang.get("button.replay.tip")));
            }
            chapters.add(new JournalPayload.ChapterView(chapterId.toString(), Feedback.colored(chapterName(server, chapterId)),
                    chapter.flatMap(c -> c.value().icon()).map(Identifier::toString).orElse(""),
                    text.stream().map(Feedback::colored).toList(), chapterButtons, quests, externals));
        }

        var overall = QuestEngine.progress(player, Optional.empty());
        Component progress = overall.total() > 0
                ? Feedback.colored(Lang.fmt("panel.progress", "percent", overall.percent()))
                : Component.empty();
        return new JournalPayload(open, focus == null ? "" : focus, Feedback.colored(Lang.get("panel.title")),
                progress, labels, chapters);
    }

    static byte status(ServerPlayer player, QuestLog log, Identifier id, Quest q) {
        if (log.isActive(id)) return JournalPayload.ACTIVE;
        if (log.isComplete(id) && !q.repeatable()) return JournalPayload.COMPLETE;
        if (q.repeatable() && QuestEngine.cooldownLeft(log, id, q) > 0) return JournalPayload.COOLDOWN;
        return QuestEngine.available(player, id, q) ? JournalPayload.AVAILABLE : JournalPayload.LOCKED;
    }

    private static String chapterName(MinecraftServer server, Identifier chapter) {
        return QuestEngine.chapters(server).get(ResourceKey.create(ChroniclerRegistries.CHAPTER, chapter))
                .map(h -> h.value().name()).orElse(chapter.toString());
    }

    /** The quest's own icon, else its chapter's, else nothing (the client draws a book). */
    private static String iconOf(MinecraftServer server, Quest q) {
        return q.icon().or(() -> QuestEngine.chapters(server).get(ResourceKey.create(ChroniclerRegistries.CHAPTER, q.chapter()))
                .flatMap(h -> h.value().icon())).map(Identifier::toString).orElse("");
    }

    /** A quest's page: the lines /quest info prints, for where this player stands. */
    private static List<Component> lines(ServerPlayer player, QuestLog log, Identifier id, Quest q, byte status) {
        MinecraftServer server = player.level().getServer();
        boolean party = QuestEngine.scopeOf(server, q) == QuestScope.PARTY;
        List<String> out = new ArrayList<>();

        String statusText = switch (status) {
            case JournalPayload.ACTIVE -> Lang.get("status.active");
            case JournalPayload.COMPLETE -> Lang.get("status.complete");
            case JournalPayload.COOLDOWN -> Lang.fmt("status.cooldown", "time", QuestEngine.clock(QuestEngine.cooldownLeft(log, id, q) / 50L));
            case JournalPayload.AVAILABLE -> Lang.get("status.available");
            default -> Lang.get("status.locked");
        };
        StringBuilder tags = new StringBuilder();
        if (q.repeatable()) tags.append(' ').append(Lang.get("status.repeatable"));
        if (party) tags.append(' ').append(Lang.get("status.party"));
        if (log.isActive(id) && log.tracked().map(id::equals).orElse(false)) tags.append(' ').append(Lang.get("status.tracked"));
        out.add(Lang.fmt("panel.quest.status", "status", statusText, "tags", tags));

        q.description().ifPresent(d -> out.add(Lang.fmt("cmd.info.description", "description", d)));
        if (party) out.add(Lang.get("cmd.info.scope_party"));
        q.giver().ifPresent(g -> out.add(Lang.fmt("cmd.info.giver", "where", Givers.describe(server, q))));
        if (status == JournalPayload.LOCKED) {
            q.locked().ifPresent(t -> out.add(Lang.fmt("panel.quest.locked", "text", t)));
            List<String> missing = new ArrayList<>();
            for (Identifier r : q.requires()) {
                if (!log.isComplete(r)) missing.add(QuestEngine.quest(server, r).map(h -> h.value().name()).orElse(r.toString()));
            }
            if (!missing.isEmpty()) {
                out.add(Lang.fmt("cmd.info.needs", "line", Lang.fmt("cond.requires", "quests", String.join(Lang.get("cond.list_join"), missing))));
            }
            QuestEngine.unmet(player, q).forEach(line -> out.add(Lang.fmt("cmd.info.needs", "line", line)));
        }

        QuestLog.Entry entry = log.entry(id);
        List<Stage> beats = q.beats();
        if (beats.size() > 1) {
            out.add(entry == null
                    ? Lang.fmt("cmd.info.stages", "count", beats.size())
                    : Lang.fmt("cmd.info.stage_now", "stage", entry.stage + 1, "stages", beats.size()));
        }
        Stage beat = entry == null ? beats.getFirst() : beats.get(Math.min(entry.stage, beats.size() - 1));
        if (entry != null) beat.text().ifPresent(t -> out.add(Lang.fmt("panel.quest.stage_text", "text", t)));
        if (entry != null && entry.deadlineAt >= 0) {
            out.add(Lang.fmt("panel.quest.deadline", "time", QuestEngine.clock(Math.max(0, entry.deadlineAt - player.level().getGameTime()))));
        }
        if (entry != null && beat.isDecision()) {
            out.add(Lang.get("panel.quest.decision"));
        } else {
            List<ObjectiveSpec> objectives = entry == null ? q.objectivesAt(0) : QuestEngine.currentObjectives(q, entry);
            out.add(Lang.get("cmd.info.objectives"));
            if (objectives.isEmpty()) out.add(Lang.get("cmd.info.none"));
            for (int n = 0; n < objectives.size(); n++) {
                String desc = objectives.get(n).describe();
                if (entry == null || n >= entry.targets.size()) {
                    out.add(Lang.fmt("cmd.info.objective", "line", desc));
                } else if (entry.objectiveDone(n)) {
                    out.add(Lang.fmt("panel.objective.done", "line", desc));
                } else {
                    out.add(Lang.fmt("cmd.info.progress", "line", desc, "done", entry.progress.get(n), "target", entry.targets.get(n)));
                }
            }
        }
        out.add(Lang.get("cmd.info.rewards"));
        if (q.rewards().isEmpty()) out.add(Lang.get("cmd.info.none"));
        q.rewards().forEach(r -> out.add(Lang.fmt("cmd.info.reward", "line", r.describe())));
        int times = log.completions(id);
        if (times > 1) out.add(Lang.fmt("panel.quest.times", "times", times));
        return out.stream().map(Feedback::colored).toList();
    }

    /** Choices first when a decision is waiting, then what chat would offer -- minus Info, which this page is. */
    private static List<JournalPayload.Button> buttons(ServerPlayer player, QuestLog log, Identifier id, Quest q) {
        List<JournalPayload.Button> out = new ArrayList<>();
        QuestLog.Entry entry = log.entry(id);
        if (entry != null) {
            Stage beat = q.beats().get(Math.min(entry.stage, q.beats().size() - 1));
            if (beat.isDecision()) {
                for (int n = 0; n < beat.choices().size(); n++) {
                    out.add(button(Lang.fmt("msg.choice.button", "label", beat.choices().get(n).label()),
                            "/quest choose " + id + " " + (n + 1), Lang.get("msg.choice.tip")));
                }
            }
        }
        for (QuestButtons.Action a : QuestButtons.forQuest(player, id, q)) {
            if (a.command().startsWith("/quest info ")) continue;
            out.add(button(a.label(), a.command(), a.tip()));
        }
        return out;
    }

    private static JournalPayload.Button button(String label, String command, String tip) {
        return new JournalPayload.Button(Feedback.colored(label), Feedback.colored(tip),
                command.startsWith("/") ? command.substring(1) : command);
    }

    private JournalPanel() {}
}
