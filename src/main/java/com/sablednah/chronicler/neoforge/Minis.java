package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.Mini;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.Slots;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.Vec3;

/**
 * Mini quests at run time: filling the slots, the filled-in quest, offers that stand
 * for a while, the ones found in the wild, and the people placed for them.
 *
 * <p><b>One of each per player.</b> A mini quest is an ordinary quest id; its slots live
 * on the journal entry, so the journal, the panel, {@code /quest} and parties all work as
 * they do for any quest, and the same errand cannot be running twice for one player.
 * Variety is more template files, not more copies.</p>
 *
 * <p><b>What is not saved.</b> Offers (pending) are memory, like givers' cooldowns: a
 * restart loses them, and any person placed for one is taken away on the next start.
 * An accepted quest's slots and its people are saved, with the journal and Cast.</p>
 */
public final class Minis {

    /** Where a mini quest starts: its {@code here}, and where its searches begin. */
    public record Anchor(ServerLevel level, BlockPos pos) {
        public static Anchor of(ServerPlayer player) {
            return new Anchor(player.level(), player.blockPosition());
        }
    }

    /** The outcome of filling a template: the values, or why not, with anyone placed along the way already removed. */
    public record Resolved(Map<String, String> values, Optional<String> failure) {
        static Resolved fail(String why) { return new Resolved(Map.of(), Optional.of(why)); }
    }

    /** An offer standing: accepted, it becomes the quest; ignored, it lapses and its people leave. */
    public record Pending(Identifier quest, UUID owner, Map<String, String> values, List<UUID> npcs, Optional<String> blockKey,
            Identifier dimension, Vec3 where, long expires, boolean wild) {}

    private static final Map<String, Quest> INSTANCES = new HashMap<>();
    private static final Map<UUID, Map<Identifier, Pending>> PENDING = new HashMap<>();
    /** Templates with a {@code spawn}, indexed at start. */
    private static final List<Identifier> WILD = new ArrayList<>();
    /** player -> template -> game time of the last wild roll. */
    private static final Map<UUID, Map<Identifier, Long>> ROLLED = new HashMap<>();

    // --- the filled-in quest ---

    /**
     * The quest with these slots filled. Cached per slot set, so the same objective object comes back
     * on every read -- {@code wait} and {@code escort} find their entry by identity.
     */
    public static Quest instance(MinecraftServer server, Identifier id, Quest base, Map<String, String> slots) {
        if (base.mini().isEmpty() || base.template().isEmpty() || slots.isEmpty()) return base;
        String key = id + "|" + new TreeMap<>(slots);
        Quest cached = INSTANCES.get(key);
        if (cached != null) return cached;
        if (INSTANCES.size() > 4096) INSTANCES.clear();
        JsonElement filled = Slots.fill(base.template().get(), slots, base.mini().get());
        var ops = RegistryOps.create(JsonOps.INSTANCE, server.registryAccess());
        Quest out = Quest.BASE_CODEC.parse(ops, filled).resultOrPartial(e ->
                Chronicler.LOGGER.error("Chronicler: mini quest {} does not decode with its slots {}: {} -- showing the template", id, slots, e)).orElse(base);
        INSTANCES.put(key, out);
        return out;
    }

    /** Every mini quest template loaded, by id. */
    public static List<Identifier> templates(MinecraftServer server) {
        List<Identifier> out = new ArrayList<>();
        QuestEngine.quests(server).listElements().forEach(h -> { if (h.value().mini().isPresent()) out.add(h.key().identifier()); });
        out.sort(java.util.Comparator.comparing(Identifier::toString));
        return out;
    }

    // --- filling the slots ---

