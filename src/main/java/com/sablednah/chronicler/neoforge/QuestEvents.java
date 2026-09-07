package com.sablednah.chronicler.neoforge;

import com.sablednah.chronicler.ChroniclerConfig;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.minecraft.world.InteractionResult;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
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
        Givers.tickMarkers(event.getServer());
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            QuestEngine.poll(player);
            Givers.tick(player);
        }
    }

    /** Remind a returning player what they were doing; hand a new one the book. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Journal.giveToNewPlayer(player);
            QuestEngine.journal(player).tracked().ifPresent(id -> QuestEngine.showTracker(player, id));
        }
    }

    /** A right-click on a giver block accepts (or says why not). Main hand only, or it fires twice. */
    @SubscribeEvent
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (Givers.onUseBlock(player, player.level(), event.getPos())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        Markers.clear(event.getServer());
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Givers.forget(event.getEntity().getUUID());
        Markers.forget(event.getEntity().getUUID());
    }

    /** The client dropped every entity it had; the marks must be re-sent, so forget what it was sent. */
    @SubscribeEvent
    static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        Markers.forget(event.getEntity().getUUID());
    }

    /**
     * The journal item: fresh pages before vanilla opens it. Server side only,
     * and cancelled so vanilla's own open does not race ours -- the pages have
     * to be rewritten and synced to the client BEFORE the open packet, or the
     * player reads last week's progress.
     */
    @SubscribeEvent
    static void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (!Journal.is(event.getItemStack())) return;
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
        Journal.onUse(player, event.getItemStack(), event.getHand());
    }

    private QuestEvents() {}
}
