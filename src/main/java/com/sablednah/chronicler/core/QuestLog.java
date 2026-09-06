package com.sablednah.chronicler.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * A player's quest journal: what they are on, how far, what they have
 * finished, and which one the action bar follows. Loader-light on purpose --
 * no Minecraft types beyond {@link Identifier} -- so it is testable without
 * a server.
 *
 * <p>Each active quest holds two lists in objective order: {@code progress}
 * and {@code targets}. Targets are stored, not recomputed, because a party
 * quest scales them by party size at acceptance and that number must survive
 * the party changing shape. Completions are counted, so a repeatable quest
 * knows how many times it has been done and a one-shot is {@code >= 1}.</p>
 */
public final class QuestLog {

    /** One active quest. Mutable lists, deliberately: the engine writes progress in place. */
    public static final class Entry {
        public final List<Integer> progress;
        public final List<Integer> targets;

        Entry(List<Integer> progress, List<Integer> targets) {
            this.progress = new ArrayList<>(progress);
            this.targets = new ArrayList<>(targets);
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.listOf().fieldOf("progress").forGetter(e -> e.progress),
                Codec.INT.listOf().fieldOf("targets").forGetter(e -> e.targets))
                .apply(i, Entry::new));

        public boolean done() {
            for (int n = 0; n < targets.size(); n++) {
                if (progress.get(n) < targets.get(n)) return false;
            }
            return true;
        }

        public boolean objectiveDone(int n) {
            return progress.get(n) >= targets.get(n);
        }
    }

    public static final MapCodec<QuestLog> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.unboundedMap(Identifier.CODEC, Entry.CODEC)
                    .optionalFieldOf("active", Map.of()).forGetter(l -> l.active),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT)
                    .optionalFieldOf("completed", Map.of()).forGetter(l -> l.completed),
            Identifier.CODEC.optionalFieldOf("tracked").forGetter(l -> Optional.ofNullable(l.tracked)),
            Codec.BOOL.optionalFieldOf("journal_given", false).forGetter(l -> l.journalGiven))
            .apply(i, QuestLog::new));

    private final Map<Identifier, Entry> active;
    private final Map<Identifier, Integer> completed;
    private Identifier tracked;
    /** Has this player ever been handed the journal item? Once, not per world-join. */
    private boolean journalGiven;

    public QuestLog() {
        this(Map.of(), Map.of(), Optional.empty(), false);
    }

    private QuestLog(Map<Identifier, Entry> active, Map<Identifier, Integer> completed, Optional<Identifier> tracked,
            boolean journalGiven) {
        this.journalGiven = journalGiven;
        this.active = new LinkedHashMap<>();
        active.forEach((k, v) -> this.active.put(k, new Entry(v.progress, v.targets)));
        this.completed = new LinkedHashMap<>(completed);
        this.tracked = tracked.orElse(null);
    }

    public boolean isActive(Identifier quest) { return active.containsKey(quest); }

    public boolean isComplete(Identifier quest) { return completed.getOrDefault(quest, 0) > 0; }

    public int completions(Identifier quest) { return completed.getOrDefault(quest, 0); }

    public int activeCount() { return active.size(); }

    public int completedCount() { return completed.size(); }

    /** Start a quest with zeroed counters against the given targets. Idempotent. */
    public void start(Identifier quest, List<Integer> targets) {
        active.computeIfAbsent(quest, k -> new Entry(Collections.nCopies(targets.size(), 0), targets));
        if (tracked == null) tracked = quest;
    }

    /** Start a quest at somebody else's progress -- a party member joining a quest already under way. */
    public void startFrom(Identifier quest, Entry other) {
        active.put(quest, new Entry(other.progress, other.targets));
        if (tracked == null) tracked = quest;
    }

    /** The live entry for an active quest, or null. */
    public Entry entry(Identifier quest) {
        return active.get(quest);
    }

    /** Record a completion and drop it from the active set. */
    public void complete(Identifier quest) {
        active.remove(quest);
        completed.merge(quest, 1, Integer::sum);
        if (quest.equals(tracked)) tracked = active.isEmpty() ? null : active.keySet().iterator().next();
    }

    public void abandon(Identifier quest) {
        active.remove(quest);
        if (quest.equals(tracked)) tracked = active.isEmpty() ? null : active.keySet().iterator().next();
    }

    public Optional<Identifier> tracked() { return Optional.ofNullable(tracked); }

    public void track(Identifier quest) { tracked = quest; }

    public boolean journalGiven() { return journalGiven; }

    public void markJournalGiven() { journalGiven = true; }

    /** Wipe everything -- an admin reset, or a test fixture. */
    public void clear() {
        active.clear();
        completed.clear();
        tracked = null;
        journalGiven = false;
    }

    public Map<Identifier, Entry> activeView() { return Collections.unmodifiableMap(active); }

    public Map<Identifier, Integer> completedView() { return Collections.unmodifiableMap(completed); }
}
