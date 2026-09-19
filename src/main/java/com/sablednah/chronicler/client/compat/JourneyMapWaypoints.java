package com.sablednah.chronicler.client.compat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sablednah.chronicler.network.WaypointsPayload;
import com.sablednah.chronicler.network.WaypointsPayload.Mark;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Chronicler's own marks, kept in step with whatever JourneyMap is currently showing. A
 * {@link Mark}'s own {@code id} is stable resend to resend (see {@link WaypointsPayload}), so a
 * waypoint already on the map is moved and renamed in place rather than flickered away and back
 * -- an escorted NPC updates every second, and a torn-down-and-rebuilt marker would never look
 * settled. {@code persistent = false}: it never survives past this session and is never shared to
 * another player -- a live readout of server state, not a landmark worth remembering on its own.
 *
 * <p>{@code createWaypoint}'s single {@code String} parameter is the calling mod's id, not a
 * display name -- {@link WaypointFactory}'s own javadoc says as much, and an unnamed waypoint
 * falls back to its coordinates, which is exactly what a bare {@code createClientWaypoint(name,
 * pos, dim, persistent)} call produced: a real name, quietly read as {@code modId}. The overload
 * that actually takes a name is what {@link #sync} calls now.</p>
 */
public final class JourneyMapWaypoints {

    private static final String MOD_ID = "chronicler";
    private static final String GROUP_NAME = "Quests";
    private static final Map<String, Waypoint> SHOWN = new HashMap<>();
    private static WaypointGroup group;

    public static void sync(List<Mark> marks) {
        IClientAPI api = JourneyMapPlugin.api();
        if (api == null) return;
        Set<String> seen = new HashSet<>();
        for (Mark m : marks) {
            seen.add(m.id());
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, m.dimension());
            Waypoint existing = SHOWN.get(m.id());
            if (existing != null) {
                existing.setName(m.label());
                existing.setPos(m.x(), m.y(), m.z());
                existing.setPrimaryDimension(dimension);
                continue;
            }
            try {
                Waypoint w = WaypointFactory.createWaypoint(MOD_ID, new BlockPos(m.x(), m.y(), m.z()), m.label(), dimension, false);
                w.setColor(colorOf(m.kind()));
                group(api).addWaypoint(w);
                api.addWaypoint(MOD_ID, w);
                SHOWN.put(m.id(), w);
            } catch (Exception ignored) {
                // A world JourneyMap has not finished loading yet, most likely; next second's sync tries again.
            }
        }
        SHOWN.keySet().removeIf(id -> {
            if (seen.contains(id)) return false;
            try { api.removeWaypoint(MOD_ID, SHOWN.get(id)); } catch (Exception ignored) {}
            return true;
        });
    }

    /** One "Quests" folder, found again by name if a previous session (or this one) already made it. */
    private static WaypointGroup group(IClientAPI api) {
        if (group != null) return group;
        WaypointGroup existing = api.getWaypointGroupByName(MOD_ID, GROUP_NAME);
        if (existing != null) return group = existing;
        group = WaypointFactory.createWaypointGroup(MOD_ID, GROUP_NAME);
        api.addWaypointGroup(group);
        return group;
    }

    /** A fresh world, or a disconnect: nothing on the map is still ours to update. */
    public static void clear() {
        IClientAPI api = JourneyMapPlugin.api();
        if (api != null) {
            try { api.removeAllWaypoints(MOD_ID); } catch (Exception ignored) {}
        }
        SHOWN.clear();
    }

    private static int colorOf(byte kind) {
        return switch (kind) {
            case WaypointsPayload.GIVER -> 0xFFD700; // gold: something waiting to be found
            case WaypointsPayload.NPC -> 0x40C040; // green: someone alive, moving
            default -> 0x4090FF; // blue: a "go here"
        };
    }

    private JourneyMapWaypoints() {}
}
