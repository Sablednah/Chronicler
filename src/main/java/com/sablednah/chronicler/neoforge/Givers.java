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
                DATA_BLOCKS.put(dim + "|" + p.at().getX() + "," + p.at().getY() + "," + p.at().getZ(), h.key().identifier());
            }
            if (g instanceof GiverTypes.NpcGiver n) placeNpc(server, h.key().identifier(), n);
        }));
        OFFERED.clear();
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
            return;
        }
        Identifier dim = n.dimension().orElse(net.minecraft.world.level.Level.OVERWORLD.identifier());
        ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (level == null) return;
        Vec3 pos = new Vec3(n.at().getX() + 0.5, n.at().getY(), n.at().getZ() + 0.5);
        UUID id = n.entity().isPresent()
                ? cast.spawnMob(level, pos, n.yaw(), n.entity().get(), n.name())
                : cast.spawnHuman(level, pos, n.yaw(), n.name(), n.skin());
        store.setPlacedFor(questId, id);
        store.setNpc(id, questId);
        Chronicler.LOGGER.info("Chronicler: placed NPC giver '{}' for {} at {}", n.name(), questId, n.at());
    }

    /** A right-click on a Cast NPC carrying the giver role. */
    public static boolean onUseNpc(ServerPlayer player, UUID npcId) {
        MinecraftServer server = player.level().getServer();
        Optional<Identifier> id = GiverStore.get(server).atNpc(npcId);
        if (id.isEmpty()) {
            Feedback.chat(player, Lang.get("msg.giver.npc_idle"));
            return true;
        }
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
        Map<String, Identifier> all = new HashMap<>(DATA_BLOCKS);
        all.putAll(GiverStore.get(server).view());
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
            if (present(player, e.giver())) offer(player, e.id(), e.quest(), e.giver().describe());
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

    private static boolean present(ServerPlayer player, GiverSpec giver) {
        if (giver instanceof GiverTypes.NpcGiver) return false; // the person is the presence; they offer on right-click
        if (giver instanceof GiverTypes.Position p) {
            if (p.dimension().isPresent() && !p.dimension().get().equals(player.level().dimension().identifier())) return false;
            if (p.dimension().isEmpty() && !player.level().dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) return false;
            double r = p.radius();
            return player.blockPosition().distSqr(p.at()) <= r * r;
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
            Feedback.chat(player, Lang.get(switch (why.get()) {
                case ALREADY_COMPLETE -> "msg.giver.done";
                default -> "msg.giver.locked";
            }));
            if (why.get() == QuestEngine.Refusal.CONDITIONS) {
                QuestEngine.unmet(player, quest).forEach(line -> Feedback.chat(player, Lang.fmt("msg.refuse.condition_line", "line", line)));
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
