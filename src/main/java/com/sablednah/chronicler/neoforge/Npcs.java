package com.sablednah.chronicler.neoforge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * NPC givers -- the neutral bridge to Cast. Without Cast nothing here does
 * anything: an {@code npc} giver in a quest file is loaded, listed as "an NPC
 * (needs Cast)" and never placed, and the quest stays reachable from the
 * list and the journal. {@code compat/CastGivers} fills the slot.
 */
public final class Npcs {

    /** A placed NPC, as much as Chronicler needs to know. */
    public record Placed(UUID id, String name, Identifier dimension, Vec3 pos) {}

    public interface Provider {
        /** Place a human NPC carrying the giver role; returns its id. */
        UUID spawnHuman(ServerLevel level, Vec3 pos, float yaw, String name, Optional<String> skin);
        /** Place a creature NPC carrying the giver role. */
        UUID spawnMob(ServerLevel level, Vec3 pos, float yaw, Identifier entityType, String name);
        Optional<Placed> byId(MinecraftServer server, UUID id);
        /** The NPC the player is looking at, if any. */
        Optional<UUID> lookedAt(ServerPlayer player, double reach);
        /** Make sure the NPC carries the giver role (an op may have spawned it bare). */
        void ensureGiverRole(MinecraftServer server, UUID id);
        void say(MinecraftServer server, UUID id, String text, double radius);
        /** Dress the NPC: slot name -> item string as /give takes it. Says nothing about failures beyond the log. */
        default void equip(MinecraftServer server, UUID id, java.util.Map<String, String> equipment) {}
        /** What the NPC wears, by slot name. */
        default java.util.Map<String, String> equipment(MinecraftServer server, UUID id) { return java.util.Map.of(); }
        boolean remove(MinecraftServer server, UUID id);
    }

    private static volatile Provider provider = null;

    public static boolean available() { return provider != null; }

    public static Optional<Provider> provider() { return Optional.ofNullable(provider); }

    public static void install(Provider p) { provider = p; }

    private Npcs() {}
}
