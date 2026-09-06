package com.sablednah.chronicler.neoforge;

import java.util.List;
import java.util.function.Function;

import net.minecraft.server.level.ServerPlayer;

/**
 * Who is in a party with whom -- the neutral bridge.
 *
 * <p>Answers "just you" on a server that has never heard of Standards, so
 * every party-scoped quest degrades to a solo one. {@code compat/StandardsGroups}
 * fills the slot when Standards is present. Nothing here imports another
 * mod; nothing outside {@code compat/} may.</p>
 */
public final class Party {

    private static volatile Function<ServerPlayer, List<ServerPlayer>> provider = List::of;
    private static volatile String providerName = "none";

    /** Online members of the player's party, including the player. Never empty. */
    public static List<ServerPlayer> members(ServerPlayer player) {
        List<ServerPlayer> out;
        try {
            out = provider.apply(player);
        } catch (RuntimeException e) {
            out = List.of(player);
        }
        return out == null || out.isEmpty() ? List.of(player) : out;
    }

    /** Is there anything answering, beyond "just you"? For messages, not decisions. */
    public static String providerName() {
        return providerName;
    }

    public static void install(String name, Function<ServerPlayer, List<ServerPlayer>> members) {
        provider = members;
        providerName = name;
    }

    /** Back to "just you" -- used by the self-test to leave no fixture behind. */
    public static void reset() {
        provider = List::of;
        providerName = "none";
    }

    private Party() {}
}
