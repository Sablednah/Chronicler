package com.sablednah.chronicler.neoforge.compat;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.ChroniclerIds;
import com.sablednah.chronicler.neoforge.FlagStore;
import com.sablednah.zombiemod.core.spawn.SpawnCondition;
import com.sablednah.zombiemod.core.spawn.SpawnConditionTypes;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;

/**
 * What Chronicler OFFERS ZombieMod: a spawn condition on a world flag, so a
 * genus file can say {@code {"type": "chronicler:flag", "flag":
 * "hospital_cleared", "value": false}} and stop spawning once the questline
 * that sets the flag is done. The city gets safer because you made it safer.
 *
 * <p>The ONLY class that may import {@code com.sablednah.zombiemod}. Reads
 * the flag cache, never the data storage -- spawn conditions run on worldgen
 * threads.</p>
 */
public final class ZombieModConditions {

    public record FlagCondition(String flag, boolean value) implements SpawnCondition {
        public static final Identifier TYPE = ChroniclerIds.of("flag");
        public static final MapCodec<FlagCondition> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Codec.STRING.fieldOf("flag").forGetter(FlagCondition::flag),
                Codec.BOOL.optionalFieldOf("value", true).forGetter(FlagCondition::value))
                .apply(i, FlagCondition::new));

        @Override public Identifier type() { return TYPE; }

        @Override public boolean test(Level level, BlockPos pos) {
            FlagStore store = FlagStore.cached();
            boolean set = store != null && store.is(flag);
            return set == value;
        }
    }

    public static void register() {
        SpawnConditionTypes.register(FlagCondition.TYPE, FlagCondition.CODEC);
        Chronicler.LOGGER.info("Chronicler: spawn condition chronicler:flag offered to ZombieMod");
    }

    private ZombieModConditions() {}
}
