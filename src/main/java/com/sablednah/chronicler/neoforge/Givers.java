package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.world.phys.Vec3;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.data.GiverSpec;
import com.sablednah.chronicler.data.GiverTypes;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * Where quests come from. Two sources, one behaviour:
 *
 * <ul>
 * <li><b>Data givers</b> on the quest record ({@code giver: {type: ...}}) --
 *     a block position, or a kind of place.</li>
 * <li><b>Op-placed givers</b> in {@link GiverStore}: {@code /quest giver set}
 *     at the block you are looking at.</li>
 * </ul>
 *
 * <p>Standing near a giver whose quest you could take gets you the <em>offer</em>:
 * an action-bar line plus a chat line with Accept and Info, once per cooldown
 * so it does not nag. Right-clicking a giver block accepts. Ambient givers
 * (a biome, a structure, a dimension, a CityWorld lot) offer the moment you
 * are in that kind of place. Hidden quests are found this way -- a giver
 * overrides {@code hidden}, that is what hidden is for.</p>
 */
public final class Givers {

    /** Data givers, indexed once per server start (registries are frozen). */
    private static final List<Entry> DATA = new ArrayList<>();
    /** Block key -> every data quest given there, the block's own quest first, then those that share it by {@code of}. */
    private static final Map<String, List<Identifier>> DATA_BLOCKS = new HashMap<>();
    /** Where each data position giver actually is (fixed, or dropped near spawn and remembered). */
    private static final Map<Identifier, BlockPos> RESOLVED = new HashMap<>();
    /** Each resolved quest's block key and dimension, so a quest sharing a block shares both. */
    private static final Map<Identifier, String> RESOLVED_KEY = new HashMap<>();
    private static final Map<Identifier, Identifier> RESOLVED_DIM = new HashMap<>();
    /** Within one index pass: "dim|dx,dz" -> the block a near_spawn offset dropped to, so a second quest naming it shares it. */
    private static final Map<String, BlockPos> NEAR_SPAWN = new HashMap<>();
    /** Per player: quest -> game time of the last offer, so a giver does not nag. */
    private static final Map<UUID, Map<Identifier, Long>> OFFERED = new HashMap<>();

    private record Entry(Identifier id, Quest quest, GiverSpec giver) {}

