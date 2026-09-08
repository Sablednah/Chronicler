package com.sablednah.chronicler.neoforge.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.sablednah.cast.api.Cast;
import com.sablednah.cast.api.Npc;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.ChroniclerIds;
import com.sablednah.chronicler.neoforge.Givers;
import com.sablednah.chronicler.neoforge.Npcs;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * Quest givers that are people. The ONLY class that may import
 * {@code com.sablednah.cast}. Registers the {@code chronicler:giver} role:
 * right-clicking an NPC that carries it runs the same accept/offer logic as a
 * giver block, keyed by the NPC's id in the GiverStore.
 */
public final class CastGivers {

    public static final Identifier ROLE = ChroniclerIds.of("giver");

    public static void register() {
        Cast.registerRole(ROLE, (player, npc, hand) -> Givers.onUseNpc(player, npc.id()));
        Npcs.install(new Npcs.Provider() {
            @Override public UUID spawnHuman(ServerLevel level, Vec3 pos, float yaw, String name, Optional<String> skin) {
                return Cast.spawnHuman(level, pos, yaw, name, skin, List.of(ROLE));
            }
            @Override public UUID spawnMob(ServerLevel level, Vec3 pos, float yaw, Identifier entityType, String name) {
                return Cast.spawnMob(level, pos, yaw, entityType, name, List.of(ROLE));
            }
            @Override public Optional<Npcs.Placed> byId(MinecraftServer server, UUID id) {
                return Cast.byId(server, id).map(n -> new Npcs.Placed(n.id(), n.name(), n.dimension(), n.pos()));
            }
            @Override public Optional<UUID> lookedAt(ServerPlayer player, double reach) {
                return Cast.npcAt(player, reach).map(Npc::id);
            }
            @Override public void ensureGiverRole(MinecraftServer server, UUID id) {
                Cast.byId(server, id).ifPresent(n -> {
                    if (!n.roles().contains(ROLE)) {
                        List<Identifier> roles = new ArrayList<>(n.roles());
                        roles.add(ROLE);
                        Cast.setRoles(server, id, roles);
                    }
                });
            }
            @Override public void equip(MinecraftServer server, UUID id, java.util.Map<String, String> equipment) {
                equipment.forEach((slot, item) -> Cast.equip(server, id, slot, item));
            }
            @Override public java.util.Map<String, String> equipment(MinecraftServer server, UUID id) {
                return Cast.equipment(server, id);
            }
            @Override public void say(MinecraftServer server, UUID id, String text, double radius) {
                Cast.say(server, id, text, radius);
            }
            @Override public boolean remove(MinecraftServer server, UUID id) {
                return Cast.remove(server, id);
            }
        });
        Chronicler.LOGGER.info("Chronicler: NPC givers via Cast (role chronicler:giver)");
    }

    private CastGivers() {}
}
