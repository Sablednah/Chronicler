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
        /** Which beat of the quest these counters belong to. */
        public int stage;
        /** Game time by which this beat must be done, or -1 for no clock. */
        public long deadlineAt = -1;
        /** Game time this beat was entered, for {@code wait} objectives. */
        public long enteredAt = 0;

        Entry(List<Integer> progress, List<Integer> targets) {
            this(progress, targets, 0, -1L, 0L);
        }

        Entry(List<Integer> progress, List<Integer> targets, int stage, long deadlineAt, long enteredAt) {
            this.progress = new ArrayList<>(progress);
            this.targets = new ArrayList<>(targets);
            this.stage = stage;
            this.deadlineAt = deadlineAt;
            this.enteredAt = enteredAt;
        }

        static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.listOf().fieldOf("progress").forGetter(e -> e.progress),
                Codec.INT.listOf().fieldOf("targets").forGetter(e -> e.targets),
                Codec.INT.optionalFieldOf("stage", 0).forGetter(e -> e.stage),
                Codec.LONG.optionalFieldOf("deadline_at", -1L).forGetter(e -> e.deadlineAt),
                Codec.LONG.optionalFieldOf("entered_at", 0L).forGetter(e -> e.enteredAt))
                .apply(i, Entry::new));

        /** Move to the next beat: fresh counters against its targets. */
        public void advance(List<Integer> nextTargets) {
            jump(stage + 1, nextTargets);
        }

        /** Move to any beat: fresh counters, clock cleared. */
        public void jump(int toStage, List<Integer> nextTargets) {
            stage = toStage;
            deadlineAt = -1;
            progress.clear();
            targets.clear();
            targets.addAll(nextTargets);
            for (int n = 0; n < nextTargets.size(); n++) progress.add(0);
        }

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
            Codec.BOOL.optionalFieldOf("journal_given", false).forGetter(l -> l.journalGiven),
            Codec.STRING.listOf().optionalFieldOf("flags", List.of()).forGetter(l -> List.copyOf(l.flags)),
            Codec.unboundedMap(Identifier.CODEC, Codec.LONG).optionalFieldOf("completed_at", Map.of()).forGetter(l -> l.completedAt),
            Codec.unboundedMap(Identifier.CODEC, Codec.STRING.listOf()).optionalFieldOf("endings", Map.of())
                    .forGetter(l -> { Map<Identifier, List<String>> m = new LinkedHashMap<>(); l.endings.forEach((k, v) -> m.put(k, List.copyOf(v))); return m; }),
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC).optionalFieldOf("flag_owners", Map.of()).forGetter(l -> l.flagOwners))
            .apply(i, QuestLog::new));

    /** Endings reached, per chapter, in the order they were reached. */
    private final Map<Identifier, java.util.LinkedHashSet<String>> endings = new LinkedHashMap<>();
    /** Which chapter set each player flag, so a replay can take its own flags back and nobody else's. */
    private final Map<String, Identifier> flagOwners = new LinkedHashMap<>();

    private final Map<Identifier, Entry> active;
    private final Map<Identifier, Integer> completed;
    private Identifier tracked;
    /** Has this player ever been handed the journal item? Once, not per world-join. */
    private boolean journalGiven;
    /** The player's own flags -- choices made, things seen. */
    private final java.util.Set<String> flags = new java.util.LinkedHashSet<>();

    /** Wall-clock millis of the last completion, for repeatable cooldowns (game time pauses with the server). */
    private final Map<Identifier, Long> completedAt;

    public QuestLog() {
        this(Map.of(), Map.of(), Optional.empty(), false, List.of(), Map.of(), Map.of(), Map.of());
    }

    private QuestLog(Map<Identifier, Entry> active, Map<Identifier, Integer> completed, Optional<Identifier> tracked,
            boolean journalGiven, List<String> flags, Map<Identifier, Long> completedAt,
            Map<Identifier, List<String>> endings, Map<String, Identifier> flagOwners) {
        this.journalGiven = journalGiven;
        endings.forEach((k, v) -> this.endings.put(k, new java.util.LinkedHashSet<>(v)));
        this.flagOwners.putAll(flagOwners);
        this.flags.addAll(flags);
        this.completedAt = new LinkedHashMap<>(completedAt);
        this.active = new LinkedHashMap<>();
        active.forEach((k, v) -> this.active.put(k, new Entry(v.progress, v.targets, v.stage, v.deadlineAt, v.enteredAt)));
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
        active.put(quest, new Entry(other.progress, other.targets, other.stage, other.deadlineAt, other.enteredAt));
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
        completedAt.put(quest, System.currentTimeMillis());
        if (quest.equals(tracked)) tracked = active.isEmpty() ? null : active.keySet().iterator().next();
    }

    public void abandon(Identifier quest) {
        active.remove(quest);
        if (quest.equals(tracked)) tracked = active.isEmpty() ? null : active.keySet().iterator().next();
    }

    public Optional<Identifier> tracked() { return Optional.ofNullable(tracked); }

    public void track(Identifier quest) { tracked = quest; }

    public long completedAt(Identifier quest) { return completedAt.getOrDefault(quest, 0L); }

    /** Test fixtures: pretend it was done long ago. */
    public void setCompletedAt(Identifier quest, long millis) { completedAt.put(quest, millis); }

    public boolean hasFlag(String name) { return flags.contains(name.trim().toLowerCase(java.util.Locale.ROOT)); }

    /** An ending reached; true if it is new to this player. */
    public boolean addEnding(Identifier chapter, String ending) {
        return endings.computeIfAbsent(chapter, k -> new java.util.LinkedHashSet<>()).add(ending.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public java.util.Set<String> endings(Identifier chapter) {
        return Collections.unmodifiableSet(endings.getOrDefault(chapter, new java.util.LinkedHashSet<>()));
    }

    /** Set a flag on behalf of a chapter, so a replay of that chapter can clear it. */
    public void setFlag(String name, boolean value, Identifier owner) {
        setFlag(name, value);
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (value && owner != null) flagOwners.put(key, owner);
        if (!value) flagOwners.remove(key);
    }

    /**
     * Start a chapter over: its quests are no longer active or complete, their
     * cooldowns are gone, and every player flag it set is cleared. Endings stay --
     * they are the point of playing again.
     */
    public void replay(Identifier chapter, java.util.Collection<Identifier> questsInChapter) {
        for (Identifier q : questsInChapter) {
            active.remove(q);
            completed.remove(q);
            completedAt.remove(q);
            if (q.equals(tracked)) tracked = null;
        }
        List<String> mine = new ArrayList<>();
        flagOwners.forEach((f, owner) -> { if (chapter.equals(owner)) mine.add(f); });
        for (String f : mine) { flags.remove(f); flagOwners.remove(f); }
    }

    public void setFlag(String name, boolean value) {
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (value) flags.add(key); else flags.remove(key);
    }

    public java.util.Set<String> flags() { return Collections.unmodifiableSet(flags); }

    public boolean journalGiven() { return journalGiven; }

    public void markJournalGiven() { journalGiven = true; }

    /** Wipe everything -- an admin reset, or a test fixture. */
    public void clear() {
        active.clear();
        completed.clear();
        tracked = null;
        journalGiven = false;
        flags.clear();
        completedAt.clear();
    }

    public Map<Identifier, Entry> activeView() { return Collections.unmodifiableMap(active); }

    public Map<Identifier, Integer> completedView() { return Collections.unmodifiableMap(completed); }
}
