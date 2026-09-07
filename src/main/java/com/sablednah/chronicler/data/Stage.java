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
 * <p>A beat with {@code choices} and no objectives is a <b>decision</b>: the
 * options are put to the player on entry and the quest waits. A beat with a
 * {@code deadline} (seconds) fails when the clock runs out: {@code on_fail}
 * fires, then the quest goes to {@code fail} (a 1-based stage) or is
 * abandoned.</p>
 *
 * @param text       narrated when the stage begins; the story, in order
 * @param objectives all of them, to finish the stage
 * @param onEnter    effects when the stage begins
 * @param onComplete effects when the stage ends, before the next begins
 * @param choices    the options at a decision beat
 * @param deadline   seconds allowed for this beat
 * @param onFail     effects when the deadline passes
 * @param fail       the stage to fall back to, 1-based; absent abandons the quest
 */
public record Stage(Optional<String> text, List<ObjectiveSpec> objectives,
        List<RewardSpec> onEnter, List<RewardSpec> onComplete,
        List<Choice> choices, Optional<Integer> deadline, List<RewardSpec> onFail, Optional<Integer> fail,
        Optional<String> ending, boolean end) {

    public static final Codec<Stage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("text").forGetter(Stage::text),
            ObjectiveTypes.CODEC.listOf().optionalFieldOf("objectives", List.of()).forGetter(Stage::objectives),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(Stage::onEnter),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_complete", List.of()).forGetter(Stage::onComplete),
            Choice.CODEC.listOf().optionalFieldOf("choices", List.of()).forGetter(Stage::choices),
            Codec.INT.optionalFieldOf("deadline").forGetter(Stage::deadline),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_fail", List.of()).forGetter(Stage::onFail),
            Codec.INT.optionalFieldOf("fail").forGetter(Stage::fail),
            Codec.STRING.optionalFieldOf("ending").forGetter(Stage::ending),
            Codec.BOOL.optionalFieldOf("end", false).forGetter(Stage::end))
            .apply(i, Stage::new));

    public static Stage of(List<ObjectiveSpec> objectives) {
        return new Stage(Optional.empty(), objectives, List.of(), List.of(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false);
    }

    /** A beat that waits for a choice rather than an objective. */
    public boolean isDecision() {
        return objectives.isEmpty() && !choices.isEmpty();
    }
}
