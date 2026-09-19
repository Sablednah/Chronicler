package com.sablednah.chronicler.client.compat;

import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;

/**
 * Discovered by JourneyMap itself through the {@link journeymap.api.v2.common.JourneyMapPlugin}
 * annotation -- it scans every mod's classes for it, so nothing here needs to register this
 * class anywhere. This IS the seam: only this class and {@link JourneyMapWaypoints} import a
 * JourneyMap type. Nothing outside {@code client/compat} may reference either -- a client
 * without JourneyMap installed must never load them, and Java only loads a class on first
 * active use, so the rest of the mod never touching them is what keeps that true.
 */
@journeymap.api.v2.common.JourneyMapPlugin(apiVersion = "2.0.0")
public final class JourneyMapPlugin implements IClientPlugin {

    private static volatile IClientAPI api;

    @Override
    public void initialize(IClientAPI jmAPI) {
        api = jmAPI;
    }

    @Override
    public String getModId() {
        return "chronicler";
    }

    /** Null until JourneyMap has actually called {@link #initialize}, even once loaded. */
    static IClientAPI api() {
        return api;
    }
}
