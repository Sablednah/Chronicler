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

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;

/**
 * The engine: accept, measure, complete, reward. Everything here is the
 * <em>rule</em>; the commands and events are thin callers, so the self-test
 * and a future StoryTeller NPC drive exactly the code a player does.
 *
 * <h2>Party quests</h2>
 *
 * <p>A party quest is one quest for the whole party. Every member's journal
 * holds the same entry (targets scaled by party size at acceptance), and every
 * write goes to every member -- a kill by one moves everyone's counter, a poll
 * sums everyone's inventory. Membership comes from {@link Party}, which
 * answers "just you" without Standards, so a party quest quietly becomes a
 * solo one there and says so once.</p>
 */
public final class QuestEngine {

    /** Why an accept was refused, for the message. */
    public enum Refusal { UNKNOWN, ALREADY_ACTIVE, ALREADY_COMPLETE, LOCKED }

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

    /** Who shares this quest's progress: the party for a party quest, just the player otherwise. */
    private static List<ServerPlayer> sharers(ServerPlayer player, Quest quest) {
        return scopeOf(player.level().getServer(), quest) == QuestScope.PARTY
                ? Party.members(player) : List.of(player);
    }

    // --- availability ---

    public static Optional<Refusal> refusal(ServerPlayer player, Identifier id, Quest quest) {
        QuestLog log = journal(player);
        if (log.isActive(id)) return Optional.of(Refusal.ALREADY_ACTIVE);
        if (log.isComplete(id) && !quest.repeatable()) return Optional.of(Refusal.ALREADY_COMPLETE);
        for (Identifier r : quest.requires()) {
            if (!log.isComplete(r)) return Optional.of(Refusal.LOCKED);
        }
        return Optional.empty();
    }

    public static boolean available(ServerPlayer player, Identifier id, Quest quest) {
        return refusal(player, id, quest).isEmpty();
    }

    // --- accept / abandon / track ---

