package com.sablednah.chronicler.neoforge;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.Vec3;

/**
 * The floating "!" over a giver: a {@code text_display} entity, which a
 * vanilla client draws. One per giver, shared by everyone (an entity is
 * visible to all, so it cannot know who has finished the quest). The text is
 * config, with {@code {quest}} for the name.
 *
 * <p>Same mechanics as LegendQuest's nameplate, same traps: every setter on
 * TextDisplay is private so it is configured by loading NBT, {@code load} is
 * whole-entity so position and tags are restored around it, and a marker is
 * tracked BEFORE it joins the level or the orphan reaper takes it at birth.</p>
 */
public final class Markers {

    public static final String TAG = "chronicler_marker";
    private static final Map<String, Display.TextDisplay> MARKERS = new HashMap<>();
    private static final Map<String, String> LAST_TEXT = new HashMap<>();

    /** Keep the marker set in step with the givers: create, move, retext, remove. */
    public static void sync(MinecraftServer server, Map<String, Identifier> givers,
            java.util.function.Function<String, Vec3> positionOf, java.util.function.Function<String, ServerLevel> levelOf) {
        if (!ChroniclerConfig.GIVER_MARKERS.get()) {
            clear();
            return;
        }
        Set<String> seen = new HashSet<>();
        for (var e : givers.entrySet()) {
            String key = e.getKey();
            ServerLevel level = levelOf.apply(key);
            Vec3 at = positionOf.apply(key);
            if (level == null || at == null || !level.isLoaded(BlockPos.containing(at))) continue;
            var quest = QuestEngine.quest(server, e.getValue());
            if (quest.isEmpty()) continue;
            seen.add(key);
            ensure(level, key, at, text(quest.get().value()));
        }
        for (String key : Set.copyOf(MARKERS.keySet())) {
            if (!seen.contains(key)) remove(key);
        }
    }

    private static String text(Quest quest) {
        return ChroniclerConfig.GIVER_MARKER_TEXT.get().replace("{quest}", quest.name());
    }

    private static void ensure(ServerLevel level, String key, Vec3 at, String text) {
        Display.TextDisplay marker = MARKERS.get(key);
        if (marker != null && marker.isRemoved()) { MARKERS.remove(key); marker = null; }
        if (marker == null) {
            marker = EntityType.TEXT_DISPLAY.create(level, EntitySpawnReason.COMMAND);
            if (marker == null) return;
            marker.snapTo(at.x, at.y, at.z, 0F, 0F);
            marker.addTag(TAG);
            apply(marker, level, text);
            MARKERS.put(key, marker); // before addFreshEntity: the reaper runs on join
            LAST_TEXT.put(key, text);
            level.addFreshEntity(marker);
            return;
        }
        if (marker.distanceToSqr(at) > 0.01) marker.snapTo(at.x, at.y, at.z, 0F, 0F);
        if (!text.equals(LAST_TEXT.get(key))) {
            apply(marker, level, text);
            LAST_TEXT.put(key, text);
        }
    }

    private static void apply(Display.TextDisplay display, ServerLevel level, String text) {
        CompoundTag tag = new CompoundTag();
        tag.put("text", ComponentSerialization.CODEC.encodeStart(NbtOps.INSTANCE, Feedback.colored(text)).getOrThrow());
        tag.putString("billboard", "center");
        tag.putBoolean("see_through", false);
        tag.putBoolean("shadow", true);
        tag.putString("alignment", "center");
        tag.putInt("teleport_duration", 2);
        double x = display.getX(), y = display.getY(), z = display.getZ();
        display.load(TagValueInput.create(ProblemReporter.DISCARDING, level.registryAccess(), tag));
        display.snapTo(x, y, z, 0F, 0F);
        display.addTag(TAG);
    }

    public static void remove(String key) {
        Display.TextDisplay m = MARKERS.remove(key);
        LAST_TEXT.remove(key);
        if (m != null) m.discard();
    }

    /** A tagged display nobody tracks: left from before a restart. */
    public static boolean isOrphan(Entity entity) {
        return entity instanceof Display.TextDisplay d && d.getTags().contains(TAG) && !MARKERS.containsValue(d);
    }

    public static boolean isMarker(Entity entity) {
        return entity instanceof Display.TextDisplay d && d.getTags().contains(TAG);
    }

    public static int count() {
        return MARKERS.size();
    }

    public static void clear() {
        MARKERS.values().forEach(Entity::discard);
        MARKERS.clear();
        LAST_TEXT.clear();
    }

    private Markers() {}
}
