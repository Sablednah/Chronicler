package com.sablednah.chronicler.neoforge.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Lots;

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
    }

    private static List<String> words(LotInfo lot) {
        List<String> out = new ArrayList<>();
        out.add(String.valueOf(lot.contextFamily()));
        out.add(lot.contextClass());
        out.add(String.valueOf(lot.lotStyle()));
        out.add(lot.lotClass());
        if (lot.interior() != null) out.add(lot.interior());
        if (lot.schematicName() != null) out.add(lot.schematicName());
        if (lot.shop() != null) out.add(lot.shop().describe());
        return out;
    }

    private CityWorldLots() {}
}