    /** Accept for the player (and, for a party quest, for every member). Returns the refusal, if any. */
    public static Optional<Refusal> accept(ServerPlayer player, Identifier id) {
        MinecraftServer server = player.level().getServer();
        var holder = quest(server, id);
        if (holder.isEmpty()) return Optional.of(Refusal.UNKNOWN);
        Quest quest = holder.get().value();
        Optional<Refusal> why = refusal(player, id, quest);
        if (why.isPresent()) return why;

        List<ServerPlayer> members = sharers(player, quest);
        boolean party = members.size() > 1 || scopeOf(server, quest) == QuestScope.PARTY;
        int factor = party && quest.scale() ? members.size() : 1;
        List<Integer> targets = new ArrayList<>();
        for (ObjectiveSpec o : quest.objectives()) targets.add(Math.max(1, o.required() * factor));

        // A member already on it? Then this is somebody joining a quest under way.
        QuestLog.Entry existing = null;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e != null) { existing = e; break; }
        }

        for (ServerPlayer m : members) {
            if (m != player && !available(m, id, quest) && !journal(m).isActive(id)) continue;
            QuestLog log = journal(m);
            if (log.isActive(id)) continue;
            if (existing != null) log.startFrom(id, existing); else log.start(id, targets);
            if (m == player) {
                Feedback.chat(m, Lang.fmt("msg.accept", "name", quest.name()));
            } else {
                Feedback.chat(m, Lang.fmt("msg.accept.party", "name", quest.name(), "who", player.getName().getString()));
            }
            for (ObjectiveSpec o : quest.objectives()) {
                Feedback.chat(m, Lang.fmt("msg.accept.objective", "line", o.describe()));
            }
        }
        if (scopeOf(server, quest) == QuestScope.PARTY && members.size() == 1 && Party.providerName().equals("none")) {
            Feedback.chat(player, Lang.get("msg.party.solo_notice"));
        }
        // Things already in the pack, places already stood in: count them now.
        for (ServerPlayer m : members) poll(m);
        return Optional.empty();
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

    /** A kill: bump every matching event objective on every active quest. */
    public static void onKill(ServerPlayer killer, LivingEntity victim) {
        QuestLog log = journal(killer);
        if (log.activeCount() == 0) return;
        MinecraftServer server = killer.level().getServer();
        for (Identifier id : List.copyOf(log.activeView().keySet())) {
            var holder = quest(server, id);
            if (holder.isEmpty()) continue;
            Quest quest = holder.get().value();
            for (int n = 0; n < quest.objectives().size(); n++) {
                ObjectiveSpec spec = quest.objectives().get(n);
                if (Trackers.of(spec).countsKill(killer, victim, spec)) {
                    bump(killer, id, quest, n, 1);
                }
            }
        }
    }

    /** Recompute every polled objective on every active quest for this player. */
    public static void poll(ServerPlayer player) {
        QuestLog log = journal(player);
        if (log.activeCount() == 0) return;
        MinecraftServer server = player.level().getServer();
        for (Identifier id : List.copyOf(log.activeView().keySet())) {
            var holder = quest(server, id);
            if (holder.isEmpty()) continue;
            Quest quest = holder.get().value();
            List<ServerPlayer> members = sharers(player, quest);
            for (int n = 0; n < quest.objectives().size(); n++) {
                ObjectiveSpec spec = quest.objectives().get(n);
                var tracker = Trackers.of(spec);
                int sum = 0;
                boolean polled = false;
                for (ServerPlayer m : members) {
                    OptionalInt v = tracker.poll(m, spec);
                    if (v.isPresent()) { polled = true; sum += v.getAsInt(); }
                }
                if (!polled) continue;
                set(player, id, quest, n, sum, tracker.latching(spec));
            }
        }
    }

    private static void bump(ServerPlayer player, Identifier id, Quest quest, int n, int delta) {
        QuestLog.Entry own = journal(player).entry(id);
        if (own == null) return;
        set(player, id, quest, n, own.progress.get(n) + delta, false);
    }

    /** Write one objective's progress to every sharer, tell them, and complete if that was the last. */
    private static void set(ServerPlayer player, Identifier id, Quest quest, int n, int value, boolean latching) {
        List<ServerPlayer> members = sharers(player, quest);
        boolean changed = false;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e == null) continue;
            int target = e.targets.get(n);
            int before = e.progress.get(n);
            int after = Math.min(value, target);
            if (latching) after = Math.max(before, after);
            if (after == before) continue;
            e.progress.set(n, after);
            changed = true;
            if (after >= target) {
                Feedback.chat(m, Lang.fmt("msg.objective_done", "line", quest.objectives().get(n).describe()));
            }
            showProgress(m, id, quest, n);
        }
        if (!changed) return;
        for (ServerPlayer m : members) {
            QuestLog.Entry e = journal(m).entry(id);
            if (e != null && e.done()) {
                complete(m, id, quest);
                return; // complete() handles every sharer
            }
        }
    }

    // --- completion ---

    public static void complete(ServerPlayer player, Identifier id, Quest quest) {
        List<ServerPlayer> members = new ArrayList<>();
        for (ServerPlayer m : sharers(player, quest)) {
            if (journal(m).isActive(id)) members.add(m);
        }
        if (members.isEmpty()) return;

        // Settle: take what was gathered, from whoever holds it, up to the target.
        for (int n = 0; n < quest.objectives().size(); n++) {
            ObjectiveSpec spec = quest.objectives().get(n);
            var tracker = Trackers.of(spec);
            int remaining = journal(members.getFirst()).entry(id).targets.get(n);
            for (ServerPlayer m : members) {
                if (remaining <= 0) break;
                remaining -= tracker.settle(m, spec, remaining);
            }
        }

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

    /** "New quest available" -- the moment a completion unlocks something, with a button. */
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
        if (e == null) return;
        Feedback.actionBar(player, Lang.fmt("msg.progress",
                "quest", quest.name(),
                "objective", quest.objectives().get(n).describe(),
                "done", e.progress.get(n), "target", e.targets.get(n)));
    }

    /** The tracked quest's first unfinished objective, on the action bar. */
    public static void showTracker(ServerPlayer player, Identifier id) {
        if (!ChroniclerConfig.TRACKER_ACTION_BAR.get()) return;
        var holder = quest(player.level().getServer(), id);
        QuestLog.Entry e = journal(player).entry(id);
        if (holder.isEmpty() || e == null) return;
        Quest quest = holder.get().value();
        for (int n = 0; n < quest.objectives().size(); n++) {
            if (!e.objectiveDone(n)) {
                showProgress(player, id, quest, n);
                return;
            }
        }
    }

    private QuestEngine() {}
}
