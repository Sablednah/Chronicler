package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.Identifier;

/**
 * What makes a quest a <b>mini quest</b>: a template whose holes are filled when it starts.
 *
 * <p>Content is a frozen registry, so an errand that happens a hundred times -- walk this
 * stranger to the nearest village, bring that villager five of something -- cannot be a
 * hundred quest files. It is one file with {@code {slot}} holes anywhere a value goes, and a
 * {@code mini:} block saying how each slot is found: a pick from a pool, a number in a range,
 * the nearest structure or CityWorld lot, a spot nearby, a block, a person Cast places. The
 * values are saved on the journal entry, so every line the player reads and every objective
 * the engine measures is the filled-in quest. {@code docs/DESIGN.md} "Mini quests".</p>
 *
 * @param slots in file order; a slot may use the values of the slots before it
 * @param spawn optional: offer this in the wild, unasked
 */
public record Mini(Map<String, Slot> slots, Optional<Wild> spawn) {

    /** Slot names a hole may not use: a {@code command} reward's own placeholders. */
    public static final List<String> RESERVED = List.of("player", "uuid", "quest", "x", "y", "z", "prefix");

    public static final Codec<Mini> CODEC = RecordCodecBuilder.<Mini>create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Slot.CODEC).optionalFieldOf("slots", Map.of()).forGetter(Mini::slots),
            Wild.CODEC.optionalFieldOf("spawn").forGetter(Mini::spawn))
            .apply(i, Mini::new)).validate(Mini::validate);

    private static DataResult<Mini> validate(Mini m) {
        for (var e : m.slots.entrySet()) {
            if (!e.getKey().matches("[a-z0-9_]+")) return DataResult.error(() -> "slot name '" + e.getKey() + "' must be lower-case letters, digits and _");
            if (RESERVED.contains(e.getKey())) return DataResult.error(() -> "slot name '" + e.getKey() + "' is taken by the command placeholders " + RESERVED);
        }
        if (m.spawn.isPresent()) {
            Wild w = m.spawn.get();
            if (w.giver().isPresent() && !"npc".equals(m.slots.getOrDefault(w.giver().get(), Slot.NONE).type())) {
                return DataResult.error(() -> "spawn.giver '" + w.giver().get() + "' must name an npc slot");
            }
            if (w.block().isPresent() && !"block".equals(m.slots.getOrDefault(w.block().get(), Slot.NONE).type())) {
                return DataResult.error(() -> "spawn.block '" + w.block().get() + "' must name a block slot");
            }
            if (w.giver().isEmpty() && w.block().isEmpty()) return DataResult.error(() -> "spawn needs a giver (an npc slot) or a block (a block slot) to make the offer");
        }
        return DataResult.success(m);
    }

    /** Where to look for a place: at most one of these, by slot type. */
    public record Find(Optional<String> structure, Optional<String> lot, Optional<String> schematic, Optional<String> block, Optional<Identifier> quest) {
        public static final MapCodec<Find> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("structure").forGetter(Find::structure),
                Codec.STRING.optionalFieldOf("lot").forGetter(Find::lot),
                Codec.STRING.optionalFieldOf("schematic").forGetter(Find::schematic),
                Codec.STRING.optionalFieldOf("block").forGetter(Find::block),
                ChroniclerIds.CODEC.optionalFieldOf("quest").forGetter(Find::quest))
                .apply(i, Find::new));
    }

    /** The person an {@code npc} slot places: a Cast human (with {@code skin}) or a creature ({@code entity}). */
    public record Person(Optional<String> name, Optional<String> skin, Optional<Identifier> entity,
            Map<String, String> equipment, Optional<String> greeting, boolean keep) {
        public static final MapCodec<Person> MAP_CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.optionalFieldOf("name").forGetter(Person::name),
                Codec.STRING.optionalFieldOf("skin").forGetter(Person::skin),
                Identifier.CODEC.optionalFieldOf("entity").forGetter(Person::entity),
                Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("equipment", Map.of()).forGetter(Person::equipment),
                Codec.STRING.optionalFieldOf("greeting").forGetter(Person::greeting),
                Codec.BOOL.optionalFieldOf("keep", false).forGetter(Person::keep))
                .apply(i, Person::new));
    }

    /**
     * One slot. {@code type} says how it is filled:
     * <ul>
     * <li>{@code pick} -- one of {@code pool}, at random. {@code {s}}, and {@code {s.name}} prettified.</li>
     * <li>{@code number} -- {@code min}..{@code max} inclusive. {@code {s}}.</li>
     * <li>{@code here} -- where the quest starts (the player, the wild giver, a chained quest's {@code near}).</li>
     * <li>{@code around} -- a dry surface spot {@code min}..{@code max} blocks from the anchor.</li>
     * <li>{@code structure} -- the nearest {@code structure} (an id or {@code #tag}) within {@code radius}.</li>
     * <li>{@code lot} -- the nearest CityWorld lot matching {@code lot} (a word, "HouseLot") or {@code schematic} ("chayats-bank") within {@code radius}.</li>
     * <li>{@code block} -- the nearest {@code block} (an id or {@code #tag}) within {@code radius}.</li>
     * <li>{@code giver} -- where the giver of {@code quest} stands (Okafor, the camp).</li>
     * <li>{@code given} -- a position handed over by whoever starts it (StoryTeller, the command).</li>
     * <li>{@code npc} -- a person placed at the anchor, or {@code min}..{@code max} from it. With
     * {@code near} and no {@code max}, placed genuinely inside whatever is there (a scan down from
     * its roof for a sheltered, walkable, floored spot), bounded by {@code y_min}/{@code y_max} --
     * near sea level by default, so the search cannot wander into a cave far underneath or land on
     * a roof high above; set them to widen or move that band for a place that is neither.</li>
     * </ul>
     * A place gives {@code {s}} (its {@code label}), {@code {s.x}}, {@code {s.y}}, {@code {s.z}},
     * {@code {s.pos}} and {@code {s.dim}}; a person also {@code {s.id}}, and {@code {s}} is their name.
     * {@code near} anchors the search at an earlier slot instead of the start.
     */
    public record Slot(String type, List<String> pool, int min, int max, Find find, int radius,
            Optional<String> near, Optional<String> label, Person person, Optional<Integer> yMin, Optional<Integer> yMax) {

        static final Slot NONE = new Slot("", List.of(), 0, 0, new Find(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()),
                0, Optional.empty(), Optional.empty(), new Person(Optional.empty(), Optional.empty(), Optional.empty(), Map.of(), Optional.empty(), false),
                Optional.empty(), Optional.empty());

        public static final List<String> TYPES = List.of("pick", "number", "here", "around", "structure", "lot", "block", "giver", "given", "npc");
        /** The types that stand for a place, and so give x, y, z, pos and dim. */
        public static final List<String> PLACES = List.of("here", "around", "structure", "lot", "block", "giver", "given", "npc");

        public static final Codec<Slot> CODEC = RecordCodecBuilder.<Slot>create(i -> i.group(
                Codec.STRING.fieldOf("type").forGetter(Slot::type),
                Codec.STRING.listOf().optionalFieldOf("pool", List.of()).forGetter(Slot::pool),
                Codec.INT.optionalFieldOf("min", 0).forGetter(Slot::min),
                Codec.INT.optionalFieldOf("max", 0).forGetter(Slot::max),
                Find.MAP_CODEC.forGetter(Slot::find),
                Codec.INT.optionalFieldOf("radius", 0).forGetter(Slot::radius),
                Codec.STRING.optionalFieldOf("near").forGetter(Slot::near),
                Codec.STRING.optionalFieldOf("label").forGetter(Slot::label),
                Person.MAP_CODEC.forGetter(Slot::person),
                Codec.INT.optionalFieldOf("y_min").forGetter(Slot::yMin),
                Codec.INT.optionalFieldOf("y_max").forGetter(Slot::yMax))
                .apply(i, Slot::new)).validate(Slot::validate);

        private static DataResult<Slot> validate(Slot s) {
            if (!TYPES.contains(s.type)) return DataResult.error(() -> "slot type '" + s.type + "' is not one of " + TYPES);
            return switch (s.type) {
                case "pick" -> s.pool.isEmpty() ? DataResult.error(() -> "a pick slot needs a pool") : DataResult.success(s);
                case "number", "around" -> s.max < s.min ? DataResult.error(() -> "a " + s.type + " slot needs max >= min") : DataResult.success(s);
                case "structure" -> s.find.structure().isEmpty() ? DataResult.error(() -> "a structure slot needs 'structure'") : DataResult.success(s);
                case "lot" -> s.find.lot().isEmpty() && s.find.schematic().isEmpty() ? DataResult.error(() -> "a lot slot needs 'lot' (a lot word) or 'schematic' (a schematic's name)") : DataResult.success(s);
                case "block" -> s.find.block().isEmpty() ? DataResult.error(() -> "a block slot needs 'block'") : DataResult.success(s);
                case "giver" -> s.find.quest().isEmpty() ? DataResult.error(() -> "a giver slot needs 'quest'") : DataResult.success(s);
                case "npc" -> s.person.name().isEmpty() ? DataResult.error(() -> "an npc slot needs 'name'") : DataResult.success(s);
                default -> DataResult.success(s);
            };
        }

        public boolean isPlace() { return PLACES.contains(type); }

        /** How far a search looks, in blocks, when the file does not say. */
        public int searchRadius() {
            if (radius > 0) return radius;
            return switch (type) {
                case "structure" -> 1600;
                case "lot" -> 768;
                case "block" -> 16;
                default -> 0;
            };
        }
    }

    /**
     * Offered unasked: while a player is in {@code place}, once every {@code every} seconds, with
     * {@code chance}, if fewer than {@code cap} such offers are standing in the world. The offer is
     * made by {@code giver} (an npc slot, placed for it) or {@code block} (a block slot, found
     * for it), with {@code say} as the opening line; unaccepted, it lapses after {@code lapse}
     * seconds and anyone placed for it leaves.
     */
    public record Wild(Place place, double chance, int every, int cap, Optional<String> giver, Optional<String> block,
            int lapse, Optional<String> say) {
        public static final Codec<Wild> CODEC = RecordCodecBuilder.create(i -> i.group(
                Place.CODEC.fieldOf("place").forGetter(Wild::place),
                Codec.doubleRange(0.0D, 1.0D).optionalFieldOf("chance", 0.1D).forGetter(Wild::chance),
                Codec.intRange(5, 86400).optionalFieldOf("every", 60).forGetter(Wild::every),
                Codec.intRange(1, 1000).optionalFieldOf("cap", 1).forGetter(Wild::cap),
                Codec.STRING.optionalFieldOf("giver").forGetter(Wild::giver),
                Codec.STRING.optionalFieldOf("block").forGetter(Wild::block),
                Codec.intRange(10, 86400).optionalFieldOf("lapse", 300).forGetter(Wild::lapse),
                Codec.STRING.optionalFieldOf("say").forGetter(Wild::say))
                .apply(i, Wild::new));
    }
}
