package com.sablednah.chronicler.api;

import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;

/**
 * Posted on the NeoForge game bus once a player has started a replayable chapter over ({@code /quest
 * replay}): the journal has forgotten the chapter's completions, cooldowns and player flags, and the
 * chapter's own {@code on_replay} effects have run. For a pack mod that keeps state of its own about the
 * chapter (Threadwork hangs Kit's scarf on the camp gate when the cure is found, and takes it down here).
 */
public final class ChapterReplayedEvent extends Event {

    private final ServerPlayer player;
    private final Identifier chapter;

    public ChapterReplayedEvent(ServerPlayer player, Identifier chapter) {
        this.player = player;
        this.chapter = chapter;
    }

    public ServerPlayer player() { return player; }

    public Identifier chapter() { return chapter; }
}
