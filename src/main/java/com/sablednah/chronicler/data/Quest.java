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
 * @param giver       who or where offers it; absent means the list and the journal only
 * @param stages      ordered beats; when present, {@code objectives} is ignored and
 *                    each stage carries its own
 * @param availability karma, level, flags and standing the player must meet
 * @param cooldown    seconds after completing before a repeatable is offered again
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
        boolean scale,
        Optional<GiverSpec> giver,
        List<Stage> stages,
        Optional<Availability> availability,
        int cooldown,
        Extras extras) {

    /** The fields past the sixteen a codec group can hold, inline in the same object. */
    public record Extras(Optional<Boolean> counts, Optional<String> locked) {
        public static final com.mojang.serialization.MapCodec<Extras> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.optionalFieldOf("counts").forGetter(Extras::counts),
                Codec.STRING.optionalFieldOf("locked").forGetter(Extras::locked))
                .apply(i, Extras::new));
        public static final Extras NONE = new Extras(Optional.empty(), Optional.empty());
    }

    public Optional<Boolean> counts() { return extras.counts(); }
    /** The in-character line for a refusal. */
    public Optional<String> locked() { return extras.locked(); }

    /** Does finishing this move the progress figure? Unsaid: main-chapter quests that are not repeatable. */
    public boolean countsToward(Chapter chapter) {
        return extras.counts().orElse(chapter.main() && !repeatable);
    }


    /** The quest as beats: its stages, or one stage made of its objectives. Never empty. */
    /** Every ending this quest can reach, in file order. */
    public List<String> endings() {
        List<String> out = new java.util.ArrayList<>();
        for (Stage s : beats()) {
            s.ending().filter(e -> !out.contains(e)).ifPresent(out::add);
            for (Choice c : s.choices()) c.ending().filter(e -> !out.contains(e)).ifPresent(out::add);
        }
        return out;
    }

    public List<Stage> beats() {
        return stages.isEmpty() ? List.of(Stage.of(objectives)) : stages;
    }

    /** The objectives of one beat, clamped to the last -- a saved stage past the end reads as the end. */
    public List<ObjectiveSpec> objectivesAt(int stage) {
        List<Stage> b = beats();
        return b.get(Math.min(Math.max(stage, 0), b.size() - 1)).objectives();
    }

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
            Codec.BOOL.optionalFieldOf("scale", true).forGetter(Quest::scale),
            GiverTypes.CODEC.optionalFieldOf("giver").forGetter(Quest::giver),
            Stage.CODEC.listOf().optionalFieldOf("stages", List.of()).forGetter(Quest::stages),
            Availability.CODEC.optionalFieldOf("availability").forGetter(Quest::availability),
            Codec.INT.optionalFieldOf("cooldown", 0).forGetter(Quest::cooldown),
            Extras.MAP_CODEC.forGetter(Quest::extras))
            .apply(i, Quest::new));
}
