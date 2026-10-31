package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

/**
 * Other words for a quest (or a chapter) once the world has moved on: the journal speaking of somebody
 * in the past tense after they are gone. A quest's {@code variants} are tried in file order when its text
 * is shown and the first whose {@code when} holds for the reader wins; every field it leaves out falls back
 * to the quest's own. Words only -- objectives, rewards and the order of beats never change.
 *
 * <p>{@code when} takes the same gates as {@code availability} (world {@code flags}, {@code player_flags},
 * race, class, karma, level, reputation). {@code stages} is keyed by stage number from 1, as {@code next}
 * and {@code fail} are; a stage's {@code choices} likewise. On a chapter only {@code name},
 * {@code description} and {@code recap} mean anything, and a chapter variant naming the rest is refused.</p>
 *
 * @param when         whose story this is
 * @param stages       per-beat text, by stage number from 1
 * @param recapEndings merged over the quest's own, line by line
 * @param recapFlags   likewise
 */
public record Variant(Availability when, Optional<String> name, Optional<String> description, Optional<String> locked,
        Optional<String> recap, Map<String, String> recapEndings, Map<String, String> recapFlags, Map<Integer, StageText> stages) {

    /**
     * A beat's words: what it narrates, its objectives' labels and its choices' buttons and lines, each by
     * number from 1 in file order. Only an objective type that takes a {@code label} can be relabelled.
     */
    public record StageText(Optional<String> text, Map<Integer, ObjectiveText> objectives, Map<Integer, ChoiceText> choices) {
        /** "1", "2", ...: a JSON key is always a string, and YAML's bare 1 arrives as one. Declared before CODEC, which reads it. */
        static final Codec<Integer> NUMBER = Codec.STRING.comapFlatMap(s -> {
            try {
                int n = Integer.parseInt(s.trim());
                return n >= 1 ? DataResult.success(n) : DataResult.error(() -> "numbered from 1, not " + s);
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "a stage or choice number (1, 2, ...), not \"" + s + "\"");
            }
        }, String::valueOf);
        public static final Codec<StageText> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("text").forGetter(StageText::text),
                Codec.unboundedMap(NUMBER, ObjectiveText.CODEC).optionalFieldOf("objectives", Map.of()).forGetter(StageText::objectives),
                Codec.unboundedMap(NUMBER, ChoiceText.CODEC).optionalFieldOf("choices", Map.of()).forGetter(StageText::choices))
                .apply(i, StageText::new));
    }

    public record ObjectiveText(String label) {
        public static final Codec<ObjectiveText> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("label").forGetter(ObjectiveText::label))
                .apply(i, ObjectiveText::new));
    }

    public record ChoiceText(Optional<String> label, Optional<String> text) {
        public static final Codec<ChoiceText> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("label").forGetter(ChoiceText::label),
                Codec.STRING.optionalFieldOf("text").forGetter(ChoiceText::text))
                .apply(i, ChoiceText::new));
    }

    public static final Codec<Variant> CODEC = RecordCodecBuilder.create(i -> i.group(
            Availability.CODEC.fieldOf("when").forGetter(Variant::when),
            Codec.STRING.optionalFieldOf("name").forGetter(Variant::name),
            Codec.STRING.optionalFieldOf("description").forGetter(Variant::description),
            Codec.STRING.optionalFieldOf("locked").forGetter(Variant::locked),
            Codec.STRING.optionalFieldOf("recap").forGetter(Variant::recap),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("recap_endings", Map.of()).forGetter(Variant::recapEndings),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("recap_flags", Map.of()).forGetter(Variant::recapFlags),
            Codec.unboundedMap(StageText.NUMBER, StageText.CODEC).optionalFieldOf("stages", Map.of()).forGetter(Variant::stages))
            .apply(i, Variant::new));

    /** What a chapter cannot use, named, so the refusal says which field to move. */
    public Optional<String> notForAChapter() {
        if (locked.isPresent()) return Optional.of("locked");
        if (!recapEndings.isEmpty()) return Optional.of("recap_endings");
        if (!recapFlags.isEmpty()) return Optional.of("recap_flags");
        if (!stages.isEmpty()) return Optional.of("stages");
        return Optional.empty();
    }

    /**
     * The beats reworded. Effects and choice outcomes are the same objects; a relabelled objective is a new
     * one (decoded again with the label set), so a caller that finds objectives by identity must hold on
     * to the reworded quest rather than build it afresh each read.
     */
    List<Stage> reword(List<Stage> beats, DynamicOps<JsonElement> ops) {
        if (stages.isEmpty()) return beats;
        List<Stage> out = new java.util.ArrayList<>(beats.size());
        for (int n = 0; n < beats.size(); n++) {
            Stage s = beats.get(n);
            StageText t = stages.get(n + 1);
            if (t == null) { out.add(s); continue; }
            List<Choice> choices = s.choices();
            if (!t.choices().isEmpty()) {
                choices = new java.util.ArrayList<>(s.choices().size());
                for (int c = 0; c < s.choices().size(); c++) {
                    Choice ch = s.choices().get(c);
                    ChoiceText ct = t.choices().get(c + 1);
                    choices.add(ct == null ? ch : new Choice(ct.label().orElse(ch.label()), ct.text().or(ch::text),
                            ch.effects(), ch.next(), ch.end(), ch.start(), ch.ending()));
                }
            }
            List<ObjectiveSpec> objectives = s.objectives();
            if (!t.objectives().isEmpty()) {
                objectives = new java.util.ArrayList<>(s.objectives());
                for (var o : t.objectives().entrySet()) {
                    int at = o.getKey() - 1;
                    if (at < objectives.size()) objectives.set(at, relabel(objectives.get(at), o.getValue().label(), ops).orElse(objectives.get(at)));
                }
                objectives = List.copyOf(objectives);
            }
            out.add(new Stage(t.text().or(s::text), objectives, s.onEnter(), s.onComplete(), choices,
                    s.deadline(), s.onFail(), s.fail(), s.ending(), s.end()));
        }
        return out;
    }

    /** The objective with {@code label} set, through its own codec; empty if it will not encode or decode that way. */
    static Optional<ObjectiveSpec> relabel(ObjectiveSpec spec, String label, DynamicOps<JsonElement> ops) {
        return ObjectiveTypes.CODEC.encodeStart(ops, spec).result()
                .filter(JsonElement::isJsonObject)
                .flatMap(j -> {
                    JsonObject o = j.getAsJsonObject().deepCopy();
                    o.addProperty("label", label);
                    return ObjectiveTypes.CODEC.parse(ops, o).result();
                });
    }

    /** A variant numbering a stage, objective or choice the quest does not have is a typo; say so at load. */
    Optional<String> mismatch(List<Stage> beats, boolean staged) {
        for (var e : stages.entrySet()) {
            if (!staged) return Optional.of("stages: this quest has no stages, so there is no stage " + e.getKey() + " to reword");
            if (e.getKey() > beats.size()) return Optional.of("stages: stage " + e.getKey() + ", but the quest has " + beats.size());
            Stage beat = beats.get(e.getKey() - 1);
            for (int o : e.getValue().objectives().keySet()) {
                if (o > beat.objectives().size()) {
                    return Optional.of("stages." + e.getKey() + ".objectives: objective " + o + ", but that stage has " + beat.objectives().size());
                }
                ObjectiveSpec spec = beat.objectives().get(o - 1);
                // Encodable without the registries here (the usual case): prove the label survives a round trip,
                // codec only (this also runs on a client, where Lang has nothing loaded). Otherwise trust it.
                var json = com.mojang.serialization.JsonOps.INSTANCE;
                var plain = ObjectiveTypes.CODEC.encodeStart(json, spec).result().filter(JsonElement::isJsonObject);
                if (plain.isPresent() && relabel(spec, "-", json)
                        .flatMap(r -> ObjectiveTypes.CODEC.encodeStart(json, r).result())
                        .map(r -> !r.isJsonObject() || !r.getAsJsonObject().has("label")).orElse(true)) {
                    String type = plain.map(j -> j.getAsJsonObject().get("type")).map(JsonElement::getAsString).orElse("this type");
                    return Optional.of("stages." + e.getKey() + ".objectives." + o + ": a " + type + " objective takes no label to reword");
                }
            }
            int choices = beat.choices().size();
            for (int c : e.getValue().choices().keySet()) {
                if (c > choices) return Optional.of("stages." + e.getKey() + ".choices: choice " + c + ", but that stage has " + choices);
            }
        }
        return Optional.empty();
    }
}
