package com.sablednah.chronicler.core;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * When a quest is listed to a player at all -- in the journal panel, the book
 * and {@code /quests}. A quest the player has started or finished is always
 * listed; this only decides the time before that.
 *
 * <p>{@code ALWAYS}: from the start, locked or not -- the player can see what
 * is coming. {@code UNLOCKED}: once every quest it {@code requires} is done, so
 * the next step appears when it opens rather than spoiling it early.
 * {@code FOUND}: only once started, typically from a giver the player walked
 * into; {@code hidden: true} in a file means this.</p>
 */
public enum QuestVisibility {
    ALWAYS, UNLOCKED, FOUND;

    public static final Codec<QuestVisibility> CODEC = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(QuestVisibility.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "visibility must be 'always', 'unlocked' or 'found', not '" + s + "'");
        }
    }, e -> e.name().toLowerCase(Locale.ROOT));
}
