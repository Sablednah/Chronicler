package com.sablednah.chronicler.neoforge;

import java.util.Optional;

import net.minecraft.server.level.ServerPlayer;

/**
 * Paying a player -- the neutral bridge. Empty means "this server has no
 * economy", which is a fact to tell the player, not an error.
 * {@code compat/StandardsEconomy} fills the slot.
 */
public final class Money {

    /** Pay, and say how the economy prints the amount. */
    public interface Payer {
        Optional<String> pay(ServerPlayer player, double amount, String reason);
    }

    private static volatile Payer payer = (p, a, r) -> Optional.empty();

    public static Optional<String> pay(ServerPlayer player, double amount, String reason) {
        try {
            return payer.pay(player, amount, reason);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    public static void install(Payer p) {
        payer = p;
    }

    private Money() {}
}
