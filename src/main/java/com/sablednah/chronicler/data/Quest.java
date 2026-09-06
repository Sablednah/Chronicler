package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.core.QuestScope;

import net.minecraft.resources.Identifier;

/**
 * One quest, defined at {@code data/<pack>/chronicler/quest/<name>.json} or
 * {@code config/chronicler/quests/<name>.yml}.
 *
 * <p>Deliberately small. {@code docs/DESIGN.md} grows this record (giver,
 * stages, choices, availability, deadlines); each addition arrives as an
 * {@code optionalFieldOf} with a default, so every file written today keeps
 * loading.</p>
 *
 * @param name        shown to players; may carry {@code &} colour codes
 * @param description the hook -- why anyone would do this
 * @param chapter     the chapter this belongs to (bare names are ours)
 * @param requires    quests that must be complete before this one is offered
 * @param objectives  what has to happen, all of them
 * @param rewards     what is handed over on completion
 * @param repeatable  may be completed more than once
 * @param hidden      not listed until it is unlocked
 * @param order       sort key within the chapter
 * @param scope       solo or party; absent means the chapter's default
 * @param scale       for a party quest, multiply each target by party size
 */
public record Quest(
        String name,
        Optional<String> description,
        Identifier chapter,
        List<Identifier> requires,
        List<ObjectiveSpec> objectives,
        List<RewardSpec> rewards,
        boolean repeatable,
        boolean hidden,
        int order,
        Optional<QuestScope> scope,
        boolean scale) {

    public static final Codec<Quest> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Quest::name),
            Codec.STRING.optionalFieldOf("description").forGetter(Quest::description),
            ChroniclerIds.CODEC.fieldOf("chapter").forGetter(Quest::chapter),
            ChroniclerIds.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(Quest::requires),
            ObjectiveTypes.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Quest::objectives),
            RewardTypes.CODEC.listOf().optionalFieldOf("rewards", List.of()).forGetter(Quest::rewards),
            Codec.BOOL.optionalFieldOf("repeatable", false).forGetter(Quest::repeatable),
            Codec.BOOL.optionalFieldOf("hidden", false).forGetter(Quest::hidden),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Quest::order),
            QuestScope.CODEC.optionalFieldOf("scope").forGetter(Quest::scope),
            Codec.BOOL.optionalFieldOf("scale", true).forGetter(Quest::scale))
            .apply(i, Quest::new));
}
