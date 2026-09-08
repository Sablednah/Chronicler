package com.sablednah.chronicler.neoforge.compat;

import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Genera;
import com.sablednah.zombiemod.ZombieModRegistries;
import com.sablednah.zombiemod.core.Genus;
import com.sablednah.zombiemod.neoforge.GenusApplier;

import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.Mob;

/**
 * Spawns a genus the way ZombieMod's own command does -- base mob, assign
 * before adding (their join handler builds the AI from the genus tag), add --
 * and hands the mob back so Chronicler can decorate it. ZombieMod dresses the
 * mob first (a genus head sits in the head slot); Chronicler only fills what
 * is left empty unless the effect says {@code override}.
 */
public final class ZombieModSpawns {

    public static void register() {
        Genera.install((level, genusId, at) -> {
            Optional<Holder.Reference<Genus>> holder = level.registryAccess().lookupOrThrow(ZombieModRegistries.GENUS)
                    .get(ResourceKey.create(ZombieModRegistries.GENUS, genusId));
            if (holder.isEmpty()) {
                Chronicler.LOGGER.warn("Chronicler: no such genus {}", genusId);
                return Optional.empty();
            }
            Entity created = holder.get().value().base().create(level, EntitySpawnReason.EVENT);
            if (!(created instanceof Mob mob)) return Optional.empty();
            mob.snapTo(at.x, at.y, at.z, level.getRandom().nextFloat() * 360F, 0F);
            GenusApplier.assign(mob, holder.get());
            level.addFreshEntity(mob);
            return Optional.of(mob);
        });
        Chronicler.LOGGER.info("Chronicler: genus spawns via ZombieMod");
    }

    private ZombieModSpawns() {}
}
