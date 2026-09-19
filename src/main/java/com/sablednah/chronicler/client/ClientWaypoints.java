package com.sablednah.chronicler.client;

import com.sablednah.chronicler.network.WaypointsPayload;

import net.neoforged.fml.ModList;

/**
 * The guarded door to {@code client/compat/JourneyMapWaypoints}: this class never imports a
 * JourneyMap type, so it loads and runs fine with no JourneyMap installed at all. Only once
 * {@code ModList} confirms it is present do we ever touch the compat class -- the same
 * {@code LinkageError}-catching discipline {@code Chronicler.optionalIntegration} uses
 * server-side, because "present" is not "new enough".
 */
public final class ClientWaypoints {

    private static boolean loggedFailure;

    public static void accept(WaypointsPayload payload) {
        if (!ModList.get().isLoaded("journeymap")) return;
        try {
            com.sablednah.chronicler.client.compat.JourneyMapWaypoints.sync(payload.marks());
        } catch (LinkageError e) {
            if (!loggedFailure) {
                loggedFailure = true;
                com.sablednah.chronicler.Chronicler.LOGGER.warn(
                        "JourneyMap is installed but too old for this build's API -- quest markers are off. ({})", e.toString());
            }
        }
    }

    public static void clear() {
        if (!ModList.get().isLoaded("journeymap")) return;
        try {
            com.sablednah.chronicler.client.compat.JourneyMapWaypoints.clear();
        } catch (LinkageError ignored) {}
    }

    private ClientWaypoints() {}
}
