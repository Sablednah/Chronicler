package com.sablednah.chronicler.neoforge.compat;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Lots;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import me.daddychurchill.CityWorld.api.CityWorldAPI;
import me.daddychurchill.CityWorld.api.LotInfo;

/**
 * Lot words from CityWorld's plan. The ONLY class that may import
 * {@code me.daddychurchill.CityWorld}. {@code lotAt} is seed-deterministic and
 * answers for chunks that were never generated, so a quest can name "a
 * hospital" in a city nobody has walked to yet.
 */
public final class CityWorldLots {

    public static void register() {
        Lots.install((level, pos) -> CityWorldAPI.lotAt(level, pos).map(CityWorldLots::words));
        Chronicler.LOGGER.info("Chronicler: lot places via CityWorld");
        nonBlocking();
    }

    /**
     * CityWorld 6.12.0's {@code lotAtIfPlanned} / {@code lotAtAsync}, by method handle: an older CityWorld (every
     * 26.x line compiles against one) has neither, and the blocking {@code lotAt} -- budgeted -- is all there is.
     */
    private static void nonBlocking() {
        MethodHandle ifPlanned, async;
        try {
            var lookup = MethodHandles.publicLookup();
            ifPlanned = lookup.findStatic(CityWorldAPI.class, "lotAtIfPlanned",
                    MethodType.methodType(Optional.class, ServerLevel.class, BlockPos.class));
            async = lookup.findStatic(CityWorldAPI.class, "lotAtAsync",
                    MethodType.methodType(CompletableFuture.class, ServerLevel.class, BlockPos.class));
        } catch (ReflectiveOperationException e) {
            Chronicler.LOGGER.info("Chronicler: this CityWorld plans lots on the server thread (6.12.0 does not); wild searches are budgeted");
            return;
        }
        Lots.installAsker((level, pos, mayPlan) -> {
            try {
                // A planned platmap always has a lot, so empty here means "not planned" (or not a CityWorld level).
                Optional<?> now = (Optional<?>) ifPlanned.invoke(level, pos);
                if (now.isPresent()) return CompletableFuture.completedFuture(Optional.of(words((LotInfo) now.get())));
                if (!mayPlan) return null;
                // Done at once off a non-CityWorld level, so "no city" is never mistaken for "not known yet".
                CompletableFuture<?> later = (CompletableFuture<?>) async.invoke(level, pos);
                return later.thenApply(o -> ((Optional<?>) o).map(lot -> words((LotInfo) lot)));
            } catch (RuntimeException | Error e) {
                throw e;
            } catch (Throwable t) {
                throw new IllegalStateException(t);
            }
        });
        Chronicler.LOGGER.info("Chronicler: CityWorld lots asked off the server thread (lotAtAsync)");
    }

    private static List<String> words(LotInfo lot) {
        List<String> out = new ArrayList<>();
        out.add(String.valueOf(lot.contextFamily()));
        out.add(lot.contextClass());
        out.add(String.valueOf(lot.lotStyle()));
        out.add(lot.lotClass());
        if (lot.interior() != null) out.add(lot.interior());
        if (lot.schematicName() != null) {
            out.add(lot.schematicName());
            out.add(com.sablednah.chronicler.neoforge.Places.SCHEMATIC + lot.schematicName());
        }
        if (lot.shop() != null) out.add(lot.shop().describe());
        return out;
    }

    private CityWorldLots() {}
}
