package com.sablednah.chronicler.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * The floating mark over a giver -- and it is <em>yours</em>: a {@code !} while
 * you could take the quest, a {@code ?} while you are on it, a tick once it is
 * done, nothing while it is locked. Every player sees their own.
 *
 * <p>That is only possible because there is no entity. A {@code text_display}
 * in the level is one thing seen by everyone, so a shared marker cannot know
 * who has finished. Instead each player is sent a private one by packets
 * (the way Cast draws a phantom): a display object built server-side and
 * never added to a level, spawned onto that client, re-sent when its text
 * changes, removed when they leave range. Vanilla clients draw it; nothing
 * needs reaping after a restart because nothing was ever in the world.</p>
 */
public final class Markers {

    private record Shown(Display.TextDisplay display, String text, Vec3 at) {}

    /** giver key -> viewer -> what they were sent. */
    private static final Map<String, Map<UUID, Shown>> SHOWN = new HashMap<>();
    private static final double RANGE = 64.0;
    /** Viewers not in the player list (the self-test's FakePlayers). */
    static final Set<ServerPlayer> EXTRA_VIEWERS = new HashSet<>();

    public static void sync(MinecraftServer server, Map<String, List<Identifier>> givers,
            Function<String, Vec3> positionOf, Function<String, ServerLevel> levelOf) {
        if (!ChroniclerConfig.GIVER_MARKERS.get()) {
            clear(server);
            return;
        }
        Set<String> live = new HashSet<>();
        for (var e : givers.entrySet()) {
            String key = e.getKey();
            ServerLevel level = levelOf.apply(key);
            Vec3 at = positionOf.apply(key);
            List<Quest> quests = new java.util.ArrayList<>();
            for (Identifier q : e.getValue()) QuestEngine.quest(server, q).ifPresent(h -> quests.add(h.value()));
            if (level == null || at == null || quests.isEmpty() || !level.isLoaded(BlockPos.containing(at))) continue;
            live.add(key);
            Map<UUID, Shown> viewers = SHOWN.computeIfAbsent(key, k -> new HashMap<>());
            Set<UUID> seen = new HashSet<>();
            for (ServerPlayer player : viewersOf(server)) {
                seen.add(player.getUUID());
                boolean near = player.level() == level && player.distanceToSqr(at) <= RANGE * RANGE;
                String text = near ? textFor(player, e.getValue(), quests) : "";
                Shown shown = viewers.get(player.getUUID());
                if (text.isEmpty()) {
                    if (shown != null) { hide(player, shown); viewers.remove(player.getUUID()); }
                    continue;
                }
                if (shown == null) {
                    viewers.put(player.getUUID(), show(player, level, at, text));
                } else {
                    if (!text.equals(shown.text())) shown = retext(player, level, shown, text);
                    // The giver moved (a possessed NPC walked off): the mark follows, or it hangs where they were.
                    if (shown.at().distanceToSqr(at) > 0.0001D) shown = move(player, shown, at);
                    viewers.put(player.getUUID(), shown);
                }
            }
            viewers.keySet().removeIf(id -> !seen.contains(id));
        }
        for (String key : Set.copyOf(SHOWN.keySet())) {
            if (!live.contains(key)) {
                for (var v : SHOWN.remove(key).entrySet()) {
                    ServerPlayer p = server.getPlayerList().getPlayer(v.getKey());
                    if (p != null) hide(p, v.getValue());
                }
            }
        }
    }

    private static List<ServerPlayer> viewersOf(MinecraftServer server) {
        if (EXTRA_VIEWERS.isEmpty()) return server.getPlayerList().getPlayers();
        List<ServerPlayer> all = new java.util.ArrayList<>(server.getPlayerList().getPlayers());
        all.addAll(EXTRA_VIEWERS);
        return all;
    }

