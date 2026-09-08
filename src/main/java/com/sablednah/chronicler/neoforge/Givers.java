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
    private static final Map<String, Identifier> DATA_BLOCKS = new HashMap<>();
    /** Where each data position giver actually is (fixed, or dropped near spawn and remembered). */
    private static final Map<Identifier, BlockPos> RESOLVED = new HashMap<>();
    /** Per player: quest -> game time of the last offer, so a giver does not nag. */
    private static final Map<UUID, Map<Identifier, Long>> OFFERED = new HashMap<>();

    private record Entry(Identifier id, Quest quest, GiverSpec giver) {}

    public static void index(MinecraftServer server) {
        DATA.clear();
        DATA_BLOCKS.clear();
        QuestEngine.quests(server).listElements().forEach(h -> h.value().giver().ifPresent(g -> {
            DATA.add(new Entry(h.key().identifier(), h.value(), g));
            if (g instanceof GiverTypes.Position p) {
                Identifier dim = p.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier());
                BlockPos at = resolve(server, h.key().identifier(), p, dim);
                if (at != null) {
                    RESOLVED.put(h.key().identifier(), at);
                    DATA_BLOCKS.put(dim + "|" + at.getX() + "," + at.getY() + "," + at.getZ(), h.key().identifier());
                }
            }
            if (g instanceof GiverTypes.NpcGiver n && n.of().isEmpty()) placeNpc(server, h.key().identifier(), n);
        }));
        // Second pass: quests that share another quest's NPC, once every NPC stands.
        for (Entry e : List.copyOf(DATA)) {
            if (e.giver() instanceof GiverTypes.NpcGiver n && n.of().isPresent()) shareNpc(server, e.id(), n.of().get());
        }
        OFFERED.clear();
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
        var spawn = server.overworld().getRespawnData().globalPos().pos();
        int x = spawn.getX() + p.nearSpawn().get().get(0), z = spawn.getZ() + p.nearSpawn().get().get(1);
        BlockPos at = surface(level, x, z);
        p.block().ifPresent(b -> placeBlock(level, at, b, questId));
        for (GiverTypes.Position.Decor d : p.decor()) {
            BlockPos column = surface(level, at.getX() + d.offset().get(0), at.getZ() + d.offset().get(2));
            placeBlock(level, column.above(d.offset().get(1)), d.block(), questId);
        }
        store.setPlacedBlock(questId, at);
        Chronicler.LOGGER.info("Chronicler: placed the giver block for {} at {}", questId, at);
        return at;
    }

    private static BlockPos surface(ServerLevel level, int x, int z) {
        level.getChunk(x >> 4, z >> 4); // generate it, or the heightmap answers for air
        return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
    }

    private static void placeBlock(ServerLevel level, BlockPos pos, String block, Identifier questId) {
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
            return;
        }
        Identifier dim = n.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier());
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null) return;
        net.minecraft.core.BlockPos at = n.at().orElseGet(() -> {
            // An offset from the world spawn, dropped onto the surface: a shipped camp stands on any seed.
            var spawn = server.overworld().getRespawnData().globalPos().pos();
            int x = spawn.getX() + n.nearSpawn().get().get(0), z = spawn.getZ() + n.nearSpawn().get().get(1);
            level.getChunk(x >> 4, z >> 4); // generate it, or the heightmap answers for air
            return level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    new net.minecraft.core.BlockPos(x, 0, z));
        });
        Vec3 pos = new Vec3(at.getX() + 0.5, at.getY(), at.getZ() + 0.5);
        UUID id = n.entity().isPresent()
                ? cast.spawnMob(level, pos, n.yaw(), n.entity().get(), n.name())
                : cast.spawnHuman(level, pos, n.yaw(), n.name(), n.skin());
        store.setPlacedFor(questId, id);
        store.setNpc(id, questId);
        if (!n.equipment().isEmpty()) cast.equip(server, id, n.equipment());
        Chronicler.LOGGER.info("Chronicler: placed NPC giver '{}' for {} at {}", n.name(), questId, at);
    }

    /** A right-click on a Cast NPC carrying the giver role. */
    public static boolean onUseNpc(ServerPlayer player, UUID npcId) {
        MinecraftServer server = player.level().getServer();
        List<Identifier> quests = GiverStore.get(server).questsAtNpc(npcId);
        if (quests.isEmpty()) {
            Feedback.chat(player, Lang.get("msg.giver.npc_idle"));
            return true;
        }
        // Several quests on one person: the first the player could take, else the first they are on, else the first.
        Optional<Identifier> id = Optional.empty();
        for (Identifier q : quests) {
            var h = QuestEngine.quest(server, q);
            if (h.isPresent() && QuestEngine.available(player, q, h.get().value())) { id = Optional.of(q); break; }
        }
        if (id.isEmpty()) for (Identifier q : quests) if (QuestEngine.journal(player).isActive(q)) { id = Optional.of(q); break; }
        if (id.isEmpty()) id = Optional.of(quests.getFirst());
        Optional<Holder.Reference<Quest>> holder = QuestEngine.quest(server, id.get());
        if (holder.isEmpty()) {
            Feedback.chat(player, Lang.fmt("msg.giver.gone", "id", id.get()));
            return true;
        }
        Quest quest = holder.get().value();
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

    /** The quest a block offers, from data or from the store. */
    public static Optional<Identifier> questAt(ServerLevel level, BlockPos pos) {
        Identifier data = DATA_BLOCKS.get(GiverStore.key(level, pos));
        if (data != null) return Optional.of(data);
        return GiverStore.get(level.getServer()).at(level, pos);
    }

    /** Once a second for the whole server: the floating markers over every giver. */
    public static void tickMarkers(MinecraftServer server) {
        GiverStore store = GiverStore.get(server);
        Map<String, List<Identifier>> all = new HashMap<>();
        DATA_BLOCKS.forEach((k, v) -> all.put(k, List.of(v)));
        store.view().keySet().forEach(k -> all.put(k, store.questsAt(k)));
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

    /** Above a block, or above an NPC's head; null when the NPC is not loaded. */
    private static Vec3 markerPosition(MinecraftServer server, String key) {
        if (key.startsWith("npc|")) {
            try {
                UUID id = UUID.fromString(key.substring(4));
                return Npcs.provider().flatMap(c -> c.byId(server, id)).map(p -> p.pos().add(0, 2.35, 0)).orElse(null);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        BlockPos pos = parse(key);
        return pos == null ? null : new Vec3(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
    }

    /** The tracker tick: offer whatever this player is standing near or in. */
    public static void tick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        for (Entry e : DATA) {
            if (!QuestEngine.available(player, e.id(), e.quest())) continue;
            if (present(player, e.id(), e.giver())) offer(player, e.id(), e.quest(), e.giver().describe());
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
            if (p.dimension().isPresent() && !p.dimension().get().equals(player.level().dimension().identifier())) return false;
            if (p.dimension().isEmpty() && !player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return false;
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
        Feedback.actionBar(player, Lang.fmt("msg.offer.bar", "name", quest.name()));
        Feedback.chatWithButtons(player, Lang.fmt("msg.offer", "name", quest.name(), "where", where),
                Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")),
                Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
    }

    /** A right-click on a giver block. Returns true if this was one. */
    public static boolean onUseBlock(ServerPlayer player, ServerLevel level, BlockPos pos) {
        Optional<Identifier> id = questAt(level, pos);
        if (id.isEmpty()) return false;
        Optional<Holder.Reference<Quest>> holder = QuestEngine.quest(level.getServer(), id.get());
        if (holder.isEmpty()) {
            Feedback.chat(player, Lang.fmt("msg.giver.gone", "id", id.get()));
            return true;
        }
        return useGiver(player, id.get(), holder.get().value());
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
