package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * One beat of a quest: what the player is told on reaching it, what has to
 * happen, and what fires on the way in and the way out. A quest with no
 * {@code stages} is one stage made of its {@code objectives}.
 *
 * <p>Effects reuse the reward vocabulary -- a command, an item, a title, a
 * message, a spawn -- because "what happens" is the same kind of thing
 * whether it is a payout or a scene.</p>
 *
 * @param text       narrated when the stage begins; the story, in order
 * @param objectives all of them, to finish the stage
 * @param onEnter    effects when the stage begins
 * @param onComplete effects when the stage ends, before the next begins
 */
public record Stage(Optional<String> text, List<ObjectiveSpec> objectives,
        List<RewardSpec> onEnter, List<RewardSpec> onComplete) {

    public static final Codec<Stage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("text").forGetter(Stage::text),
            ObjectiveTypes.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Stage::objectives),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(Stage::onEnter),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_complete", List.of()).forGetter(Stage::onComplete))
            .apply(i, Stage::new));

    public static Stage of(List<ObjectiveSpec> objectives) {
        return new Stage(Optional.empty(), objectives, List.of(), List.of());
    }
}
