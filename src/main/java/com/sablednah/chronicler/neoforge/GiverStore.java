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
            new SavedDataType<>("chronicler_givers", GiverStore::new, CODEC, null);

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

    /** The NPC that data placed for a quest, so it is placed once, not once per start. */
    public Optional<java.util.UUID> placedFor(Identifier quest) {
        String v = placed.get(quest.toString());
        if (v == null) return Optional.empty();
        try { return Optional.of(java.util.UUID.fromString(v)); } catch (IllegalArgumentException e) { return Optional.empty(); }
    }

    public void setPlacedFor(Identifier quest, java.util.UUID npcId) {
        placed.put(quest.toString(), npcId.toString());
        setDirty();
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
