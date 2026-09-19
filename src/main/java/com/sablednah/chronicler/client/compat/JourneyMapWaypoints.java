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
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Chronicler's own marks, kept in step with whatever JourneyMap is currently showing. A
 * {@link Mark}'s own {@code id} is stable resend to resend (see {@link WaypointsPayload}), so a
 * waypoint already on the map is moved and renamed in place rather than flickered away and back
 * -- an escorted NPC updates every second, and a torn-down-and-rebuilt marker would never look
 * settled. {@code createClientWaypoint} never shares this to another player or persists it past
 * this session: it is a live readout of server state, not something worth remembering on its own.
 */
public final class JourneyMapWaypoints {

    private static final String MOD_ID = "chronicler";
    private static final Map<String, Waypoint> SHOWN = new HashMap<>();

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
                Waypoint w = WaypointFactory.createClientWaypoint(m.label(), new BlockPos(m.x(), m.y(), m.z()), dimension, false);
                w.setColor(colorOf(m.kind()));
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
