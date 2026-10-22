package com.sablednah.chronicler.neoforge.compat;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Claims;

import net.minecraft.world.level.ChunkPos;

/**
 * Claimed land through Standards' Claims seam -- Factions ReForged's claims, and any other provider
 * registered there. The ONLY class that may import {@code com.sablednah.standards.api.groups.Claims};
 * loaded only when Standards is present, via {@code Chronicler.optionalIntegration}. A Standards with
 * no claim provider answers "nobody's", which is also the answer without Standards.
 */
public final class StandardsClaims {

    public static void register() {
        Claims.install("standards", (level, pos) -> com.sablednah.standards.api.groups.Claims.isAvailable()
                && com.sablednah.standards.api.groups.Claims.owner(level, new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4)).isPresent());
        Chronicler.LOGGER.info("Chronicler: claimed land via Standards claims (a mini quest's block slot skips it)");
    }

    private StandardsClaims() {}
}
