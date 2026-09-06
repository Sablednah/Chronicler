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
 * we hand them over as written and let it agree with itself. Bands are
 * display-only and not in the API yet; {@link #band} answers empty until
 * Standards exposes a text lookup.</p>
 */
public final class StandardsReputation {

    public static void register() {
        // Touch the facade so a missing class fails HERE, inside the guard.
        Reputation.normalise("probe");
        Rep.install(new Rep.Provider() {
            @Override public boolean available() { return Reputation.isAvailable(); }
            @Override public int get(ServerPlayer player, String standing) {
                return Reputation.get(player.getUUID(), standing);
            }
            @Override public int adjust(ServerPlayer player, String standing, int delta, String reason) {
                return Reputation.adjust(player.getUUID(), standing, delta, reason);
            }
            @Override public Optional<String> band(String standing, int value) {
                return Optional.empty();
            }
        });
        Chronicler.LOGGER.info("Chronicler: reputation via Standards");
    }

    private StandardsReputation() {}
}
