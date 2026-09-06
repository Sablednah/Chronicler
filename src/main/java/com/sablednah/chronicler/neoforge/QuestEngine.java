package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.Chapter;
import com.sablednah.chronicler.data.ObjectiveSpec;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardSpec;
import com.sablednah.chronicler.data.Stage;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * The engine: accept, measure, advance, complete, reward. Everything here is
 * the <em>rule</em>; the commands, events, journal and API are thin callers,
 * so the self-test and a StoryTeller NPC drive exactly the code a player does.
 *
 * <h2>Stages</h2>
 *
 * <p>A quest is a list of beats ({@link Quest#beats()}); a plain quest is one
 * beat. The journal entry records which beat the player is on and holds
 * counters for that beat only. Finishing a beat fires its {@code on_complete}
 * effects, then either enters the next (narration, {@code on_enter}, fresh
 * counters) or completes the quest (rewards).</p>
 *
 * <h2>Party quests</h2>
 *
 * <p>One quest for the whole party: every member's journal holds the same
 * entry (targets scaled by party size at acceptance), and every write goes to
 * every member. Membership comes from {@link Party}, which answers "just you"
 * without Standards, so a party quest quietly becomes a solo one there.</p>
 */
public final class QuestEngine {

    public enum Refusal { UNKNOWN, ALREADY_ACTIVE, ALREADY_COMPLETE, LOCKED, CONDITIONS, COOLDOWN }

    // --- lookups ---

    public static Registry<Quest> quests(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
    }

    public static Registry<Chapter> chapters(MinecraftServer server) {
        return server.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
    }

    public static Optional<Holder.Reference<Quest>> quest(MinecraftServer server, Identifier id) {
        return quests(server).get(ResourceKey.create(ChroniclerRegistries.QUEST, id));
    }

    public static QuestScope scopeOf(MinecraftServer server, Quest quest) {
        return quest.scope().orElseGet(() -> chapters(server)
                .get(ResourceKey.create(ChroniclerRegistries.CHAPTER, quest.chapter()))
                .map(h -> h.value().scope()).orElse(QuestScope.SOLO));
    }

    public static QuestLog journal(ServerPlayer player) {
        return player.getData(ChroniclerAttachments.JOURNAL);
    }

    private static List<ServerPlayer> sharers(ServerPlayer player, Quest quest) {
        return scopeOf(player.level().getServer(), quest) == QuestScope.PARTY
                ? Party.members(player) : List.of(player);
    }

    /** The objectives the player is currently working on. */
    public static List<ObjectiveSpec> currentObjectives(Quest quest, QuestLog.Entry entry) {
        return quest.objectivesAt(entry.stage);
    }

    private static List<Integer> targetsFor(List<ObjectiveSpec> objectives, int factor) {
        List<Integer> targets = new ArrayList<>();
        for (ObjectiveSpec o : objectives) targets.add(Math.max(1, o.required() * factor));
        return targets;
    }

    // --- availability ---

    public static Optional<Refusal> refusal(ServerPlayer player, Identifier id, Quest quest) {
        QuestLog log = journal(player);
        if (log.isActive(id)) return Optional.of(Refusal.ALREADY_ACTIVE);
        if (log.isComplete(id) && !quest.repeatable()) return Optional.of(Refusal.ALREADY_COMPLETE);
        if (quest.repeatable() && quest.cooldown() > 0 && cooldownLeft(log, id, quest) > 0) return Optional.of(Refusal.COOLDOWN);
        for (Identifier r : quest.requires()) {
            if (!log.isComplete(r)) return Optional.of(Refusal.LOCKED);
        }
        if (quest.availability().isPresent() && !Conditions.unmet(player, quest.availability().get()).isEmpty()) {
            return Optional.of(Refusal.CONDITIONS);
        }
        return Optional.empty();
    }

    /** Millis until a repeatable may be taken again; 0 when it may. */
    public static long cooldownLeft(QuestLog log, Identifier id, Quest quest) {
        long at = log.completedAt(id);
        if (at <= 0 || quest.cooldown() <= 0) return 0;
        return Math.max(0, at + quest.cooldown() * 1000L - System.currentTimeMillis());
    }

    /** The availability lines a player does not meet, for the refusal message. */
    public static List<String> unmet(ServerPlayer player, Quest quest) {
        return quest.availability().map(a -> Conditions.unmet(player, a)).orElse(List.of());
    }

    public static boolean available(ServerPlayer player, Identifier id, Quest quest) {
        return refusal(player, id, quest).isEmpty();
    }

    // --- accept / abandon / track ---

    public static Optional<Refusal> accept(ServerPlayer player, Identifier id) {
        MinecraftServer server = player.level().getServer();
        var holder = quest(server, id);
        if (holder.isEmpty()) return Optional.of(Refusal.UNKNOWN);
        Quest quest = holder.get().value();
        Optional<Refusal> why = refusal(player, id, quest);
        if (why.isPresent()) return why;

        List<ServerPlayer> members = sharers(player, quest);
        boolean party = scopeOf(server, quest) == QuestScope.PARTY;
        int factor = party && quest.scale() ? members.size() : 1;
        Stage first = quest.beats().getFirst();
        List<Integer> targets = targetsFor(first.objectives(), factor);

        QuestLog.Entry existing = null;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e != null) { existing = e; break; }
        }

        List<ServerPlayer> started = new ArrayList<>();
        for (ServerPlayer m : members) {
            if (m != player && !available(m, id, quest)) continue;
            QuestLog log = journal(m);
            if (log.isActive(id)) continue;
            if (existing != null) log.startFrom(id, existing); else log.start(id, targets);
            started.add(m);
            if (m == player) {
                Feedback.chat(m, Lang.fmt("msg.accept", "name", quest.name()));
            } else {
                Feedback.chat(m, Lang.fmt("msg.accept.party", "name", quest.name(), "who", player.getName().getString()));
            }
        }
        if (party && members.size() == 1 && Party.providerName().equals("none")) {
            Feedback.chat(player, Lang.get("msg.party.solo_notice"));
        }
        // A joiner lands mid-story: narrate the beat they are on, not the first.
        if (existing != null) {
            for (ServerPlayer m : started) {
                QuestLog.Entry e = journal(m).entry(id);
                Stage stage = quest.beats().get(Math.min(e.stage, quest.beats().size() - 1));
                narrate(m, quest, stage, e.stage);
                if (stage.isDecision()) presentChoices(m, id, quest, stage);
            }
        } else {
            enterStage(started, id, quest, 0, false);
        }
        for (ServerPlayer m : members) poll(m);
        return Optional.empty();
    }

    /**
     * Begin a beat for everyone sharing it: narration, the clock, on_enter, and
     * -- for a decision beat -- the choices. {@code jump} means the counters
     * need setting (a choice or a failure sent us here); a fresh accept has
     * them already.
     */
    private static void enterStage(List<ServerPlayer> members, Identifier id, Quest quest, int index, boolean jump) {
        List<Stage> beats = quest.beats();
        if (members.isEmpty() || beats.isEmpty()) return;
        index = Math.min(Math.max(index, 0), beats.size() - 1);
        Stage stage = beats.get(index);
        if (jump) {
            boolean party = scopeOf(members.getFirst().level().getServer(), quest) == QuestScope.PARTY;
            int factor = party && quest.scale() ? members.size() : 1;
            List<Integer> targets = targetsFor(stage.objectives(), factor);
            for (ServerPlayer m : members) {
                QuestLog.Entry e = journal(m).entry(id);
                if (e != null) e.jump(index, targets);
            }
        }
        long deadlineAt = stage.deadline().map(secs -> members.getFirst().level().getGameTime() + secs * 20L).orElse(-1L);
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e != null) e.deadlineAt = deadlineAt;
            narrate(m, quest, stage, index);
            if (deadlineAt >= 0) Feedback.chat(m, Lang.fmt("msg.deadline.set", "time", clock(stage.deadline().get() * 20L)));
            effects(m, stage.onEnter(), id, quest);
            if (stage.isDecision()) presentChoices(m, id, quest, stage);
        }
    }

    private static void presentChoices(ServerPlayer player, Identifier id, Quest quest, Stage stage) {
        Feedback.chat(player, Lang.get("msg.choice.header"));
        for (int n = 0; n < stage.choices().size(); n++) {
            String label = stage.choices().get(n).label();
            Feedback.chatWithButtons(player, Lang.fmt("msg.choice.option", "n", n + 1),
                    Feedback.button(Lang.fmt("msg.choice.button", "label", label),
                            "/quest choose " + id + " " + (n + 1), Lang.get("msg.choice.tip")));
        }
    }

    /** A player picks option {@code n} (1-based) at the current decision beat. */
    public static boolean choose(ServerPlayer player, Identifier id, int n) {
        MinecraftServer server = player.level().getServer();
        var holder = quest(server, id);
        QuestLog.Entry e = journal(player).entry(id);
        if (holder.isEmpty() || e == null) {
            Feedback.chat(player, Lang.get("msg.not_active"));
            return false;
        }
        Quest quest = holder.get().value();
        Stage stage = quest.beats().get(Math.min(e.stage, quest.beats().size() - 1));
        if (!stage.isDecision()) {
            Feedback.chat(player, Lang.get("msg.choice.none"));
            return false;
        }
        if (n < 1 || n > stage.choices().size()) {
            Feedback.chat(player, Lang.get("msg.choice.bad"));
            return false;
        }
        com.sablednah.chronicler.data.Choice choice = stage.choices().get(n - 1);
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer m : sharers(player, quest)) {
            if (journal(m).isActive(id)) members.add(m);
        }
        int index = e.stage;
        for (ServerPlayer m : members) {
            Feedback.chat(m, Lang.fmt("msg.choice.made", "label", choice.label()));
            choice.text().ifPresent(t -> Feedback.chat(m, Lang.fmt("msg.choice.text", "text", t)));
            effects(m, choice.effects(), id, quest);
            choice.start().ifPresent(other -> accept(m, other));
        }
        if (choice.end()) {
            complete(members, player, id, quest);
            return true;
        }
        int next = choice.next().map(v -> v - 1).orElse(index + 1);
        if (next >= quest.beats().size()) {
            complete(members, player, id, quest);
            return true;
        }
        enterStage(members, id, quest, next, true);
        for (ServerPlayer m : members) {
            poll(m);
            if (journal(m).entry(id) != null) showTracker(m, id);
        }
        return true;
    }

    /** The clock ran out on this beat for everyone sharing it. */
    private static void failStage(ServerPlayer player, Identifier id, Quest quest) {
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer m : sharers(player, quest)) {
            if (journal(m).isActive(id)) members.add(m);
        }
        if (members.isEmpty()) return;
        QuestLog.Entry lead = journal(members.getFirst()).entry(id);
        Stage stage = quest.beats().get(Math.min(lead.stage, quest.beats().size() - 1));
        for (ServerPlayer m : members) {
            Feedback.chat(m, Lang.fmt("msg.deadline.failed", "name", quest.name()));
            effects(m, stage.onFail(), id, quest);
        }
        if (stage.fail().isPresent()) {
            enterStage(members, id, quest, stage.fail().get() - 1, true);
            for (ServerPlayer m : members) {
                poll(m);
                if (journal(m).entry(id) != null) showTracker(m, id);
            }
            return;
        }
        for (ServerPlayer m : members) {
            journal(m).abandon(id);
            Feedback.chat(m, Lang.get("msg.deadline.abandoned"));
        }
        Chronicler.LOGGER.info("Chronicler: {} ran out of time on {}", player.getName().getString(), id);
    }

    /** "4:59" from ticks. */
    public static String clock(long ticks) {
        long secs = Math.max(0, ticks) / 20L;
        return (secs / 60) + ":" + String.format("%02d", secs % 60);
    }

    private static void narrate(ServerPlayer player, Quest quest, Stage stage, int index) {
        stage.text().ifPresent(t -> Feedback.chat(player, Lang.fmt("msg.stage.enter", "text", t)));
        for (ObjectiveSpec o : stage.objectives()) {
            Feedback.chat(player, Lang.fmt("msg.accept.objective", "line", o.describe()));
        }
    }

    private static void effects(ServerPlayer player, List<RewardSpec> effects, Identifier id, Quest quest) {
        for (RewardSpec r : effects) Rewards.grant(player, r, id, quest);
    }

    public static boolean abandon(ServerPlayer player, Identifier id) {
        QuestLog log = journal(player);
        if (!log.isActive(id)) return false;
        log.abandon(id);
        String name = quest(player.level().getServer(), id).map(h -> h.value().name()).orElse(id.toString());
        Feedback.chat(player, Lang.fmt("msg.abandon", "name", name));
        return true;
    }

    public static boolean track(ServerPlayer player, Identifier id) {
        QuestLog log = journal(player);
        if (!log.isActive(id)) return false;
        log.track(id);
        showTracker(player, id);
        return true;
    }

    // --- measuring ---

    public static void onKill(ServerPlayer killer, LivingEntity victim) {
        QuestLog log = journal(killer);
        if (log.activeCount() == 0) return;
        MinecraftServer server = killer.level().getServer();
        for (Identifier id : List.copyOf(log.activeView().keySet())) {
            var holder = quest(server, id);
            QuestLog.Entry e = log.entry(id);
            if (holder.isEmpty() || e == null) continue;
            Quest quest = holder.get().value();
            List<ObjectiveSpec> objectives = currentObjectives(quest, e);
            for (int n = 0; n < objectives.size() && n < e.progress.size(); n++) {
                ObjectiveSpec spec = objectives.get(n);
                if (Trackers.of(spec).countsKill(killer, victim, spec)) {
                    set(killer, id, quest, n, e.progress.get(n) + 1, false);
                    if (journal(killer).entry(id) == null || journal(killer).entry(id).stage != e.stage) break;
                }
            }
        }
    }

    public static void poll(ServerPlayer player) {
        QuestLog log = journal(player);
        if (log.activeCount() == 0) return;
        MinecraftServer server = player.level().getServer();
        for (Identifier id : List.copyOf(log.activeView().keySet())) {
            var holder = quest(server, id);
            QuestLog.Entry e = log.entry(id);
            if (holder.isEmpty() || e == null) continue;
            Quest quest = holder.get().value();
            if (e.deadlineAt >= 0 && player.level().getGameTime() >= e.deadlineAt) {
                failStage(player, id, quest);
                continue;
            }
            List<ServerPlayer> members = sharers(player, quest);
            List<ObjectiveSpec> objectives = currentObjectives(quest, e);
            int stage = e.stage;
            if (e.deadlineAt >= 0 && log.tracked().map(id::equals).orElse(true)) {
                showTracker(player, id); // the countdown is worth a line a second
            }
            for (int n = 0; n < objectives.size(); n++) {
                ObjectiveSpec spec = objectives.get(n);
                var tracker = Trackers.of(spec);
                int sum = 0;
                boolean polled = false;
                for (ServerPlayer m : members) {
                    OptionalInt v = tracker.poll(m, spec);
                    if (v.isPresent()) { polled = true; sum += v.getAsInt(); }
                }
                if (!polled) continue;
                set(player, id, quest, n, sum, tracker.latching(spec));
                QuestLog.Entry now = journal(player).entry(id);
                if (now == null || now.stage != stage) break; // the beat moved on under us
            }
        }
    }

    /** Write one objective's progress to every sharer, tell them, and move on if that was the last. */
    private static void set(ServerPlayer player, Identifier id, Quest quest, int n, int value, boolean latching) {
        List<ServerPlayer> members = sharers(player, quest);
        boolean changed = false;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e == null || n >= e.targets.size()) continue;
            int target = e.targets.get(n);
            int before = e.progress.get(n);
            int after = Math.min(value, target);
            if (latching) after = Math.max(before, after);
            if (after == before) continue;
            e.progress.set(n, after);
            changed = true;
            if (after >= target) {
                Feedback.chat(m, Lang.fmt("msg.objective_done", "line", currentObjectives(quest, e).get(n).describe()));
            }
            showProgress(m, id, quest, n);
        }
        if (!changed) return;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e != null && e.done() && !e.targets.isEmpty()) {
                finishStage(m, id, quest);
                return;
            }
        }
    }

    // --- stages and completion ---

    /** The current beat is done for everyone sharing it: settle, fire, advance or complete. */
    private static void finishStage(ServerPlayer player, Identifier id, Quest quest) {
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer m : sharers(player, quest)) {
            if (journal(m).isActive(id)) members.add(m);
        }
        if (members.isEmpty()) return;
        QuestLog.Entry lead = journal(members.getFirst()).entry(id);
        int index = lead.stage;
        List<Stage> beats = quest.beats();
        Stage stage = beats.get(Math.min(index, beats.size() - 1));

        // Settle: take what was gathered, from whoever holds it, up to the target.
        for (int n = 0; n < stage.objectives().size() && n < lead.targets.size(); n++) {
            ObjectiveSpec spec = stage.objectives().get(n);
            var tracker = Trackers.of(spec);
            int remaining = lead.targets.get(n);
            for (ServerPlayer m : members) {
                if (remaining <= 0) break;
                remaining -= tracker.settle(m, spec, remaining);
            }
        }
        for (ServerPlayer m : members) effects(m, stage.onComplete(), id, quest);

        if (index + 1 < beats.size()) {
            for (ServerPlayer m : members) {
                Feedback.chat(m, Lang.fmt("msg.stage.done", "stage", index + 1, "stages", beats.size()));
            }
            enterStage(members, id, quest, index + 1, true);
            for (ServerPlayer m : members) {
                poll(m);
                if (journal(m).entry(id) != null) showTracker(m, id);
            }
            return;
        }
        complete(members, player, id, quest);
    }

    private static void complete(List<ServerPlayer> members, ServerPlayer player, Identifier id, Quest quest) {
        for (ServerPlayer m : members) {
            journal(m).complete(id);
            Feedback.fanfare(m, Lang.fmt("msg.complete", "name", quest.name()),
                    Lang.fmt("msg.complete.title", "name", quest.name()),
                    Lang.get("msg.complete.subtitle"));
            for (RewardSpec r : quest.rewards()) Rewards.grant(m, r, id, quest);
            announceUnlocked(m, id);
            journal(m).tracked().ifPresent(t -> showTracker(m, t));
        }
        Chronicler.LOGGER.info("Chronicler: {} completed {}{}", player.getName().getString(), id,
                members.size() > 1 ? " with " + (members.size() - 1) + " party member(s)" : "");
    }

    /** Complete outright -- an admin or a StoryTeller finishing a quest by hand. */
    public static void complete(ServerPlayer player, Identifier id, Quest quest) {
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer m : sharers(player, quest)) {
            if (journal(m).isActive(id)) members.add(m);
        }
        if (!members.isEmpty()) complete(members, player, id, quest);
    }

    private static void announceUnlocked(ServerPlayer player, Identifier justDone) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = journal(player);
        quests(server).listElements().forEach(h -> {
            Quest q = h.value();
            Identifier id = h.key().identifier();
            if (!q.requires().contains(justDone)) return;
            if (log.isActive(id) || (log.isComplete(id) && !q.repeatable())) return;
            if (!available(player, id, q)) return;
            Feedback.chatWithButtons(player, Lang.fmt("msg.new_available", "name", q.name()),
                    Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")),
                    Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
        });
    }

    // --- the action-bar tracker ---

    private static void showProgress(ServerPlayer player, Identifier id, Quest quest, int n) {
        if (!ChroniclerConfig.TRACKER_ACTION_BAR.get()) return;
        QuestLog log = journal(player);
        if (log.tracked().map(t -> !t.equals(id)).orElse(false)) return;
        QuestLog.Entry e = log.entry(id);
        if (e == null || n >= e.targets.size()) return;
        String key = e.deadlineAt >= 0 ? "msg.deadline.bar" : "msg.progress";
        Feedback.actionBar(player, Lang.fmt(key,
                "quest", quest.name(),
                "objective", currentObjectives(quest, e).get(n).describe(),
                "done", e.progress.get(n), "target", e.targets.get(n),
                "time", clock(e.deadlineAt - player.level().getGameTime())));
    }

    public static void showTracker(ServerPlayer player, Identifier id) {
        if (!ChroniclerConfig.TRACKER_ACTION_BAR.get()) return;
        var holder = quest(player.level().getServer(), id);
        QuestLog.Entry e = journal(player).entry(id);
        if (holder.isEmpty() || e == null) return;
        Quest quest = holder.get().value();
        List<ObjectiveSpec> objectives = currentObjectives(quest, e);
        for (int n = 0; n < objectives.size() && n < e.targets.size(); n++) {
            if (!e.objectiveDone(n)) {
                showProgress(player, id, quest, n);
                return;
            }
        }
    }

    private QuestEngine() {}
}
