package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * One option at a decision beat. Choosing it fires {@code effects} (karma, a
 * flag, reputation, a message), then goes to {@code next} (a 1-based stage
 * number), or ends the quest ({@code end: true}, rewards paid), or -- absent
 * both -- the following stage. {@code start} also begins another quest.
 *
 * @param label   the button
 * @param text    narrated when chosen
 * @param effects what the choice does
 * @param next    the stage to go to, 1-based
 * @param end     complete the quest instead
 * @param start   another quest to accept as well
 */
public record Choice(String label, Optional<String> text, List<RewardSpec> effects,
        Optional<Integer> next, boolean end, Optional<Identifier> start, Optional<String> ending) {

    public static final Codec<Choice> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("label").forGetter(Choice::label),
            Codec.STRING.optionalFieldOf("text").forGetter(Choice::text),
            RewardTypes.CODEC.listOf().optionalFieldOf("effects", List.of()).forGetter(Choice::effects),
            Codec.INT.optionalFieldOf("next").forGetter(Choice::next),
            Codec.BOOL.optionalFieldOf("end", false).forGetter(Choice::end),
            ChroniclerIds.CODEC.optionalFieldOf("start").forGetter(Choice::start),
            Codec.STRING.optionalFieldOf("ending").forGetter(Choice::ending))
            .apply(i, Choice::new));
}
