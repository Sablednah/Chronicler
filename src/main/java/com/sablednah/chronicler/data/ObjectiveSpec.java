package com.sablednah.chronicler.data;

import com.mojang.serialization.MapCodec;

/**
 * One thing a quest asks of the player. Pure data: the record says <em>what</em>,
 * and the engine (later) says <em>how it is measured</em>. Keeping the record
 * loader-light is what lets it sync to a client and print in a book.
 *
 * <p>{@link #target()} is the number that counts as done; most objectives
 * count up to it, a location objective is 0 or 1.</p>
 */
public interface ObjectiveSpec {

    MapCodec<? extends ObjectiveSpec> codec();

    /** The count that completes this objective. */
    int required();

    /**
     * Seconds the condition must hold continuously before it credits, for an objective that can
     * otherwise complete the instant a line is crossed -- a village's edge, an escort's radius --
     * with no chance to actually be inside before the next beat's narration (and its ambush) fires.
     * 0, the default, credits the moment it is true, as every objective always has.
     */
    default int settleSeconds() { return 0; }

    /**
     * One line for chat and the journal, from the record's own fields so the
     * text can never disagree with the rule. Goes through {@code Lang} so a
     * server can re-word it.
     */
    String describe();
}
