package com.sablednah.chronicler.data;

import com.mojang.serialization.MapCodec;

/** One thing a quest hands over on completion. Data only; the engine grants it. */
public interface RewardSpec {

    MapCodec<? extends RewardSpec> codec();

    /** One line for chat and the journal, generated from the record's fields. */
    String describe();
}
