package com.sablednah.chronicler.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * Op-placed givers: "this block offers that quest". SavedData, world-global,
 * because a giver belongs to the world and must answer for a player who is
 * not online. Keyed by dimension and position.
 *
 * <p>26.1 moves saved data into a namespaced folder; the version branch
 * carries the migration, and this class logs its count on start so an empty
 * store is distinguishable from a lost one.</p>
 */
public final class GiverStore extends SavedData {

    private static final Codec<GiverStore> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC).fieldOf("givers").forGetter(s -> s.byKey),
            Codec.unboundedMap(Codec.STRING, Codec.STRING).optionalFieldOf("placed", Map.of()).forGetter(s -> s.placed),
            Codec.unboundedMap(Codec.STRING, Identifier.CODEC.listOf()).optionalFieldOf("npc_quests", Map.of()).forGetter(s -> s.npcQuests))
            .apply(i, GiverStore::new));

    public static final SavedDataType<GiverStore> TYPE =
            new SavedDataType<>(net.minecraft.resources.Identifier.fromNamespaceAndPath("chronicler", "givers"), GiverStore::new, CODEC, null);

    private final Map<String, Identifier> byKey;
    /** quest id -> the Cast npcId its data giver placed. */
    private final Map<String, String> placed;
    /** Every quest an NPC gives, in order; {@code byKey} keeps the first so blocks and NPCs list alike. */
    private final Map<String, java.util.List<Identifier>> npcQuests;

    public GiverStore() {
        this(Map.of(), Map.of(), Map.of());
    }

    private GiverStore(Map<String, Identifier> byKey, Map<String, String> placed, Map<String, java.util.List<Identifier>> npcQuests) {
        this.byKey = new LinkedHashMap<>(byKey);
        this.placed = new LinkedHashMap<>(placed);
        this.npcQuests = new LinkedHashMap<>();
        npcQuests.forEach((k, v) -> this.npcQuests.put(k, new java.util.ArrayList<>(v)));
    }

    public static GiverStore get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    public static String key(ServerLevel level, BlockPos pos) {
        return level.dimension().identifier() + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    /** An NPC giver's key: the Cast npcId. */
    public static String npcKey(java.util.UUID npcId) {
        return "npc|" + npcId;
    }

    public Optional<Identifier> atNpc(java.util.UUID npcId) {
        return Optional.ofNullable(byKey.get(npcKey(npcId)));
    }

    /** Add a quest to what this NPC gives (first one wins the primary slot). */
    public void setNpc(java.util.UUID npcId, Identifier quest) {
        String k = npcKey(npcId);
        byKey.putIfAbsent(k, quest);
        java.util.List<Identifier> list = npcQuests.computeIfAbsent(k, x -> new java.util.ArrayList<>());
        if (!list.contains(quest)) list.add(quest);
        setDirty();
    }

    /** All the quests an NPC gives, primary first. */
    public java.util.List<Identifier> questsAtNpc(java.util.UUID npcId) {
        String k = npcKey(npcId);
        java.util.List<Identifier> out = new java.util.ArrayList<>();
        Identifier primary = byKey.get(k);
        if (primary != null) out.add(primary);
        for (Identifier q : npcQuests.getOrDefault(k, java.util.List.of())) if (!out.contains(q)) out.add(q);
        return out;
    }

    /** All the quests a giver key carries: one for a block, several for an NPC. */
    public java.util.List<Identifier> questsAt(String key) {
        if (key.startsWith("npc|")) {
            try { return questsAtNpc(java.util.UUID.fromString(key.substring(4))); } catch (IllegalArgumentException e) { return java.util.List.of(); }
        }
        Identifier q = byKey.get(key);
        return q == null ? java.util.List.of() : java.util.List.of(q);
    }

    public boolean removeNpc(java.util.UUID npcId) {
        boolean had = byKey.remove(npcKey(npcId)) != null;
        had |= npcQuests.remove(npcKey(npcId)) != null;
        if (had) setDirty();
        return had;
    }

    /**
     * Every person this store knows as Chronicler's -- given a quest by an op or {@code giver_for}, placed by data
     * for a quest, or placed for a mini -- with the quests they give (none for a mini's person). The map's radar.
     */
    public Map<java.util.UUID, java.util.List<Identifier>> people() {
        Map<java.util.UUID, java.util.List<Identifier>> out = new LinkedHashMap<>();
        for (var e : byKey.entrySet()) {
            if (!e.getKey().startsWith("npc|")) continue;
            uuid(e.getKey().substring(4)).ifPresent(id -> out.computeIfAbsent(id, k -> new java.util.ArrayList<>()).add(e.getValue()));
        }
        for (var e : npcQuests.entrySet()) {
            if (!e.getKey().startsWith("npc|")) continue;
            uuid(e.getKey().substring(4)).ifPresent(id -> {
                var quests = out.computeIfAbsent(id, k -> new java.util.ArrayList<>());
                for (Identifier q : e.getValue()) if (!quests.contains(q)) quests.add(q);
            });
        }
        for (var e : placed.entrySet()) {
            String k = e.getKey();
            if (k.startsWith("mini:")) {
                uuid(k.substring(5)).ifPresent(id -> out.computeIfAbsent(id, x -> new java.util.ArrayList<>()));
            } else if (!k.startsWith("block:") && !k.startsWith("left:") && !k.startsWith("gone:") && !k.startsWith("settle:")) {
                Identifier quest = Identifier.tryParse(k); // a bare quest id: the person data placed for it
                if (quest == null) continue;
                uuid(e.getValue()).ifPresent(id -> {
                    var quests = out.computeIfAbsent(id, x -> new java.util.ArrayList<>());
                    if (!quests.contains(quest)) quests.add(quest);
                });
            }
        }
        return out;
    }

    private static Optional<java.util.UUID> uuid(String s) {
        try { return Optional.of(java.util.UUID.fromString(s)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    /** The NPC that data placed for a quest, so it is placed once, not once per start. */
    public Optional<java.util.UUID> placedFor(Identifier quest) {
        String v = placed.get(quest.toString());
        if (v == null) return Optional.empty();
        try { return Optional.of(java.util.UUID.fromString(v)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    /** Where a data giver's block was dropped near spawn, once. */
    public Optional<BlockPos> placedBlock(Identifier quest) {
        String v = placed.get("block:" + quest);
        if (v == null) return Optional.empty();
        try {
            String[] p = v.split(",");
            return Optional.of(new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2])));
        } catch (RuntimeException e) { return Optional.empty(); }
    }

    public void setPlacedBlock(Identifier quest, BlockPos pos) {
        placed.put("block:" + quest, pos.getX() + "," + pos.getY() + "," + pos.getZ());
        setDirty();
    }

    /** Where {@code npc_remove} left something behind for this quest's person, in {@code dim|x,y,z}. */
    public record Left(Identifier dimension, BlockPos pos) {}

    public void setLeft(Identifier quest, Identifier dimension, BlockPos pos) {
        placed.put("left:" + quest, dimension + "|" + pos.getX() + "," + pos.getY() + "," + pos.getZ());
        setDirty();
    }

    public Optional<Left> left(Identifier quest) {
        String v = placed.get("left:" + quest);
        if (v == null) return Optional.empty();
        try {
            String[] halves = v.split("\\|");
            String[] p = halves[1].split(",");
            return Optional.of(new Left(Identifier.parse(halves[0]), new BlockPos(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]))));
        } catch (RuntimeException e) { return Optional.empty(); }
    }

    /**
     * A data-placed person {@code npc_remove} took away, by their own quest: never placed again on a later
     * start (Cast forgets a removed NPC, so without this the next start put them straight back). A
     * {@code left:} record says the same, so a world that removed someone before this record existed agrees.
     */
    public void setGone(Identifier quest) {
        placed.put("gone:" + quest, "1");
        setDirty();
    }

    public boolean isGone(Identifier quest) {
        return placed.containsKey("gone:" + quest) || placed.containsKey("left:" + quest);
    }

    public void clearGone(Identifier quest) {
        if (placed.remove("gone:" + quest) != null) setDirty();
    }

    /** Every quest whose person left something behind. */
    public java.util.List<Identifier> leftQuests() {
        java.util.List<Identifier> out = new java.util.ArrayList<>();
        for (String k : placed.keySet()) {
            if (!k.startsWith("left:")) continue;
            Identifier id = Identifier.tryParse(k.substring("left:".length()));
            if (id != null) out.add(id);
        }
        return out;
    }

    public void clearLeft(Identifier quest) {
        if (placed.remove("left:" + quest) != null) setDirty();
    }

    public void setPlacedFor(Identifier quest, java.util.UUID npcId) {
        placed.put(quest.toString(), npcId.toString());
        setDirty();
    }

    /**
     * A person placed for a mini quest: {@code pending} while an offer stands (such a one is removed
     * on the next start, since offers do not survive a restart), then active until the quest ends.
     */
    public void setMini(java.util.UUID npcId, Identifier quest, boolean pending) {
        placed.put("mini:" + npcId, (pending ? "pending|" : "active|") + quest);
        setDirty();
    }

    public void clearMini(java.util.UUID npcId) {
        if (placed.remove("mini:" + npcId) != null) setDirty();
    }

    /**
     * A person placed in land nobody had loaded, standing at a provisional height until it loads and their
     * real spot can be picked without loading anything ({@code Minis.settle}). Saved: a restart in between
     * must not leave them at sea level inside a hill.
     */
    public void setSettle(java.util.UUID npcId, String how) {
        placed.put("settle:" + npcId, how);
        setDirty();
    }

    public void clearSettle(java.util.UUID npcId) {
        if (placed.remove("settle:" + npcId) != null) setDirty();
    }

    public Map<java.util.UUID, String> settles() {
        Map<java.util.UUID, String> out = new LinkedHashMap<>();
        placed.forEach((k, v) -> {
            if (!k.startsWith("settle:")) return;
            try { out.put(java.util.UUID.fromString(k.substring(7)), v); } catch (IllegalArgumentException ignored) {}
        });
        return out;
    }

    /** npcId -> "pending|quest" or "active|quest". */
    public Map<java.util.UUID, String> minis() {
        Map<java.util.UUID, String> out = new LinkedHashMap<>();
        placed.forEach((k, v) -> {
            if (!k.startsWith("mini:")) return;
            try { out.put(java.util.UUID.fromString(k.substring(5)), v); } catch (IllegalArgumentException ignored) {}
        });
        return out;
    }

    public Optional<Identifier> at(ServerLevel level, BlockPos pos) {
        return Optional.ofNullable(byKey.get(key(level, pos)));
    }

    public void set(ServerLevel level, BlockPos pos, Identifier quest) {
        byKey.put(key(level, pos), quest);
        setDirty();
    }

    public boolean remove(ServerLevel level, BlockPos pos) {
        boolean had = byKey.remove(key(level, pos)) != null;
        if (had) setDirty();
        return had;
    }

    public Map<String, Identifier> view() {
        return java.util.Collections.unmodifiableMap(byKey);
    }

    public int size() {
        return byKey.size();
    }
}
