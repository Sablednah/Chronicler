package com.sablednah.chronicler.core;

import java.util.Locale;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;

/**
 * Who a quest belongs to once accepted.
 *
 * <p>{@code SOLO}: one player, one journal entry. {@code PARTY}: one quest
 * for the whole party — every member has it active, progress is pooled, the
 * target is scaled by party size at acceptance, everyone completes and is
 * rewarded together. Chapters carry the default; a quest overrides it.</p>
 */
public enum QuestScope {
    SOLO, PARTY;

    public static final Codec<QuestScope> CODEC = Codec.STRING.comapFlatMap(s -> {
        try {
            return DataResult.success(QuestScope.valueOf(s.trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return DataResult.error(() -> "scope must be 'solo' or 'party', not '" + s + "'");
        }
    }, e -> e.name().toLowerCase(Locale.ROOT));
}