    /**
     * What this player's mark says: their state, their text. Empty means no mark.
     * Several quests on one giver: on one of them wins, then one on offer, then all
     * finished, else locked -- so a person with a whole storyline shows what matters now.
     */
    private static String textFor(ServerPlayer player, List<Identifier> ids, List<Quest> quests) {
        var log = QuestEngine.journal(player);
        Quest named = quests.getFirst();
        String text = null;
        for (int n = 0; n < ids.size() && n < quests.size(); n++) {
            if (log.isActive(ids.get(n))) { text = ChroniclerConfig.GIVER_MARKER_ACTIVE.get(); named = quests.get(n); break; }
        }
        if (text == null) for (int n = 0; n < ids.size() && n < quests.size(); n++) {
            if (QuestEngine.available(player, ids.get(n), quests.get(n))) { text = ChroniclerConfig.GIVER_MARKER_TEXT.get(); named = quests.get(n); break; }
        }
        if (text == null) {
            boolean allDone = true;
            for (int n = 0; n < ids.size() && n < quests.size(); n++) {
                if (!(log.isComplete(ids.get(n)) && !quests.get(n).repeatable())) { allDone = false; break; }
            }
            text = allDone ? ChroniclerConfig.GIVER_MARKER_COMPLETE.get() : ChroniclerConfig.GIVER_MARKER_LOCKED.get();
        }
        return text.replace("{quest}", named.name());
    }

    private static Shown show(ServerPlayer player, ServerLevel level, Vec3 at, String text) {
        Display.TextDisplay display = EntityTypes.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
        display.snapTo(at.x, at.y, at.z, 0F, 0F);
        apply(display, level, text);
        if (player.connection != null) {
            player.connection.send(new ClientboundAddEntityPacket(display.getId(), display.getUUID(),
                    at.x, at.y, at.z, 0F, 0F, EntityTypes.TEXT_DISPLAY, 0, Vec3.ZERO, 0D));
            sendData(player, display);
        }
        return new Shown(display, text, at);
    }

    private static Shown move(ServerPlayer player, Shown shown, Vec3 at) {
        shown.display().snapTo(at.x, at.y, at.z, 0F, 0F);
        if (player.connection != null) player.connection.send(net.minecraft.network.protocol.game.ClientboundEntityPositionSyncPacket.of(shown.display()));
        return new Shown(shown.display(), shown.text(), at);
    }

    private static Shown retext(ServerPlayer player, ServerLevel level, Shown shown, String text) {
        apply(shown.display(), level, text);
        sendData(player, shown.display());
        return new Shown(shown.display(), text, shown.at());
    }

    private static void sendData(ServerPlayer player, Display.TextDisplay display) {
        List<SynchedEntityData.DataValue<?>> values = display.getEntityData().getNonDefaultValues();
        if (values != null && !values.isEmpty() && player.connection != null) {
            player.connection.send(new ClientboundSetEntityDataPacket(display.getId(), values));
        }
    }

    private static void hide(ServerPlayer player, Shown shown) {
        if (player.connection != null) player.connection.send(new ClientboundRemoveEntitiesPacket(shown.display().getId()));
    }

    /** Every setter on TextDisplay is private; NBT is the way in. load() is whole-entity, so position is restored. */
    private static void apply(Display.TextDisplay display, ServerLevel level, String text) {
        CompoundTag tag = new CompoundTag();
        tag.put("text", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, Feedback.colored(text)).getOrThrow());
        tag.putString("billboard", "center");
        tag.putBoolean("see_through", false);
        tag.putBoolean("shadow", true);
        tag.putString("alignment", "center");
        double x = display.getX(), y = display.getY(), z = display.getZ();
        display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        display.snapTo(x, y, z, 0F, 0F);
    }

    /** A viewer left: forget what they were sent (their client dropped it with the connection). */
    public static void forget(UUID player) {
        SHOWN.values().forEach(v -> v.remove(player));
    }

    /** Marks shown to this player right now, for the self-test. */
    public static int shownTo(UUID player) {
        int n = 0;
        for (var v : SHOWN.values()) if (v.containsKey(player)) n++;
        return n;
    }

    public static Vec3 positionShownTo(UUID player, String key) {
        Shown s = SHOWN.getOrDefault(key, Map.of()).get(player);
        return s == null ? null : s.at();
    }

    public static String textShownTo(UUID player, String key) {
        Shown s = SHOWN.getOrDefault(key, Map.of()).get(player);
        return s == null ? "" : s.text();
    }

    public static void clear(MinecraftServer server) {
        for (var v : SHOWN.values()) {
            for (var e : v.entrySet()) {
                ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
                if (p != null) hide(p, e.getValue());
            }
        }
        SHOWN.clear();
    }

    private Markers() {}
}