    /**
     * Fill a template's slots for a player, in file order. {@code given} values win over finding
     * one (a position as "x y z" or "dimension x y z", a person's id, a pick). A slot that cannot
     * be filled refuses the whole quest with a reason a player can read, and anyone already placed
     * for it is taken away again.
     */
    public static Resolved resolve(ServerPlayer player, Identifier id, Quest base, Map<String, String> given, Anchor anchor, boolean pending) {
        Mini mini = base.mini().orElseThrow();
        MinecraftServer server = anchor.level().getServer();
        Map<String, String> v = new LinkedHashMap<>();
        List<UUID> placed = new ArrayList<>();
        for (var e : mini.slots().entrySet()) {
            String name = e.getKey();
            Mini.Slot slot = e.getValue();
            String label = Slots.text(slot.label().orElseGet(() -> defaultLabel(slot)), v, mini);
            Anchor from = slot.near().map(n -> anchorOf(server, v, n)).orElse(anchor);
            if (from == null) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.near", "slot", slot.near().orElse("?"))); }
            ServerLevel level = from.level();
            String dim = level.dimension().identifier().toString();
            if (given.containsKey(name) && !slot.type().equals("npc")) {
                String g = given.get(name);
                if (slot.isPlace()) {
                    Anchor at = parsePlace(server, g, level);
                    if (at == null) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.given", "slot", name)); }
                    Slots.putPlace(v, name, label, at.level().dimension().identifier().toString(), at.pos().getX(), at.pos().getY(), at.pos().getZ());
                } else {
                    v.put(name, g);
                    if (slot.type().equals("pick")) v.put(name + ".name", Lang.pretty(g));
                }
                continue;
            }
            var rng = level.getRandom();
            switch (slot.type()) {
                case "pick" -> {
                    String pick = slot.pool().get(rng.nextInt(slot.pool().size()));
                    v.put(name, pick);
                    v.put(name + ".name", Lang.pretty(pick));
                }
                case "number" -> v.put(name, Integer.toString(slot.min() + rng.nextInt(slot.max() - slot.min() + 1)));
                case "here" -> Slots.putPlace(v, name, label, dim, from.pos().getX(), from.pos().getY(), from.pos().getZ());
                case "around" -> {
                    BlockPos at = around(level, from.pos(), slot.min() <= 0 && slot.max() <= 0 ? 16 : slot.min(), slot.max() <= 0 ? 48 : slot.max());
                    Slots.putPlace(v, name, label, dim, at.getX(), at.getY(), at.getZ());
                }
                case "structure" -> {
                    Optional<BlockPos> at = nearestStructure(level, from.pos(), slot.find().structure().get(), slot.searchRadius());
                    if (at.isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.structure", "what", label, "radius", slot.searchRadius())); }
                    Slots.putPlace(v, name, label, dim, at.get().getX(), at.get().getY(), at.get().getZ());
                }
                case "lot" -> {
                    if (Lots.describe(level, from.pos()).isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.no_city", "what", label)); }
                    Optional<BlockPos> at = slot.find().schematic().isPresent()
                            ? nearestLot(level, from.pos(), words -> Places.schematicMatches(words, slot.find().schematic().get()), slot.searchRadius())
                            : nearestLot(level, from.pos(), words -> Places.lotMatches(words, slot.find().lot().get()), slot.searchRadius());
                    if (at.isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.lot", "what", label, "radius", slot.searchRadius())); }
                    Slots.putPlace(v, name, label, dim, at.get().getX(), at.get().getY(), at.get().getZ());
                }
                case "block" -> {
                    Optional<BlockPos> at = nearestBlock(level, from.pos(), slot.find().block().get(), Math.min(48, slot.searchRadius()));
                    if (at.isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.block", "what", label, "radius", Math.min(48, slot.searchRadius()))); }
                    Slots.putPlace(v, name, label, dim, at.get().getX(), at.get().getY(), at.get().getZ());
                }
                case "giver" -> {
                    Identifier of = slot.find().quest().get();
                    Optional<Givers.Where> w = Givers.positionOf(server, of);
                    String who = slot.label().isPresent() ? label : Givers.nameOf(of);
                    if (w.isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.giver", "who", who)); }
                    BlockPos at = BlockPos.containing(w.get().pos());
                    Slots.putPlace(v, name, who, w.get().level().toString(), at.getX(), at.getY(), at.getZ());
                }
                case "given" -> { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.given", "slot", name)); }
                case "npc" -> {
                    if (given.containsKey(name)) {
                        // An existing person handed over by id (a wild giver standing for the offer, a StoryTeller NPC).
                        String npcName = Slots.text(slot.person().name().get(), v, mini);
                        UUID uid = parseUuid(given.get(name));
                        var p = uid == null || !Npcs.available() ? Optional.<Npcs.Placed>empty() : Npcs.provider().get().byId(server, uid);
                        if (p.isEmpty()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.npc", "name", npcName)); }
                        BlockPos at = BlockPos.containing(p.get().pos());
                        Slots.putPlace(v, name, Feedback.colored(p.get().name()).getString(), p.get().dimension().toString(), at.getX(), at.getY(), at.getZ());
                        v.put(name + ".id", uid.toString());
                        continue;
                    }
                    String npcName = Slots.text(slot.person().name().get(), v, mini);
                    if (!Npcs.available()) { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.npc", "name", npcName)); }
                    // A person placed at a found place (an outpost, a house) stands INSIDE it -- a lot or
                    // structure's own coordinate is the heightmap's highest block, which for anything
                    // built is its ROOF (Mum, standing near "mums", first landed on top of her own house;
                    // fixed once already to step outdoors, still not what "near a house" should mean for
                    // a person). Placed exactly AT one (no near) is left alone -- that is a deliberate
                    // coordinate (a giver's own spot, a chapter's camp).
                    BlockPos at = slot.max() > 0 ? around(level, from.pos(), slot.min(), slot.max())
                            : slot.near().isPresent() ? interiorNear(level, from.pos(), 10,
                                    slot.yMin().orElseGet(() -> defaultInteriorMinY(level)), slot.yMax().orElseGet(() -> defaultInteriorMaxY(level)))
                            : from.pos();
                    Vec3 pos = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
                    float yaw = rng.nextFloat() * 360F;
                    UUID uid;
                    try {
                        uid = slot.person().entity().isPresent()
                                ? Npcs.provider().get().spawnMob(level, pos, yaw, slot.person().entity().get(), npcName)
                                : Npcs.provider().get().spawnHuman(level, pos, yaw, npcName, slot.person().skin().map(sk -> Slots.text(sk, v, mini)));
                    } catch (RuntimeException ex) {
                        Chronicler.LOGGER.error("Chronicler: mini quest {} could not place {}", id, npcName, ex);
                        undo(server, placed);
                        return Resolved.fail(Lang.fmt("mini.why.npc", "name", npcName));
                    }
                    placed.add(uid);
                    if (!slot.person().equipment().isEmpty()) Npcs.provider().get().equip(server, uid, slot.person().equipment());
                    if (slot.person().scale() != 1D) Npcs.provider().get().setScale(server, uid, slot.person().scale());
                    GiverStore store = GiverStore.get(server);
                    store.setNpc(uid, id);
                    store.setMini(uid, id, pending);
                    Slots.putPlace(v, name, Feedback.colored(npcName).getString(), dim, at.getX(), at.getY(), at.getZ());
                    v.put(name + ".id", uid.toString());
                }
                default -> { undo(server, placed); return Resolved.fail(Lang.fmt("mini.why.type", "slot", name)); }
            }
        }
        return new Resolved(v, Optional.empty());
    }

    private static String defaultLabel(Mini.Slot slot) {
        return switch (slot.type()) {
            case "structure" -> Lang.pretty(slot.find().structure().orElse("").replace("#", ""));
            case "lot" -> Lang.pretty(slot.find().schematic().orElse(slot.find().lot().orElse("")).replace('-', '_').replace(' ', '_'));
            case "block" -> Lang.pretty(slot.find().block().orElse("").replace("#", ""));
            case "around" -> Lang.get("mini.label.around");
            case "given" -> Lang.get("mini.label.given");
            default -> Lang.get("mini.label.here");
        };
    }

    /** An earlier slot as an anchor, or null if it is not a place or not filled. */
    private static Anchor anchorOf(MinecraftServer server, Map<String, String> v, String slot) {
        String dim = v.get(slot + ".dim"), pos = v.get(slot + ".pos");
        if (dim == null || pos == null) return null;
        return parsePlace(server, dim + " " + pos, null);
    }

    /** "x y z" (in {@code fallback}'s level) or "dimension x y z". */
    static Anchor parsePlace(MinecraftServer server, String text, ServerLevel fallback) {
        String[] p = text.trim().split("\\s+");
        try {
            ServerLevel level = fallback;
            int o = 0;
            if (p.length == 4) {
                Identifier dim = Identifier.tryParse(p[0]);
                level = dim == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
                o = 1;
            } else if (p.length != 3) {
                return null;
            }
            if (level == null) return null;
            return new Anchor(level, new BlockPos(Integer.parseInt(p[o]), Integer.parseInt(p[o + 1]), Integer.parseInt(p[o + 2])));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static UUID parseUuid(String s) {
        try { return UUID.fromString(s.trim()); } catch (IllegalArgumentException e) { return null; }
    }

    /** A dry surface spot {@code min}..{@code max} blocks away, at a random bearing. */
    private static BlockPos around(ServerLevel level, BlockPos from, int min, int max) {
        var rng = level.getRandom();
        double angle = rng.nextDouble() * Math.PI * 2;
        double dist = min + rng.nextDouble() * Math.max(0, max - min);
        return Givers.dryColumn(level, (int) Math.round(from.getX() + Math.cos(angle) * dist), (int) Math.round(from.getZ() + Math.sin(angle) * dist));
    }

    /** {@link #interiorNear(ServerLevel, BlockPos, int, int, int)}, bounded to near sea level. */
    static BlockPos interiorNear(ServerLevel level, BlockPos from, int radius) {
        return interiorNear(level, from, radius, defaultInteriorMinY(level), defaultInteriorMaxY(level));
    }

    static int defaultInteriorMinY(ServerLevel level) { return level.getSeaLevel() - 8; }
    static int defaultInteriorMaxY(ServerLevel level) { return level.getSeaLevel() + 24; }

    /**
     * A sheltered floor within {@code radius} blocks of {@code from}, between {@code minY} and
     * {@code maxY} -- CityWorld's own API only ever answers "what kind of place is this chunk" (no
     * bounding box, no door, no floor level: a lot is chunk-granular metadata, not geometry), so
     * there is no API to ask for a building's inside. This asks the world instead: scan down from
     * each column's roof for the first walkable block that cannot see the sky (under cover, not the
     * roof itself) with solid ground beneath it -- but "sheltered, walkable, floored" describes a
     * natural cave just as well as a room, and an unbounded scan found one 45 blocks under a house.
     * The Y band keeps it out of a cave far underneath and off a roof high above; near sea level by
     * default ({@link #interiorNear(ServerLevel, BlockPos, int)}), a slot's own {@code y_min}/
     * {@code y_max} otherwise. A person placed "near" a house lands standing in it, not on its roof,
     * in the cave under it, or the path outside. Falls back to the old outdoor spot
     * ({@link #around}) if nothing sheltered turns up in the band -- CityWorld generates whatever
     * shape it likes, and not every "near" reference is a real building.
     */
    static BlockPos interiorNear(ServerLevel level, BlockPos from, int radius, int minY, int maxY) {
        var rng = level.getRandom();
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            double dist = rng.nextDouble() * radius;
            int x = (int) Math.round(from.getX() + Math.cos(angle) * dist);
            int z = (int) Math.round(from.getZ() + Math.sin(angle) * dist);
            BlockPos found = interiorColumn(level, x, z, minY, maxY);
            if (found != null) return found;
        }
        return around(level, from, 4, 10);
    }

    /** The first sheltered, walkable, floored spot in this column between {@code minY} and {@code maxY}, scanning down. */
    private static BlockPos interiorColumn(ServerLevel level, int x, int z, int minY, int maxY) {
        int top = Math.min(level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 1, maxY);
        int bottom = Math.max(level.getMinY() + 1, minY);
        for (int y = top; y > bottom; y--) {
            BlockPos pos = new BlockPos(x, y, z);
            if (level.canSeeSky(pos)) continue; // still the roof, or the open air outside it
            if (level.getBlockState(pos).is(net.minecraft.tags.BlockTags.BLOCKS_MOTION) || level.getBlockState(pos.above()).is(net.minecraft.tags.BlockTags.BLOCKS_MOTION)) continue; // not standing room
            if (!level.getBlockState(pos.below()).is(net.minecraft.tags.BlockTags.BLOCKS_MOTION)) continue; // no floor
            return pos;
        }
        return null;
    }

    /**
     * The nearest structure, as {@code /locate} finds it. Its height is the ground there if that
     * chunk exists, else sea level: nothing generates a far chunk just to learn how tall it is, and
     * the objectives that go there measure across the map, not up.
     */
    static Optional<BlockPos> nearestStructure(ServerLevel level, BlockPos from, String want, int radius) {
        var registry = level.registryAccess().lookupOrThrow(Registries.STRUCTURE);
        Optional<HolderSet<Structure>> set;
        if (want.startsWith("#")) {
            Identifier tag = Identifier.tryParse(want.substring(1));
            set = tag == null ? Optional.empty() : registry.get(TagKey.create(Registries.STRUCTURE, tag)).map(h -> (HolderSet<Structure>) h);
        } else {
            Identifier sid = Identifier.tryParse(want);
            set = sid == null ? Optional.empty() : registry.get(ResourceKey.create(Registries.STRUCTURE, sid)).map(h -> (HolderSet<Structure>) HolderSet.direct(h));
        }
        if (set.isEmpty()) {
            Chronicler.LOGGER.warn("Chronicler: mini quest names unknown structure '{}'", want);
            return Optional.empty();
        }
        var found = level.getChunkSource().getGenerator().findNearestMapStructure(level, set.get(), from, Math.max(1, radius / 16), false);
        if (found == null) return Optional.empty();
        BlockPos p = found.getFirst();
        return Optional.of(new BlockPos(p.getX(), heightAt(level, p.getX(), p.getZ()), p.getZ()));
    }

    private static int heightAt(ServerLevel level, int x, int z) {
        if (level.hasChunk(x >> 4, z >> 4)) return level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        return level.getSeaLevel();
    }

    /** The nearest CityWorld lot whose words contain {@code want}, chunk by chunk outward. CityWorld answers for chunks never generated. */
    static Optional<BlockPos> nearestLot(ServerLevel level, BlockPos from, java.util.function.Predicate<List<String>> matches, int radius) {
        int cx = from.getX() >> 4, cz = from.getZ() >> 4;
        int rings = Math.max(1, radius / 16);
        for (int r = 0; r <= rings; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    int x = ((cx + dx) << 4) + 8, z = ((cz + dz) << 4) + 8;
                    BlockPos probe = new BlockPos(x, from.getY(), z);
                    var words = Lots.describe(level, probe);
                    if (words.isPresent() && matches.test(words.get())) {
                        return Optional.of(new BlockPos(x, heightAt(level, x, z), z));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** The nearest matching block within a cube; for a two-block-tall one (a door) its lower half. Loaded chunks only. */
    static Optional<BlockPos> nearestBlock(ServerLevel level, BlockPos from, String want, int radius) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = from.getX() + dx, z = from.getZ() + dz;
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                for (int dy = -radius; dy <= radius; dy++) {
                    p.set(x, from.getY() + dy, z);
                    if (!QuestEngine.blockMatches(level, p, want)) continue;
                    var state = level.getBlockState(p);
                    if (state.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                            && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF)
                                    != net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER) continue;
                    double d = p.distSqr(from);
                    if (d < bestD) { bestD = d; best = p.immutable(); }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    // --- starting and offering ---

    /**
     * Start a mini quest for a player now ({@code offer} false), or put it to them with Accept
     * (true). Empty means it was started or offered; otherwise the reason, which the player has
     * already been told.
     */
    public static Optional<String> start(ServerPlayer player, Identifier id, Map<String, String> given, Anchor anchor, boolean offer) {
        MinecraftServer server = player.level().getServer();
        var holder = QuestEngine.quest(server, id);
        if (holder.isEmpty() || holder.get().value().mini().isEmpty()) {
            String why = Lang.fmt("msg.mini.not_mini", "id", id);
            Feedback.chat(player, why);
            return Optional.of(why);
        }
        Quest base = holder.get().value();
        var refusal = QuestEngine.refusal(player, id, base);
        if (refusal.isPresent()) {
            String why = Lang.fmt("msg.mini.refused", "name", base.name(), "why", Lang.get("msg.mini.refusal." + refusal.get().name().toLowerCase(Locale.ROOT)));
            Feedback.chat(player, why);
            return Optional.of(why);
        }
        Resolved r = resolve(player, id, base, given, anchor, offer);
        if (r.failure().isPresent()) {
            Feedback.chat(player, Lang.fmt("msg.mini.unresolved", "name", base.name(), "why", r.failure().get()));
            return r.failure();
        }
        if (offer) {
            Quest filled = instance(server, id, base, r.values());
            putPending(player, id, base, r.values(), Optional.empty(), anchor, ChroniclerConfig.MINI_OFFER_SECONDS.get(), false);
            Givers.offerNow(player, id, filled, Lang.get("mini.offer.where"));
            return Optional.empty();
        }
        var refused = QuestEngine.accept(player, id, r.values());
        if (refused.isPresent()) {
            ended(server, id, base, r.values());
            return Optional.of(refused.get().name());
        }
        return Optional.empty();
    }

    private static void putPending(ServerPlayer player, Identifier id, Quest base, Map<String, String> values, Optional<String> blockKey,
            Anchor anchor, int seconds, boolean wild) {
        List<UUID> npcs = new ArrayList<>();
        base.mini().get().slots().forEach((name, slot) -> {
            if (!slot.type().equals("npc")) return;
            UUID u = parseUuid(values.getOrDefault(name + ".id", ""));
            if (u != null) npcs.add(u);
        });
        Pending old = PENDING.computeIfAbsent(player.getUUID(), k -> new HashMap<>()).put(id,
                new Pending(id, player.getUUID(), values, npcs, blockKey, anchor.level().dimension().identifier(),
                        Vec3.atCenterOf(anchor.pos()), player.level().getGameTime() + seconds * 20L, wild));
        if (old != null) lapse(player.level().getServer(), old);
    }

    /** The filled values of an offer this player could accept, and it is theirs now: the nearest standing offer, else their own. */
    public static Map<String, String> claim(ServerPlayer player, Identifier id) {
        Pending p = nearestPending(player, id).orElseGet(() -> PENDING.getOrDefault(player.getUUID(), Map.of()).get(id));
        if (p == null) return null;
        Map<Identifier, Pending> theirs = PENDING.get(p.owner());
        if (theirs != null) theirs.remove(id);
        GiverStore store = GiverStore.get(player.level().getServer());
        for (UUID n : p.npcs()) store.setMini(n, id, false);
        return p.values();
    }

    private static Optional<Pending> nearestPending(ServerPlayer player, Identifier id) {
        Pending best = null;
        double bestD = 24 * 24;
        for (Map<Identifier, Pending> m : PENDING.values()) {
            Pending p = m.get(id);
            if (p == null || !p.wild() || !p.dimension().equals(player.level().dimension().identifier())) continue;
            double d = p.where().distanceToSqr(player.position());
            if (d < bestD) { bestD = d; best = p; }
        }
        return Optional.ofNullable(best);
    }

    /** The values a player would get if they accepted now -- for showing the offer and /quest info filled in. */
    public static Optional<Map<String, String>> pendingValues(ServerPlayer player, Identifier id) {
        return nearestPending(player, id).or(() -> Optional.ofNullable(PENDING.getOrDefault(player.getUUID(), Map.of()).get(id))).map(Pending::values);
    }

    /** The filled quest a person stands for, if they are standing for an offer of it. */
    public static Optional<Quest> pendingQuestAtNpc(MinecraftServer server, UUID npcId, Identifier id) {
        for (Map<Identifier, Pending> m : PENDING.values()) {
            Pending p = m.get(id);
            if (p != null && p.npcs().contains(npcId)) {
                return QuestEngine.quest(server, id).map(h -> instance(server, id, h.value(), p.values()));
            }
        }
        return Optional.empty();
    }

    public static Optional<Identifier> pendingAtBlock(ServerLevel level, BlockPos pos) {
        String key = GiverStore.key(level, pos);
        for (Map<Identifier, Pending> m : PENDING.values()) {
            for (Pending p : m.values()) if (p.blockKey().map(key::equals).orElse(false)) return Optional.of(p.quest());
        }
        return Optional.empty();
    }

    public static Optional<Quest> pendingQuestAtBlock(ServerLevel level, BlockPos pos) {
        String key = GiverStore.key(level, pos);
        for (Map<Identifier, Pending> m : PENDING.values()) {
            for (Pending p : m.values()) {
                if (p.blockKey().map(key::equals).orElse(false)) {
                    return QuestEngine.quest(level.getServer(), p.quest()).map(h -> instance(level.getServer(), p.quest(), h.value(), p.values()));
                }
            }
        }
        return Optional.empty();
    }

    /** The quest an accepted mini quest's person is working for, if they are one. */
    public static Optional<Identifier> activeQuestOf(MinecraftServer server, UUID npcId) {
        String v = GiverStore.get(server).minis().get(npcId);
        if (v == null || !v.startsWith("active|")) return Optional.empty();
        return Optional.ofNullable(Identifier.tryParse(v.substring("active|".length())));
    }

    /** Is this person part of this player's own running copy of the quest? */
    public static boolean isMine(ServerPlayer player, Identifier id, UUID npcId) {
        QuestLog.Entry e = QuestEngine.journal(player).entry(id);
        return e != null && e.slots.containsValue(npcId.toString());
    }

    // --- endings and lapses ---

    /**
     * A mini quest ended -- done, abandoned, failed, replayed. Its people leave, unless the slot
     * says {@code keep} (the next errand in a chain needs them) or someone online is still on the
     * same copy of it (a party member who has not finished).
     */
    public static void ended(MinecraftServer server, Identifier id, Quest base, Map<String, String> slots) {
        if (base.mini().isEmpty() || slots.isEmpty()) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            QuestLog.Entry e = QuestEngine.journal(p).entry(id);
            if (e != null && e.slots.equals(slots)) return;
        }
        GiverStore store = GiverStore.get(server);
        base.mini().get().slots().forEach((name, slot) -> {
            if (!slot.type().equals("npc")) return;
            UUID u = parseUuid(slots.getOrDefault(name + ".id", ""));
            if (u == null) return;
            store.clearMini(u);
            store.removeNpc(u);
            if (!slot.person().keep() && Npcs.available()) {
                Npcs.provider().get().stopFollowing(server, u);
                Npcs.provider().get().remove(server, u);
            }
        });
    }

    /** Overload for callers holding only an id. */
    public static void ended(MinecraftServer server, Identifier id, Map<String, String> slots) {
        QuestEngine.quest(server, id).ifPresent(h -> ended(server, id, h.value(), slots));
    }

    private static void lapse(MinecraftServer server, Pending p) {
        GiverStore store = GiverStore.get(server);
        for (UUID n : p.npcs()) {
            store.clearMini(n);
            store.removeNpc(n);
            if (Npcs.available()) Npcs.provider().get().remove(server, n);
        }
    }

    private static void undo(MinecraftServer server, List<UUID> placed) {
        GiverStore store = GiverStore.get(server);
        for (UUID n : placed) {
            store.clearMini(n);
            store.removeNpc(n);
            if (Npcs.available()) Npcs.provider().get().remove(server, n);
        }
    }

    // --- the wild ---

    /** Server start: which templates spawn, and the people left standing for offers the last run never settled. */
    public static void index(MinecraftServer server) {
        WILD.clear();
        INSTANCES.clear();
        PENDING.clear();
        ROLLED.clear();
        QuestEngine.quests(server).listElements().forEach(h -> {
            if (h.value().mini().flatMap(Mini::spawn).isPresent()) WILD.add(h.key().identifier());
        });
        GiverStore store = GiverStore.get(server);
        int gone = 0;
        for (var e : store.minis().entrySet()) {
            if (!e.getValue().startsWith("pending|")) continue;
            store.clearMini(e.getKey());
            store.removeNpc(e.getKey());
            if (Npcs.available()) Npcs.provider().get().remove(server, e.getKey());
            gone++;
        }
        if (!WILD.isEmpty() || gone > 0) {
            Chronicler.LOGGER.info("Chronicler: {} mini quest template(s), {} found in the wild; {} lapsed offer(s) cleared", templates(server).size(), WILD.size(), gone);
        }
    }

    public static void clear() {
        INSTANCES.clear();
        PENDING.clear();
        ROLLED.clear();
    }

    /** Once a second: offers that lapse, and a roll for each wild template this player is standing in the place of. */
    public static void tick(MinecraftServer server) {
        long now = server.overworld().getGameTime();
        for (var byPlayer : PENDING.values()) {
            byPlayer.values().removeIf(p -> {
                if (now < p.expires()) return false;
                lapse(server, p);
                return true;
            });
        }
        if (!ChroniclerConfig.MINI_WILD.get() || WILD.isEmpty()) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) tickWild(player);
    }

    /** Package-visible: the self-test rolls for a FakePlayer, who is not in the player list. */
    static void tickWild(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        long now = player.level().getGameTime();
        for (Identifier id : WILD) {
            var holder = QuestEngine.quest(server, id);
            if (holder.isEmpty()) continue;
            Quest base = holder.get().value();
            Mini.Wild wild = base.mini().get().spawn().get();
            Map<Identifier, Long> mine = ROLLED.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
            Long last = mine.get(id);
            if (last != null && now - last < wild.every() * 20L) continue;
            mine.put(id, now);
            if (!Places.isAt(player, wild.place())) continue;
            if (!QuestEngine.available(player, id, base)) continue;
            if (PENDING.getOrDefault(player.getUUID(), Map.of()).containsKey(id)) continue;
            if (standing(id) >= wild.cap()) continue;
            if (player.getRandom().nextDouble() >= wild.chance()) continue;
            offerWild(player, id, base);
        }
    }

    /** Make a wild offer to this player now, rolls aside. Empty when made; the reason otherwise (said to nobody -- the wild is quiet about what it did not do). */
    public static Optional<String> offerWild(ServerPlayer player, Identifier id, Quest base) {
        MinecraftServer server = player.level().getServer();
        Mini.Wild wild = base.mini().get().spawn().get();
        Anchor anchor = Anchor.of(player);
        Resolved r = resolve(player, id, base, Map.of(), anchor, true);
        if (r.failure().isPresent()) {
            Chronicler.LOGGER.debug("Chronicler: wild {} for {} not offered: {}", id, player.getName().getString(), r.failure().get());
            return r.failure();
        }
        Quest filled = instance(server, id, base, r.values());
        Optional<String> blockKey = Optional.empty();
        Anchor where = anchor;
        String whereText;
        String opening = wild.say().map(t -> Slots.text(t, r.values(), base.mini().get())).orElse(filled.description().orElse(filled.name()));
        if (wild.giver().isPresent()) {
            String slot = wild.giver().get();
            whereText = r.values().getOrDefault(slot, Lang.get("giver.someone"));
            Anchor at = anchorOf(server, r.values(), slot);
            if (at != null) where = at;
            UUID npc = parseUuid(r.values().getOrDefault(slot + ".id", ""));
            if (npc != null && Npcs.available()) Npcs.provider().get().say(server, npc, opening, 24.0);
        } else {
            String slot = wild.block().get();
            whereText = r.values().getOrDefault(slot, Lang.get("giver.someone"));
            Anchor at = anchorOf(server, r.values(), slot);
            if (at != null) { where = at; blockKey = Optional.of(GiverStore.key(at.level(), at.pos())); }
            Feedback.chat(player, Lang.fmt("msg.mini.scene", "text", opening));
        }
        putPending(player, id, base, r.values(), blockKey, where, wild.lapse(), true);
        Givers.offerNow(player, id, filled, whereText);
        Chronicler.LOGGER.info("Chronicler: {} found a mini quest in the wild: {} at {}", player.getName().getString(), id, where.pos());
        return Optional.empty();
    }

    /** Wild offers of this template standing in the world right now. */
    private static int standing(Identifier id) {
        int n = 0;
        for (Map<Identifier, Pending> m : PENDING.values()) {
            Pending p = m.get(id);
            if (p != null && p.wild()) n++;
        }
        return n;
    }

    public static int pendingCount() {
        int n = 0;
        for (Map<Identifier, Pending> m : PENDING.values()) n += m.size();
        return n;
    }

    /** Test fixture: let every standing offer lapse now. */
    static void lapseAll(MinecraftServer server) {
        for (var m : PENDING.values()) for (Pending p : m.values()) lapse(server, p);
        PENDING.clear();
    }

    private Minis() {}
}
