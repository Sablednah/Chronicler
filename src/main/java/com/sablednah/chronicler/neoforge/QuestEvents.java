package com.sablednah.chronicler.neoforge;

import com.sablednah.chronicler.ChroniclerConfig;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Game-bus hooks that feed the engine. Thin: the rules live in {@link QuestEngine}. */
public final class QuestEvents {

    private static int tickCounter;

    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer killer && !killer.isFakePlayer()) {
            QuestEngine.onKill(killer, event.getEntity());
        } else if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            // A FakePlayer with a journal is the self-test driving the real path.
            QuestEngine.onKill(killer, event.getEntity());
        }
    }

    /** Polled objectives (inventory, position) on the configured interval, not every tick. */
    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (++tickCounter < ChroniclerConfig.TRACKER_INTERVAL_TICKS.get()) return;
        tickCounter = 0;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            QuestEngine.poll(player);
        }
    }

    /** Remind a returning player what they were doing. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            QuestEngine.journal(player).tracked().ifPresent(id -> QuestEngine.showTracker(player, id));
        }
    }

    private QuestEvents() {}
}
