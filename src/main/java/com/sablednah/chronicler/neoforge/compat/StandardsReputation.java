package com.sablednah.chronicler.neoforge.compat;

import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Rep;
import com.sablednah.standards.api.reputation.Reputation;

import net.minecraft.server.level.ServerPlayer;

/**
 * Reputation through Standards' {@code api/reputation} (1.5.0+). The ONLY
 * class that may import it. Wired through {@code optionalIntegration}, so a
 * Standards too old to carry the package costs this seam and nothing else.
 *
 * <p>Standing names are normalised by the facade (lower-cased, trimmed);
 * we hand them over as written and let it agree with itself. Bands are the
 * store's own words for a value ({@code Reputation.band}), configurable per
 * standing over there -- <b>text only, never a condition</b>; a threshold is
 * a number and {@code ReputationEvent.crossed(n)} is the hook for it.</p>
 */
public final class StandardsReputation {

    public static void register() {
        // Touch the facade so a missing class or method fails HERE, inside the
        // guard: a LinkageError at reward time would be caught by nothing.
        Reputation.normalise("probe");
        Reputation.band("probe", 0);
        Rep.install(new Rep.Provider() {
            @Override public boolean available() { return Reputation.isAvailable(); }
            @Override public int get(ServerPlayer player, String standing) {
                return Reputation.get(player.getUUID(), standing);
            }
            @Override public int adjust(ServerPlayer player, String standing, int delta, String reason) {
                return Reputation.adjust(player.getUUID(), standing, delta, reason);
            }
            @Override public Optional<String> band(String standing, int value) {
                return Reputation.band(standing, value);
            }
        });
        Chronicler.LOGGER.info("Chronicler: reputation via Standards");
    }

    private StandardsReputation() {}
}
