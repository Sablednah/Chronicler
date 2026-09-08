package com.sablednah.chronicler.neoforge;

import java.util.Optional;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

/**
 * Spawning a ZombieMod genus and getting the mob back -- the neutral bridge.
 * Without ZombieMod (or with one too old to link) it spawns nothing and says
 * so, and the {@code spawn} effect falls back to its vanilla stand-in.
 *
 * <p>Why a seam and not the {@code /zombiemod spawn} command: a command hands
 * nothing back, and an entity added this tick is invisible to every lookup
 * until the next, so a name, a tag or a helmet meant for the genus mob never
 * found it. Fable paid for that with a mini-boss nobody could tag.</p>
 */
public final class Genera {

    public interface Provider {
        Optional<Mob> spawn(ServerLevel level, Identifier genus, Vec3 at);
    }

    private static volatile Provider provider = null;

    public static boolean available() { return provider != null; }

    public static Optional<Mob> spawn(ServerLevel level, Identifier genus, Vec3 at) {
        Provider p = provider;
        if (p == null) return Optional.empty();
        try {
            return p.spawn(level, genus, at);
        } catch (RuntimeException e) {
            com.sablednah.chronicler.Chronicler.LOGGER.warn("Chronicler: spawning genus {} threw: {}", genus, e.toString());
            return Optional.empty();
        }
    }

    public static void install(Provider p) { provider = p; }

    private Genera() {}
}
