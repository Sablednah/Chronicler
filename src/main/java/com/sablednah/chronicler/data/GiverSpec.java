package com.sablednah.chronicler.data;

import com.mojang.serialization.MapCodec;

/**
 * Who, or where, offers a quest. Pure data: the record says where the offer
 * lives; {@code neoforge/Givers} decides when a player is close enough to hear
 * it. A modular registry ({@link GiverTypes}) so other mods add kinds --
 * StoryTeller's NPCs are the first external one.
 */
public interface GiverSpec {

    MapCodec<? extends GiverSpec> codec();

    /** Where to find it, for the offer line and the journal: "the lectern at the vault door". */
    String describe();
}
