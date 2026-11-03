package com.sablednah.chronicler.client.compat;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.sablednah.chronicler.network.WaypointsPayload;
import com.sablednah.chronicler.network.WaypointsPayload.Mark;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.display.MarkerOverlay;
import journeymap.api.v2.client.model.MapImage;
import journeymap.api.v2.client.model.TextProperties;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;
import journeymap.api.v2.common.waypoint.WaypointGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
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
        Set<String> people = new HashSet<>();
        for (Mark m : marks) {
            if (m.kind() == WaypointsPayload.PERSON || m.kind() == WaypointsPayload.PERSON_OFFERS) {
                people.add(m.id());
                person(api, m);
                continue;
            }
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
        PEOPLE.keySet().removeIf(id -> {
            if (people.contains(id)) return false;
            try { api.remove(PEOPLE.get(id).overlay()); } catch (Exception ignored) {}
            return true;
        });
    }

    /**
     * A quest's person, drawn as a radar draws someone: a mark with their name on the minimap and the full map, never
     * a waypoint (nothing to save, nothing in the waypoint list). Redrawn only when they moved, were renamed or now
     * have a quest for you -- the server already sends only what changed, and so does this.
     */
    private record Person(Mark mark, MarkerOverlay overlay) {}

    private static final Map<String, Person> PEOPLE = new HashMap<>();
    private static final Identifier PERSON_ICON = Identifier.fromNamespaceAndPath(MOD_ID, "textures/map/person.png");

    private static void person(IClientAPI api, Mark m) {
        Person was = PEOPLE.get(m.id());
        if (was != null && was.mark().equals(m)) return;
        if (was != null) {
            try { api.remove(was.overlay()); } catch (Exception ignored) {}
            PEOPLE.remove(m.id());
        }
        int colour = colorOf(m.kind());
        MapImage icon = new MapImage(PERSON_ICON, 32, 32).setColor(colour).setDisplayWidth(12).setDisplayHeight(12).centerAnchors();
        MarkerOverlay overlay = new MarkerOverlay(MOD_ID, new BlockPos(m.x(), m.y(), m.z()), icon);
        overlay.setDimension(ResourceKey.create(Registries.DIMENSION, m.dimension()));
        overlay.setLabel(m.label());
        overlay.setTitle(m.label());
        overlay.setTextProperties(new TextProperties().setColor(colour).setOffsetY(10).setFontShadow(true).setScale(1F));
        try {
            api.show(overlay);
            PEOPLE.put(m.id(), new Person(m, overlay));
        } catch (Exception ignored) {
            // JourneyMap not ready for this world yet; the next change (or the next world) shows them.
        }
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
            try { api.removeAll(MOD_ID); } catch (Exception ignored) {}
        }
        SHOWN.clear();
        PEOPLE.clear();
        group = null; // found or made again in the next world: JourneyMap keeps waypoints per world, and so may groups
    }

    private static int colorOf(byte kind) {
        return switch (kind) {
            case WaypointsPayload.GIVER -> 0xFFD700; // gold: something waiting to be found
            case WaypointsPayload.NPC -> 0x40C040; // green: someone alive, moving
            case WaypointsPayload.PERSON_OFFERS -> 0xFFD700; // gold, like a giver's waypoint: a quest for you here
            case WaypointsPayload.PERSON -> 0xF0F0F0; // near-white: one of the story's people, nothing for you now
            default -> 0x4090FF; // blue: a "go here"
        };
    }

    private JourneyMapWaypoints() {}
}
