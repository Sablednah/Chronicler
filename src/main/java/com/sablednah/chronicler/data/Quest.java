package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.core.QuestVisibility;

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
 * @param hidden      not listed until started -- shorthand for {@code visibility: found}
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

    /**
     * The fields past the sixteen a codec group can hold, inline in the same object.
     *
     * @param visibility when the quest is listed; absent means {@code found} for a hidden quest, else {@code always}
     * @param icon       an item id drawn for the quest in the journal panel
     */
    public record Extras(Optional<Boolean> counts, Optional<String> locked,
            Optional<QuestVisibility> visibility, Optional<Identifier> icon, Optional<Mini> mini, Optional<JsonElement> template) {
        /**
         * @param mini     present on a mini quest: its slots and wild spawning
         * @param template the file as written, holes and all, kept to be filled when it starts. Never
         *                 encoded; attached by {@link Quest#CODEC} when a file with {@code mini} is read
         */
        public Extras(Optional<Boolean> counts, Optional<String> locked, Optional<QuestVisibility> visibility, Optional<Identifier> icon) {
            this(counts, locked, visibility, icon, Optional.empty(), Optional.empty());
        }
        public static final com.mojang.serialization.MapCodec<Extras> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.BOOL.optionalFieldOf("counts").forGetter(Extras::counts),
                Codec.STRING.optionalFieldOf("locked").forGetter(Extras::locked),
                QuestVisibility.CODEC.optionalFieldOf("visibility").forGetter(Extras::visibility),
                Identifier.CODEC.optionalFieldOf("icon").forGetter(Extras::icon),
                Mini.CODEC.optionalFieldOf("mini").forGetter(Extras::mini))
                .apply(i, (c, l, v, ic, m) -> new Extras(c, l, v, ic, m, Optional.empty())));
        public static final Extras NONE = new Extras(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public Optional<Boolean> counts() { return extras.counts(); }
    /** The in-character line for a refusal. */
    public Optional<String> locked() { return extras.locked(); }
    /** When this is listed to a player who has not started it. {@code hidden: true} is shorthand for {@code found}. */
    public QuestVisibility visibility() { return extras.visibility().orElse(hidden ? QuestVisibility.FOUND : QuestVisibility.ALWAYS); }
    public Optional<Identifier> icon() { return extras.icon(); }
    /** Present on a mini quest. */
    public Optional<Mini> mini() { return extras.mini(); }
    /** A mini quest's file with its holes, to be filled at start. */
    public Optional<JsonElement> template() { return extras.template(); }

    private Quest withTemplate(JsonElement raw) {
        return new Quest(name, description, chapter, requires, objectives, rewards, repeatable, hidden, order, scope, scale, giver,
                stages, availability, cooldown, new Extras(extras.counts(), extras.locked(), extras.visibility(), extras.icon(), extras.mini(), Optional.of(raw)));
    }

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

    /** The record as written, holes already filled. */
    public static final Codec<Quest> BASE_CODEC = RecordCodecBuilder.create(i -> i.group(
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

    /**
     * A quest file. One with a {@code mini} block is a template: it is checked by filling every
     * hole with stand-in values ({@link Slots#samples}) and decoding that -- so a template with a
     * mistake is refused at load like any other bad file -- and the file as written is kept to be
     * filled for real when the quest starts. Encoding writes the stand-in quest, which is all a
     * client needs to list it.
     */
    public static final Codec<Quest> CODEC = new Codec<>() {
        @Override
        public <T> DataResult<Pair<Quest, T>> decode(DynamicOps<T> ops, T input) {
            JsonElement json = ops.convertTo(JsonOps.INSTANCE, input);
            if (!(json instanceof JsonObject o) || !o.has("mini")) return BASE_CODEC.decode(ops, input);
            DataResult<Mini> mini = Mini.CODEC.parse(JsonOps.INSTANCE, o.get("mini"));
            if (mini.error().isPresent()) return DataResult.error(() -> "mini: " + mini.error().get().message());
            JsonElement sample = Slots.fill(json, Slots.samples(mini.getOrThrow()), mini.getOrThrow());
            return BASE_CODEC.decode(ops, JsonOps.INSTANCE.convertTo(ops, sample))
                    .mapError(e -> "mini template (filled with stand-in values): " + e)
                    .map(p -> Pair.of(p.getFirst().withTemplate(json), input));
        }

        @Override
        public <T> DataResult<T> encode(Quest quest, DynamicOps<T> ops, T prefix) {
            return BASE_CODEC.encode(quest, ops, prefix);
        }
    };
}
