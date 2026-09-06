package com.sablednah.chronicler.neoforge;

import java.util.Optional;
import java.util.OptionalInt;

import net.minecraft.server.level.ServerPlayer;

/**
 * Reputation -- the neutral bridge. Standings live in Standards
 * ({@code api/reputation}); this answers "no opinion" on a server without it.
 * {@code compat/StandardsReputation} fills the slot.
 */
public final class Rep {

    public interface Provider {
        boolean available();
        int get(ServerPlayer player, String standing);
        /** Adjust; returns where the value LANDED after clamping. */
        int adjust(ServerPlayer player, String standing, int delta, String reason);
        Optional<String> band(String standing, int value);
    }

    private static volatile Provider provider = null;

    public static boolean available() {
        Provider p = provider;
        try {
            return p != null && p.available();
        } catch (RuntimeException e) {
            return false;
        }
    }

    public static int get(ServerPlayer player, String standing) {
        Provider p = provider;
        if (p == null) return 0;
        try {
            return p.get(player, standing);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Empty when nothing keeps reputation on this server; otherwise the landed value. */
    public static OptionalInt adjust(ServerPlayer player, String standing, int delta, String reason) {
        Provider p = provider;
        if (p == null || !available()) return OptionalInt.empty();
        try {
            return OptionalInt.of(p.adjust(player, standing, delta, reason));
        } catch (RuntimeException e) {
            return OptionalInt.empty();
        }
    }

    /** The display word for a value, if the store names bands. Text only, never logic. */
    public static Optional<String> band(String standing, int value) {
        Provider p = provider;
        if (p == null) return Optional.empty();
        try {
            return p.band(standing, value);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void install(Provider p) {
        provider = p;
    }

    private Rep() {}
}
