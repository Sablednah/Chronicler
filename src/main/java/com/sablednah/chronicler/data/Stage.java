package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
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
 * @param unscaled   the objectives (0-based) written {@code "scale": false}: in a party quest their target
 *                   stays as written instead of growing with the party -- one boss for the whole party
 */
public record Stage(Optional<String> text, List<ObjectiveSpec> objectives,
        List<RewardSpec> onEnter, List<RewardSpec> onComplete,
        List<Choice> choices, Optional<Integer> deadline, List<RewardSpec> onFail, Optional<Integer> fail,
        Optional<String> ending, boolean end, Set<Integer> unscaled) {

    /** Before {@code scale} on an objective. */
    public Stage(Optional<String> text, List<ObjectiveSpec> objectives, List<RewardSpec> onEnter, List<RewardSpec> onComplete,
            List<Choice> choices, Optional<Integer> deadline, List<RewardSpec> onFail, Optional<Integer> fail,
            Optional<String> ending, boolean end) {
        this(text, objectives, onEnter, onComplete, choices, deadline, onFail, fail, ending, end, Set.of());
    }

    /** A beat's objectives as written, with the indices of those that said {@code "scale": false}. */
    public record Objectives(List<ObjectiveSpec> list, Set<Integer> unscaled) {
        static final Objectives NONE = new Objectives(List.of(), Set.of());
    }

    /**
     * The objectives list, with {@code scale} taken off each entry before it is decoded (it is the beat's
     * business, not the objective's: every tracker and every label stays exactly as it was) and put back on
     * the way out.
     */
    public static final Codec<Objectives> OBJECTIVES = new Codec<>() {
        @Override
        public <T> DataResult<Pair<Objectives, T>> decode(DynamicOps<T> ops, T input) {
            JsonElement json = ops.convertTo(JsonOps.INSTANCE, input);
            if (!(json instanceof JsonArray arr)) return ObjectiveTypes.CODEC.listOf().decode(ops, input).map(p -> Pair.of(new Objectives(p.getFirst(), Set.of()), p.getSecond()));
            JsonArray rest = new JsonArray();
            Set<Integer> unscaled = new java.util.TreeSet<>();
            for (int n = 0; n < arr.size(); n++) {
                JsonElement e = arr.get(n);
                if (e instanceof JsonObject o && o.has("scale")) {
                    JsonElement v = o.get("scale");
                    if (!v.isJsonPrimitive() || !v.getAsJsonPrimitive().isBoolean()) {
                        int at = n + 1;
                        return DataResult.error(() -> "objective " + at + ": scale is true or false");
                    }
                    if (!v.getAsBoolean()) unscaled.add(n);
                    o = o.deepCopy();
                    o.remove("scale");
                    e = o;
                }
                rest.add(e);
            }
            return ObjectiveTypes.CODEC.listOf().parse(JsonOps.INSTANCE, rest)
                    .map(list -> Pair.of(new Objectives(list, Set.copyOf(unscaled)), input));
        }

        @Override
        public <T> DataResult<T> encode(Objectives in, DynamicOps<T> ops, T prefix) {
            return ObjectiveTypes.CODEC.listOf().encodeStart(JsonOps.INSTANCE, in.list()).map(json -> {
                if (json instanceof JsonArray arr) for (int n : in.unscaled()) {
                    if (n < arr.size() && arr.get(n) instanceof JsonObject o) o.addProperty("scale", false);
                }
                return JsonOps.INSTANCE.convertTo(ops, json);
            });
        }
    };

    public static final Codec<Stage> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.optionalFieldOf("text").forGetter(Stage::text),
            OBJECTIVES.optionalFieldOf("objectives", Objectives.NONE).forGetter(s -> new Objectives(s.objectives, s.unscaled)),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_enter", List.of()).forGetter(Stage::onEnter),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_complete", List.of()).forGetter(Stage::onComplete),
            Choice.CODEC.listOf().optionalFieldOf("choices", List.of()).forGetter(Stage::choices),
            Codec.INT.optionalFieldOf("deadline").forGetter(Stage::deadline),
            RewardTypes.CODEC.listOf().optionalFieldOf("on_fail", List.of()).forGetter(Stage::onFail),
            Codec.INT.optionalFieldOf("fail").forGetter(Stage::fail),
            Codec.STRING.optionalFieldOf("ending").forGetter(Stage::ending),
            Codec.BOOL.optionalFieldOf("end", false).forGetter(Stage::end))
            .apply(i, (text, o, enter, complete, choices, deadline, onFail, fail, ending, end) ->
                    new Stage(text, o.list(), enter, complete, choices, deadline, onFail, fail, ending, end, o.unscaled())));

    /** Does objective {@code n}'s target grow with the party (the quest's {@code scale} permitting)? */
    public boolean scales(int n) {
        return !unscaled.contains(n);
    }

    public static Stage of(List<ObjectiveSpec> objectives) {
        return new Stage(Optional.empty(), objectives, List.of(), List.of(), List.of(), Optional.empty(), List.of(), Optional.empty(), Optional.empty(), false);
    }

    /** A beat that waits for a choice rather than an objective. */
    public boolean isDecision() {
        return objectives.isEmpty() && !choices.isEmpty();
    }
}
