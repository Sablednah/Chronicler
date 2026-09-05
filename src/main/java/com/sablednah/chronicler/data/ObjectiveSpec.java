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
     * One line for chat and the journal, from the record's own fields so the
     * text can never disagree with the rule. Goes through {@code Lang} so a
     * server can re-word it.
     */
    String describe();
}
