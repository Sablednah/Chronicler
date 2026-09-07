package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * When a quest may be offered, beyond {@code requires}. Every present field
 * must hold. Character fields need a character system (LegendQuest); with
 * none, a quest that names them is never available -- and says why.
 *
 * @param karmaMin     LegendQuest karma at least this
 * @param karmaMax     ... at most this (a fall-from-grace questline)
 * @param levelMin     character level at least
 * @param levelMax     ... at most
 * @param flags        world flags that must hold (true) or not (false)
 * @param playerFlags  the player's own flags, likewise
 * @param reputation   minimum standing with each named group
 */
public record Availability(
        Optional<Long> karmaMin, Optional<Long> karmaMax,
        Optional<Integer> levelMin, Optional<Integer> levelMax,
        Map<String, Boolean> flags, Map<String, Boolean> playerFlags,
        Map<String, Integer> reputation,
        List<String> race, List<String> clazz) {

    public static final Codec<Availability> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("karma_min").forGetter(Availability::karmaMin),
            Codec.LONG.optionalFieldOf("karma_max").forGetter(Availability::karmaMax),
            Codec.INT.optionalFieldOf("level_min").forGetter(Availability::levelMin),
            Codec.INT.optionalFieldOf("level_max").forGetter(Availability::levelMax),
            Codec.unboundedMap(Codec.STRING, Codec.BOOL).optionalFieldOf("flags", Map.of()).forGetter(Availability::flags),
            Codec.unboundedMap(Codec.STRING, Codec.BOOL).optionalFieldOf("player_flags", Map.of()).forGetter(Availability::playerFlags),
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("reputation", Map.of()).forGetter(Availability::reputation),
            Codec.STRING.listOf().optionalFieldOf("race", List.of()).forGetter(Availability::race),
            Codec.STRING.listOf().optionalFieldOf("class", List.of()).forGetter(Availability::clazz))
            .apply(i, Availability::new));

    public boolean needsCharacter() {
        return karmaMin.isPresent() || karmaMax.isPresent() || levelMin.isPresent() || levelMax.isPresent()
                || !race.isEmpty() || !clazz.isEmpty();
    }
}
