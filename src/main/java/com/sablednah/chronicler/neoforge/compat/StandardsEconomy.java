package com.sablednah.chronicler.neoforge.compat;

import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Money;
import com.sablednah.standards.api.economy.Economy;

/**
 * Money rewards through Standards' economy facade. The ONLY class that may
 * import {@code com.sablednah.standards.api.economy}.
 *
 * <p>Standards being installed and the server having an economy provider are
 * two different questions -- {@code Economy.isAvailable()} answers the
 * second, per call, because a provider can register late.</p>
 */
public final class StandardsEconomy {

    public static void register() {
        Money.install((player, amount, reason) -> {
            if (!Economy.isAvailable()) return Optional.empty();
            var result = Economy.deposit(player.getUUID(), amount, reason);
            return result.success() ? Optional.of(Economy.format(amount)) : Optional.empty();
        });
        Chronicler.LOGGER.info("Chronicler: money rewards via Standards economy");
    }

    private StandardsEconomy() {}
}