    public static void index(MinecraftServer server) {
        DATA.clear();
        DATA_BLOCKS.clear();
        RESOLVED.clear();
        RESOLVED_KEY.clear();
        RESOLVED_DIM.clear();
        NEAR_SPAWN.clear();
        GiverStore store = GiverStore.get(server);
        QuestEngine.quests(server).listElements().forEach(h -> h.value().giver().ifPresent(g ->
                DATA.add(new Entry(h.key().identifier(), h.value(), g))));
        // Blocks a near_spawn offset already dropped to in an earlier start, first: a quest new to this world
        // that names the same offset shares that block instead of measuring again -- off the top of the campfire.
        for (Entry e : DATA) {
            if (e.giver() instanceof GiverTypes.Position p && p.nearSpawn().isPresent() && p.of().isEmpty()) {
                store.placedBlock(e.id()).ifPresent(at -> NEAR_SPAWN.putIfAbsent(nearSpawnKey(p), at));
            }
        }
        for (Entry e : DATA) {
            if (e.giver() instanceof GiverTypes.Position p && p.of().isEmpty()) {
                Identifier dim = p.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier());
                BlockPos at = resolve(server, e.id(), p, dim);
                if (at != null) addBlock(e.id(), dim, at);
            }
            if (e.giver() instanceof GiverTypes.NpcGiver n && n.of().isEmpty()) placeNpc(server, e.id(), n);
        }
        // Second pass: quests that share another quest's block or NPC, once every block and NPC is placed.
        for (Entry e : List.copyOf(DATA)) {
            if (e.giver() instanceof GiverTypes.NpcGiver n && n.of().isPresent()) shareNpc(server, e.id(), n.of().get());
            if (e.giver() instanceof GiverTypes.Position p && p.of().isPresent()) {
                BlockPos at = RESOLVED.get(p.of().get());
                if (at == null) {
                    Chronicler.LOGGER.warn("Chronicler: {} gives from the block of {}, which has no position giver -- it is given from nowhere",
                            e.id(), p.of().get());
                    continue;
                }
                addBlock(e.id(), RESOLVED_DIM.get(p.of().get()), at);
            }
        }
        OFFERED.clear();
    }

    private static void addBlock(Identifier questId, Identifier dim, BlockPos at) {
        String key = dim + "|" + at.getX() + "," + at.getY() + "," + at.getZ();
        RESOLVED.put(questId, at);
        RESOLVED_KEY.put(questId, key);
        RESOLVED_DIM.put(questId, dim);
        List<Identifier> here = DATA_BLOCKS.computeIfAbsent(key, k -> new ArrayList<>());
        if (!here.contains(questId)) here.add(questId);
    }

    private static String nearSpawnKey(GiverTypes.Position p) {
        return p.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier()) + "|"
                + p.nearSpawn().get().get(0) + "," + p.nearSpawn().get().get(1);
    }

    /** Where a position giver stands, placing its block and decor the first time a near-spawn one is seen. */
    private static BlockPos resolve(MinecraftServer server, Identifier questId, GiverTypes.Position p, Identifier dim) {
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (p.at().isPresent()) {
            if (level != null && p.block().isPresent()) placeBlock(level, p.at().get(), p.block().get(), questId);
            return p.at().get();
        }
        if (level == null) return null;
        GiverStore store = GiverStore.get(server);
        Optional<BlockPos> known = store.placedBlock(questId);
        if (known.isPresent()) {
            if (p.block().isPresent() && level.isLoaded(known.get())) placeBlock(level, known.get(), p.block().get(), questId);
            return known.get();
        }
        // Another quest already dropped onto this offset: share its block. Measuring again would land on top of
        // what it placed (a second campfire on the first), and its decor stands already.
        BlockPos shared = NEAR_SPAWN.get(nearSpawnKey(p));
        if (shared != null) {
            p.block().ifPresent(b -> placeBlock(level, shared, b, questId));
            store.setPlacedBlock(questId, shared);
            return shared;
        }
        var spawn = server.overworld().getRespawnData().globalPos().pos();
        int x = spawn.getX() + p.nearSpawn().get().get(0), z = spawn.getZ() + p.nearSpawn().get().get(1);
        BlockPos at = surface(level, x, z);
        p.block().ifPresent(b -> placeBlock(level, at, b, questId));
        placeDecor(level, at, p.decor(), questId);
        store.setPlacedBlock(questId, at);
        NEAR_SPAWN.put(nearSpawnKey(p), at);
        Chronicler.LOGGER.info("Chronicler: placed the giver block for {} at {}", questId, at);
        return at;
    }

    /** Set dressing around a giver, each column on its own ground. Package-visible for the self-test. */
    static void placeDecor(ServerLevel level, BlockPos at, List<GiverTypes.Position.Decor> decor, Identifier questId) {
        // Measure every column BEFORE placing anything: once a fence post stands, the surface of that
        // column is the top of the post, and a lantern "one above the ground" floats a block over it.
        Map<Long, BlockPos> columns = new HashMap<>();
        for (GiverTypes.Position.Decor d : decor) {
            int cx = at.getX() + d.offset().get(0), cz = at.getZ() + d.offset().get(2);
            columns.computeIfAbsent(BlockPos.asLong(cx, 0, cz), k -> surface(level, cx, cz));
        }
        for (GiverTypes.Position.Decor d : decor) {
            BlockPos column = columns.get(BlockPos.asLong(at.getX() + d.offset().get(0), 0, at.getZ() + d.offset().get(2)));
            placeBlock(level, column.above(d.offset().get(1)), d.block(), questId);
        }
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        return dryColumn(level, x, z);
    }

    private static BlockPos rawSurface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4); // generate it, or the heightmap answers for air
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
    }

    private static boolean dry(ServerLevel level, BlockPos surface) {
        BlockPos below = surface.below();
        return level.getFluidState(surface).isEmpty() && level.getFluidState(below).isEmpty()
                && !level.getBlockState(below).getCollisionShape(level, below).isEmpty();
    }

    /**
     * The surface at this column, or the nearest dry one: the heightmap counts water as
     * a surface, and a camp dropped by the shoreline put Mags in the sea. Spirals out to
     * eight blocks; past that, the wet spot is returned and the placement says so in the log.
     * Package-visible for the self-test.
     */
    public static BlockPos dryColumn(ServerLevel level, int x, int z) {
        BlockPos here = rawSurface(level, x, z);
        if (dry(level, here)) return here;
        for (int r = 1; r <= 8; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    BlockPos c = rawSurface(level, x + dx, z + dz);
                    if (dry(level, c)) return c;
                }
            }
        }
        Chronicler.LOGGER.warn("Chronicler: no dry ground within 8 of {}, {}; placing on what is there", x, z);
        return here;
    }

    /** Package-visible: {@code npc_remove}'s {@code leave} places what is left of an NPC with it. */
    static void placeBlock(ServerLevel level, BlockPos pos, String block, Identifier questId) {
        Identifier id = Identifier.tryParse(block.contains(":") ? block : "minecraft:" + block);
        var holder = id == null ? Optional.<net.minecraft.core.Holder.Reference<net.minecraft.world.level.block.Block>>empty()
                : net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(id);
        if (holder.isEmpty()) {
            Chronicler.LOGGER.warn("Chronicler: giver of {} names unknown block '{}'", questId, block);
            return;
        }
        var state = holder.get().value().defaultBlockState();
        if (state.isAir() || level.getBlockState(pos).is(holder.get().value())) return;
        level.setBlockAndUpdate(pos, state);
    }

    /** This quest is given by the NPC of another quest. */
    private static void shareNpc(MinecraftServer server, Identifier questId, Identifier of) {
        if (!Npcs.available()) return;
        GiverStore store = GiverStore.get(server);
        Npcs.Provider cast = Npcs.provider().get();
        Optional<UUID> npc = store.placedFor(of).filter(id -> cast.byId(server, id).isPresent());
        if (npc.isEmpty()) {
            Chronicler.LOGGER.warn("Chronicler: quest {} wants the NPC of {}, which is not placed", questId, of);
            return;
        }
        store.setNpc(npc.get(), questId);
        store.setPlacedFor(questId, npc.get());
    }

    /** Put a data-declared NPC giver in the world once, and remember which NPC it is. */
    private static void placeNpc(MinecraftServer server, Identifier questId, GiverTypes.NpcGiver n) {
        if (!Npcs.available()) {
            Chronicler.LOGGER.info("Chronicler: quest {} has an NPC giver ({}) but Cast is not installed -- listed, not placed", questId, n.name());
            return;
        }
        GiverStore store = GiverStore.get(server);
        Npcs.Provider cast = Npcs.provider().get();
        Optional<UUID> existing = store.placedFor(questId).filter(id -> cast.byId(server, id).isPresent());
        if (existing.isPresent()) {
            cast.ensureGiverRole(server, existing.get());
            store.setNpc(existing.get(), questId);
            if (!n.equipment().isEmpty()) cast.equip(server, existing.get(), n.equipment());
            cast.setDefyGravity(server, existing.get(), n.defyGravity());
            cast.setScale(server, existing.get(), n.scale()); // an edited file's size applies on the next start
            return;
        }
        Identifier dim = n.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier());
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null) return;
        net.minecraft.core.BlockPos at = n.at().orElseGet(() -> {
            // An offset from the world spawn, dropped onto the surface: a shipped camp stands on any seed.
            var spawn = server.overworld().getRespawnData().globalPos().pos();
            int x = spawn.getX() + n.nearSpawn().get().get(0), z = spawn.getZ() + n.nearSpawn().get().get(1);
            return dryColumn(level, x, z); // never in the sea, whatever the heightmap says
        });
        Vec3 pos = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        UUID id = n.entity().isPresent()
                ? cast.spawnMob(level, pos, n.yaw(), n.entity().get(), n.name())
                : cast.spawnHuman(level, pos, n.yaw(), n.name(), n.skin());
        store.setPlacedFor(questId, id);
        store.setNpc(id, questId);
        if (!n.equipment().isEmpty()) cast.equip(server, id, n.equipment());
        cast.setDefyGravity(server, id, n.defyGravity());
        if (n.scale() != 1D) cast.setScale(server, id, n.scale());
        Chronicler.LOGGER.info("Chronicler: placed NPC giver '{}' for {} at {}", n.name(), questId, at);
    }

    /** A right-click on a Cast NPC carrying the giver role. */
    public static boolean onUseNpc(ServerPlayer player, UUID npcId) {
        MinecraftServer server = player.level().getServer();
        // A mini quest's person takes deliveries addressed to them by id, whoever else they give for.
        if (QuestEngine.onDeliver(player, d -> d.npc().map(npcId.toString()::equals).orElse(false))) return true;
        List<Identifier> quests = GiverStore.get(server).questsAtNpc(npcId);
        Optional<Identifier> spokenFor = Minis.activeQuestOf(server, npcId);
        if (spokenFor.isPresent() && !Minis.isMine(player, spokenFor.get(), npcId)) {
            Feedback.chat(player, Lang.fmt("msg.mini.spoken_for", "name", npcName(npcId.toString())));
            return true;
        }
        if (quests.isEmpty()) {
            Feedback.chat(player, Lang.get("msg.giver.npc_idle"));
            return true;
        }
        // A hand-over to this person, for any of the quests they give, comes before any offer.
        for (Identifier q : quests) if (QuestEngine.onDeliver(player, q)) return true;
        // More than one thing to take from this person: all of them, not the first.
        if (offerAll(player, npcName(npcId.toString()), quests)) return true;
        // Several quests on one person: the same rule as several on one block.
        Optional<Identifier> id = pick(player, quests);
        Optional<Holder.Reference<Quest>> holder = QuestEngine.quest(server, id.get());
        if (holder.isEmpty()) {
            Feedback.chat(player, Lang.fmt("msg.giver.gone", "id", id.get()));
            return true;
        }
        // A mini quest shows its filled-in self: this person's offer if they stand for one, else the player's own.
        final Identifier chosen = id.get();
        final Quest registered = holder.get().value();
        Quest quest = Minis.pendingQuestAtNpc(server, npcId, chosen).orElseGet(() -> QuestEngine.questFor(player, chosen).orElse(registered));
        // A greeting from the data, spoken as the NPC, before the offer.
        if (quest.giver().orElse(null) instanceof GiverTypes.NpcGiver n && n.greeting().isPresent()
                && QuestEngine.available(player, id.get(), quest)) {
            Npcs.provider().ifPresent(c -> c.say(server, npcId, n.greeting().get(), 12.0));
        }
        return useGiver(player, id.get(), quest);
    }

    public static int dataCount() {
        return DATA.size();
    }

    /** The first quest a block offers, from data or from the store; whether it is a giver at all. */
    public static Optional<Identifier> questAt(ServerLevel level, BlockPos pos) {
        return questsAt(level, pos).stream().findFirst();
    }

    /** Every quest a block offers: the data's (its own quest, then those sharing it by {@code of}), then any an op added. */
    public static List<Identifier> questsAt(ServerLevel level, BlockPos pos) {
        String key = GiverStore.key(level, pos);
        List<Identifier> out = new ArrayList<>(DATA_BLOCKS.getOrDefault(key, List.of()));
        for (Identifier q : GiverStore.get(level.getServer()).questsAt(key)) if (!out.contains(q)) out.add(q);
        return out;
    }

    /**
     * Which of several quests on one giver a click (or standing by it) is about: the first the player can take,
     * else the first they are on, else the first. One rule for a person and a block alike.
     */
    static Optional<Identifier> pick(ServerPlayer player, List<Identifier> quests) {
        MinecraftServer server = player.level().getServer();
        for (Identifier q : quests) {
            var h = QuestEngine.quest(server, q);
            if (h.isPresent() && QuestEngine.available(player, q, h.get().value())) return Optional.of(q);
        }
        for (Identifier q : quests) if (QuestEngine.journal(player).isActive(q)) return Optional.of(q);
        return quests.stream().findFirst();
    }

    /** Once a second for the whole server: the floating markers over every giver. */
    public static void tickMarkers(MinecraftServer server) {
        GiverStore store = GiverStore.get(server);
        Map<String, List<Identifier>> all = new HashMap<>();
        DATA_BLOCKS.forEach((k, v) -> all.put(k, new ArrayList<>(v)));
        store.view().keySet().forEach(k -> {
            List<Identifier> here = all.computeIfAbsent(k, x -> new ArrayList<>());
            for (Identifier q : store.questsAt(k)) if (!here.contains(q)) here.add(q);
        });
        Markers.sync(server, all,
                key -> markerPosition(server, key),
                key -> {
                    if (key.startsWith("npc|")) {
                        return Npcs.provider().flatMap(c -> { try { return c.byId(server, UUID.fromString(key.substring(4))); } catch (IllegalArgumentException e) { return Optional.empty(); } })
                                .map(p -> server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, p.dimension())))
                                .orElse(null);
                    }
                    Identifier dim = Identifier.tryParse(key.substring(0, key.indexOf('|')));
                    return dim == null ? null : server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
                });
    }

    /** How far above an NPC's feet its mark floats: the nameplate's height (scaled with the body) plus a hair. */
    public static double markerHeight(double bodyHeight) {
        return bodyHeight * (2.3D / 1.8D) + 0.05D;
    }

    /** Above a block, or above an NPC's head; null when the NPC is not loaded. */
    private static Vec3 markerPosition(MinecraftServer server, String key) {
        if (key.startsWith("npc|")) {
            try {
                UUID id = UUID.fromString(key.substring(4));
                // Just over the nameplate, which vanilla raises with the body's scale: 2.35 for an ordinary 1.8,
                // so a child's mark is not left floating a metre above them and a big man's is not in his name.
                return Npcs.provider().flatMap(c -> c.byId(server, id)).map(p -> p.pos().add(0, markerHeight(p.height()), 0)).orElse(null);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        BlockPos pos = parse(key);
        return pos == null ? null : new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
    }

    /** A giver's place in the world, for "stand near" deliveries. */
    public record Where(Identifier level, Vec3 pos) {}

    public static Optional<Where> positionOf(MinecraftServer server, Identifier questId) {
        BlockPos fixed = RESOLVED.get(questId);
        if (fixed != null) {
            Identifier dim = RESOLVED_DIM.getOrDefault(questId, net.minecraft.world.level.Level.OVERWORLD.identifier());
            return Optional.of(new Where(dim, Vec3.atCenterOf(fixed)));
        }
        if (!Npcs.available()) return Optional.empty();
        return GiverStore.get(server).placedFor(questId).flatMap(id -> Npcs.provider().get().byId(server, id))
                .map(p -> new Where(p.dimension(), p.pos()));
    }

    /** How far {@code of} is followed before giving up: a chain that long is a loop in the data. */
    private static final int OF_HOPS = 8;

    /** A person's name by Cast id, as text; "the one who asked" when they are not (or no longer) there. */
    public static String npcName(String npcId) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || !Npcs.available()) return Lang.get("giver.someone");
        try {
            return Npcs.provider().get().byId(server, UUID.fromString(npcId)).map(p -> Feedback.colored(p.name()).getString()).orElse(Lang.get("giver.someone"));
        } catch (IllegalArgumentException e) {
            return Lang.get("giver.someone");
        }
    }

    /** What to call a giver in text: the NPC's name, a block's label, or "the one who asked". */
    public static String nameOf(Identifier questId) {
        return nameOf(questId, 0);
    }

    private static String nameOf(Identifier questId, int hops) {
        var server = net.neoforged.neoforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null || hops >= OF_HOPS) return Lang.get("giver.someone");
        return QuestEngine.quest(server, questId).flatMap(h -> h.value().giver()).map(g -> {
            if (g instanceof GiverTypes.NpcGiver n) {
                if (n.of().isPresent()) return nameOf(n.of().get(), hops + 1);
                return Feedback.colored(n.name()).getString();
            }
            if (g instanceof GiverTypes.Position p) return p.label().orElse(Lang.get("giver.someone"));
            return Lang.get("giver.someone");
        }).orElse(Lang.get("giver.someone"));
    }

    /**
     * A quest's giver as text for "From:" and the offer line, following {@code of} to the quest whose
     * NPC it borrows. A borrowing giver's own {@code name} is filler the codec demands ({@code "-"} in
     * ZARP), and printed as-is it read "From: -" under a quest Dr Okafor plainly hands out.
     */
    public static String describe(MinecraftServer server, com.sablednah.chronicler.data.Quest quest) {
        com.sablednah.chronicler.data.GiverSpec g = quest.giver().orElse(null);
        for (int hops = 0; hops < OF_HOPS && g instanceof GiverTypes.NpcGiver n && n.of().isPresent(); hops++) {
            Identifier borrowed = n.of().get();
            g = QuestEngine.quest(server, borrowed).flatMap(h -> h.value().giver()).orElse(null);
        }
        if (g == null || (g instanceof GiverTypes.NpcGiver n && n.of().isPresent())) return Lang.get("giver.someone");
        return g.describe();
    }

    /** A quest this player could take right now, and where its giver is. {@code distance} is NaN when nowhere is known. */
    public record Offer(Identifier id, Quest quest, String where, double distance) {
        public boolean placed() { return !Double.isNaN(distance); }
    }

    /**
     * Everything on offer to this player: visible, takeable now, with its giver's whereabouts. Within
     * {@code radius} blocks of the player (nearest first) -- or, with a negative radius, every one,
     * the placed ones nearest first and the unplaced (ambient, or a person who is not standing) after.
     * What the HUD's Nearby section and the journal's home page list.
     */
    public static List<Offer> offers(ServerPlayer player, double radius) {
        MinecraftServer server = player.level().getServer();
        Identifier here = player.level().dimension().identifier();
        Map<Identifier, Offer> out = new java.util.LinkedHashMap<>();
        QuestEngine.quests(server).listElements().forEach(h -> {
            Identifier id = h.key().identifier();
            if (!QuestEngine.visible(player, id, h.value()) || !QuestEngine.available(player, id, h.value())) return;
            Quest q = QuestEngine.questFor(player, id).orElse(h.value());
            var where = positionOf(server, id);
            double d = where.filter(w -> w.level().equals(here)).map(w -> player.position().distanceTo(w.pos())).orElse(Double.NaN);
            if (radius >= 0 && (Double.isNaN(d) || d > radius)) return;
            out.put(id, new Offer(id, q, describe(server, q), d));
        });
        // Op-placed blocks: known by block, not by quest.
        GiverStore store = GiverStore.get(server);
        for (var entry : store.view().entrySet()) {
            if (!entry.getKey().startsWith(here + "|")) continue;
            BlockPos pos = parse(entry.getKey());
            if (pos == null) continue;
            double d = player.position().distanceTo(Vec3.atCenterOf(pos));
            if (radius >= 0 && d > radius) continue;
            for (Identifier id : store.questsAt(entry.getKey())) {
                var h = QuestEngine.quest(server, id);
                if (h.isEmpty() || !QuestEngine.visible(player, id, h.get().value()) || !QuestEngine.available(player, id, h.get().value())) continue;
                Offer known = out.get(id);
                if (known != null && known.placed() && known.distance() <= d) continue;
                out.put(id, new Offer(id, h.get().value(), Lang.fmt("giver.position", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ()), d));
            }
        }
        List<Offer> list = new ArrayList<>(out.values());
        list.sort(java.util.Comparator.comparingDouble((Offer o) -> o.placed() ? o.distance() : Double.MAX_VALUE).thenComparing(o -> o.id().toString()));
        return list;
    }

    /** How many quests the last click listed at once; the self-test's window onto the menu. */
    static int lastMenuSize;

    /**
     * Several quests a player could take from one giver: say them all, each with its buttons, rather
     * than the first alone (which hid the rest of a camp's board behind one offer). Also names what they
     * are already on here, so the one click answers "what is there for me" in full. Returns false when
     * there is not more than one to list, and the caller carries on as for a single quest.
     */
    private static boolean offerAll(ServerPlayer player, String who, List<Identifier> quests) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);
        List<Identifier> takeable = new ArrayList<>();
        List<Identifier> active = new ArrayList<>();
        for (Identifier q : quests) {
            var h = QuestEngine.quest(server, q);
            if (h.isEmpty()) continue;
            if (log.isActive(q)) active.add(q);
            else if (QuestEngine.available(player, q, h.get().value())) takeable.add(q);
        }
        lastMenuSize = takeable.size();
        if (takeable.size() < 2) return false;
        Feedback.chat(player, Lang.fmt("msg.giver.menu.header", "who", who, "count", takeable.size()));
        for (Identifier id : takeable) {
            Quest q = QuestEngine.questFor(player, id).orElseGet(() -> QuestEngine.quest(server, id).get().value());
            Feedback.chatWithButtons(player, Lang.fmt("msg.giver.menu.entry", "name", q.name(), "hook", q.description().orElse("")),
                    Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")),
                    Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
        }
        for (Identifier id : active) {
            Quest q = QuestEngine.questFor(player, id).orElseGet(() -> QuestEngine.quest(server, id).get().value());
            QuestLog.Entry e = log.entry(id);
            String objective = "";
            if (e != null) {
                var objectives = QuestEngine.currentObjectives(q, e);
                for (int n = 0; n < objectives.size() && n < e.targets.size(); n++) {
                    if (!e.objectiveDone(n)) { objective = objectives.get(n).describe(); break; }
                }
            }
            Feedback.chat(player, Lang.fmt("msg.giver.menu.active", "name", q.name(), "objective", objective));
        }
        return true;
    }

    /** The tracker tick: offer whatever this player is standing near or in. */
    public static void tick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        java.util.Set<String> blocksOffered = new java.util.HashSet<>();
        for (Entry e : DATA) {
            String key = RESOLVED_KEY.get(e.id());
            if (key != null) {
                // Several quests on one block (a camp's job board): one offer, the one a click would make.
                if (!blocksOffered.add(key) || !present(player, e.id(), e.giver())) continue;
                pick(player, DATA_BLOCKS.getOrDefault(key, List.of())).ifPresent(id -> {
                    var h = QuestEngine.quest(server, id);
                    if (h.isPresent() && QuestEngine.available(player, id, h.get().value())) offer(player, id, h.get().value(), describe(server, h.get().value()));
                });
                continue;
            }
            if (!QuestEngine.available(player, e.id(), e.quest())) continue;
            if (present(player, e.id(), e.giver())) offer(player, e.id(), e.quest(), describe(server, e.quest()));
        }
        GiverStore store = GiverStore.get(server);
        if (store.size() > 0) {
            double r = ChroniclerConfig.GIVER_RADIUS.get();
            ServerLevel level = player.level();
            String dim = level.dimension().identifier().toString();
            for (var entry : store.view().entrySet()) {
                if (!entry.getKey().startsWith(dim + "|")) continue;
                BlockPos pos = parse(entry.getKey());
                if (pos == null || player.blockPosition().distSqr(pos) > r * r) continue;
                var holder = QuestEngine.quest(server, entry.getValue());
                if (holder.isEmpty() || !QuestEngine.available(player, entry.getValue(), holder.get().value())) continue;
                offer(player, entry.getValue(), holder.get().value(),
                        Lang.fmt("giver.position", "x", pos.getX(), "y", pos.getY(), "z", pos.getZ()));
            }
        }
    }

    private static boolean present(ServerPlayer player, Identifier id, GiverSpec giver) {
        if (giver instanceof GiverTypes.NpcGiver) return false; // the person is the presence; they offer on right-click
        if (giver instanceof GiverTypes.Position p) {
            // Where it was resolved (an "of" quest's dimension is its host's), else what the data says.
            Identifier dim = RESOLVED_DIM.getOrDefault(id, p.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier()));
            if (!dim.equals(player.level().dimension().identifier())) return false;
            double r = p.radius();
            BlockPos at = RESOLVED.get(id);
            if (at == null) at = p.at().orElse(null);
            return at != null && player.blockPosition().distSqr(at) <= r * r;
        }
        if (giver instanceof GiverTypes.InPlace ip) {
            return Places.isAt(player, ip.place());
        }
        return false;
    }

    /** Say it once per cooldown: the action bar for the moment, a chat line with buttons to act on. */
    public static void offer(ServerPlayer player, Identifier id, Quest quest, String where) {
        long now = player.level().getGameTime();
        long cooldown = ChroniclerConfig.GIVER_COOLDOWN_SECONDS.get() * 20L;
        Map<Identifier, Long> mine = OFFERED.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Long last = mine.get(id);
        if (last != null && now - last < cooldown) return;
        mine.put(id, now);
        offerNow(player, id, quest, where);
    }

    /** The offer, now, whatever the cooldown: a mini quest found in the wild is offered once, when it is found. */
    public static void offerNow(ServerPlayer player, Identifier id, Quest quest, String where) {
        Feedback.actionBar(player, Lang.fmt("msg.offer.bar", "name", quest.name()));
        Feedback.chatWithButtons(player, Lang.fmt("msg.offer", "name", quest.name(), "where", where),
                Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")),
                Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
    }

    /** A right-click on a giver block. Returns true if this was one. */
    public static boolean onUseBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        // A mini quest's block (the jammed door) takes its delivery -- and refuses to be used until then.
        if (QuestEngine.onDeliver(player, d -> d.at().map(pos::equals).orElse(false))) return true;
        Optional<Identifier> wild = Minis.pendingAtBlock(level, pos);
        if (wild.isPresent()) {
            var q = Minis.pendingQuestAtBlock(level, pos);
            if (q.isPresent()) return useGiver(player, wild.get(), q.get());
        }
        List<Identifier> here = questsAt(level, pos);
        if (here.isEmpty()) return false;
        // A hand-over for any quest given here comes before any offer, as at a person.
        for (Identifier q : here) if (QuestEngine.onDeliver(player, q)) return true;
        String who = QuestEngine.quest(level.getServer(), here.getFirst()).map(h -> describe(level.getServer(), h.value())).orElse(Lang.get("giver.someone"));
        if (offerAll(player, who, here)) return true;
        Optional<Identifier> id = pick(player, here);
        Optional<Holder.Reference<Quest>> holder = QuestEngine.quest(level.getServer(), id.get());
        if (holder.isEmpty()) {
            Feedback.chat(player, Lang.fmt("msg.giver.gone", "id", id.get()));
            return true;
        }
        return useGiver(player, id.get(), QuestEngine.questFor(player, id.get()).orElse(holder.get().value()));
    }

    /** Per player: quest -> game time the giver last made its offer, so a second click accepts. */
    private static final Map<UUID, Map<Identifier, Long>> CLICKED = new HashMap<>();

    /**
     * What any giver does when used. On it already: track it. Not available:
     * say why. Available: MAKE THE OFFER -- the name, the hook, what it asks
     * and what it pays, with Accept and Info buttons -- and only a second
     * click within the window (or the button) accepts. Straight to accepted
     * gave nobody a chance to choose, which Sable noticed on the first try.
     */
    private static boolean useGiver(ServerPlayer player, Identifier id, Quest quest) {
        var log = QuestEngine.journal(player);
        if (log.isActive(id)) {
            Feedback.chat(player, Lang.fmt("msg.giver.active", "name", quest.name()));
            QuestEngine.track(player, id);
            return true;
        }
        var why = QuestEngine.refusal(player, id, quest);
        if (why.isPresent()) {
            switch (why.get()) {
                case ALREADY_COMPLETE -> Feedback.chat(player, Lang.get("msg.giver.done"));
                case COOLDOWN -> Feedback.chat(player, Lang.fmt("msg.refuse.cooldown", "time", QuestEngine.clock(QuestEngine.cooldownLeft(log, id, quest) / 50L)));
                case LOCKED, CONDITIONS -> QuestEngine.explainLocked(player, id, quest);
                default -> Feedback.chat(player, Lang.get("msg.giver.locked"));
            }
            return true;
        }
        long now = player.level().getGameTime();
        Map<Identifier, Long> mine = CLICKED.computeIfAbsent(player.getUUID(), k -> new HashMap<>());
        Long last = mine.get(id);
        long window = ChroniclerConfig.GIVER_SECOND_CLICK_SECONDS.get() * 20L;
        if (last != null && now - last <= window) {
            mine.remove(id);
            QuestEngine.accept(player, id);
            return true;
        }
        mine.put(id, now);
        Feedback.chat(player, Lang.fmt("msg.giver.offer.header", "name", quest.name()));
        quest.description().ifPresent(d -> Feedback.chat(player, Lang.fmt("msg.giver.offer.description", "description", d)));
        for (var o : quest.objectivesAt(0)) Feedback.chat(player, Lang.fmt("msg.giver.offer.objective", "line", o.describe()));
        for (var r : quest.rewards()) Feedback.chat(player, Lang.fmt("msg.giver.offer.reward", "line", r.describe()));
        Feedback.chatWithButtons(player, Lang.get("msg.giver.offer.prompt"),
                Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")),
                Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
        Feedback.actionBar(player, Lang.fmt("msg.offer.bar", "name", quest.name()));
        return true;
    }

    public static void forget(UUID player) {
        OFFERED.remove(player);
        CLICKED.remove(player);
    }

    private static BlockPos parse(String key) {
        try {
            String[] xyz = key.substring(key.indexOf('|') + 1).split(",");
            return new BlockPos(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Givers() {}
}
