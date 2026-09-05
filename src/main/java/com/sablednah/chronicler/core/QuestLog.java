package com.sablednah.chronicler.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * A player's quest journal: what they are on, how far, and what they have
 * finished. Loader-light on purpose -- no Minecraft types beyond
 * {@link Identifier} -- so it can be reasoned about and tested without a
 * server.
 *
 * <p>Progress is a list of counters, one per objective in the quest's own
 * order. Completions are counted, not flagged, so a repeatable quest knows
 * how many times it has been done and a one-shot is simply {@code >= 1}.</p>
 */
public final class QuestLog {

    public static final MapCodec<QuestLog> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
            Codec.unboundedMap(Identifier.CODEC, Codec.INT.listOf())
                    .optionalFieldOf("active", Map.of()).forGetter(l -> l.active),
            Codec.unboundedMap(Identifier.CODEC, Codec.INT)
                    .optionalFieldOf("completed", Map.of()).forGetter(l -> l.completed))
            .apply(i, QuestLog::new));

    private final Map<Identifier, List<Integer>> active;
    private final Map<Identifier, Integer> completed;

    public QuestLog() {
        this(Map.of(), Map.of());
    }

    private QuestLog(Map<Identifier, List<Integer>> active, Map<Identifier, Integer> completed) {
        this.active = new LinkedHashMap<>();
        active.forEach((k, v) -> this.active.put(k, new ArrayList<>(v)));
        this.completed = new LinkedHashMap<>(completed);
    }

    public boolean isActive(Identifier quest) { return active.containsKey(quest); }

    public boolean isComplete(Identifier quest) { return completed.getOrDefault(quest, 0) > 0; }

    public int completions(Identifier quest) { return completed.getOrDefault(quest, 0); }

    public int activeCount() { return active.size(); }

    public int completedCount() { return completed.size(); }

    /** Start a quest with {@code objectiveCount} zeroed counters. Idempotent. */
    public void start(Identifier quest, int objectiveCount) {
        active.computeIfAbsent(quest, k -> new ArrayList<>(java.util.Collections.nCopies(objectiveCount, 0)));
    }

    /** The counters for an active quest, or an empty list. Live view: mutate to record progress. */
    public List<Integer> progress(Identifier quest) {
        return active.getOrDefault(quest, List.of());
    }

    /** Record a completion and drop it from the active set. */
    public void complete(Identifier quest) {
        active.remove(quest);
        completed.merge(quest, 1, Integer::sum);
    }

    public void abandon(Identifier quest) {
        active.remove(quest);
    }

    public Map<Identifier, List<Integer>> activeView() { return java.util.Collections.unmodifiableMap(active); }

    public Map<Identifier, Integer> completedView() { return java.util.Collections.unmodifiableMap(completed); }
}
