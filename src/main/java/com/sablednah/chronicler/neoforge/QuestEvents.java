package com.sablednah.chronicler.neoforge;

import com.sablednah.chronicler.ChroniclerConfig;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
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
        var victim = event.getEntity();
        if (event.getSource().getEntity() instanceof ServerPlayer killer) {
            // A FakePlayer with a journal is the self-test driving the real path.
            QuestEngine.onKill(killer, victim);
        } else {
            notifyOwnerOfUnownedRemoval(victim);
        }
        Rewards.forget(victim);
    }

    /**
     * The other way a tracked mob can disappear without a {@code LivingDeathEvent} at all: ZombieMod's
     * mutation swaps a mob for a fresh one of a different genus (a Walker that drops low enough health
     * turning into a Runner is exactly this, not a kill) via {@code Entity#discard()} -- which removes
     * the entity silently, no death, no damage source, nothing {@code onDeath} above ever sees. The
     * replacement carries none of Chronicler's tags either (only persistent-data NBT crosses a
     * mutation, never vanilla scoreboard tags), so this is the only place a tagged own_kill target's
     * disappearance is ever noticed. {@code DISCARDED} only, never {@code KILLED} (that already went
     * through {@code onDeath} an instant earlier and must not be credited twice) nor an unload (the mob
     * is still there, just not loaded).
     */
    @SubscribeEvent
    static void onLeave(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity victim)) return;
        if (victim.getRemovalReason() != Entity.RemovalReason.DISCARDED) return;
        notifyOwnerOfUnownedRemoval(victim);
        Rewards.forget(victim);
    }

    /** Not the player's doing. If a quest spawned it for someone, that someone still gets an answer. */
    private static void notifyOwnerOfUnownedRemoval(LivingEntity victim) {
        for (String tag : victim.getTags()) {
            if (!tag.startsWith(Rewards.FOR_PREFIX)) continue;
            try {
                ServerPlayer owner = victim.level().getServer().getPlayerList().getPlayer(java.util.UUID.fromString(tag.substring(Rewards.FOR_PREFIX.length())));
                if (owner != null) QuestEngine.onUnownedDeath(owner, victim);
            } catch (IllegalArgumentException ignored) {}
            break;
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
            Waypoints.sync(player);
            Hud.sync(player);
            EscortBars.sync(player);
        }
        Minis.tick(event.getServer());
    }

    /** Remind a returning player what they were doing; hand a new one the book. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            Journal.giveToNewPlayer(player);
            Achievements.sweep(player); // what the journal says they earned while no advancement was there to take it
            QuestEngine.journal(player).tracked().ifPresent(id -> QuestEngine.showTracker(player, id));
        }
    }

    /**
     * A right-click on a giver block accepts (or says why not). Main hand only, or it fires twice.
     * HIGH, not NORMAL: a claims mod (Standards' Factions) cancels every non-member's block click at
     * NORMAL, and at equal priority registration order decided whether a giver inside a claim worked
     * at all. This cancels only a click that really was a giver or turn-in, so running first lets a
     * claim protect everything else and never shows a refusal for the one click it had no business in.
     */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.HIGH)
    static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getLevel().isClientSide() || event.getHand() != net.minecraft.world.InteractionHand.MAIN_HAND) return;
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        if (QuestEngine.onUseBlock(player, player.level(), event.getPos(), event.getItemStack())) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
            return;
        }
        if (Givers.onUseBlock(player, player.level(), event.getPos())) {
            // The block's own use is swallowed, a door's included -- by design (Sable: a door that will not open
            // fits a rusted-door quest). The wild must simply never put that quest on somebody's own door, which
            // is the block slot's job (it skips claimed land).
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    /** A giver block is furniture, not loot: it stays unless an admin sneaks through it. */
    @SubscribeEvent
    static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (!ChroniclerConfig.GIVER_PROTECT.get()) return;
        if (Givers.questAt(level, event.getPos()).isEmpty()) return;
        if (event.getPlayer() instanceof ServerPlayer p && p.isShiftKeyDown() && ChroniclerPermissions.isAdmin(p.createCommandSourceStack())) return;
        event.setCanceled(true);
        if (event.getPlayer() instanceof ServerPlayer p) Feedback.actionBar(p, Lang.get("msg.giver.protected"));
    }

    @SubscribeEvent
    static void onExplode(net.neoforged.neoforge.event.level.ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof net.minecraft.server.level.ServerLevel level)) return;
        if (!ChroniclerConfig.GIVER_PROTECT.get()) return;
        event.getAffectedBlocks().removeIf(pos -> Givers.questAt(level, pos).isPresent());
    }

    @SubscribeEvent
    static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        Markers.clear(event.getServer()); // needs the players still connected, to hide their marks
        Minis.clear();
    }

    /**
     * The server is gone. Everything this mod keeps in a static map is about THAT world, and the JVM
     * outlives it: a singleplayer client quits to the title and opens another world with the same
     * player UUID, the same quest ids and a game clock starting over -- so a stamp, a cooldown, a
     * settle clock or a cached store left behind here is wrong in the next world, not merely stale
     * (Threadwork paid for it: a "flags already set this session" set made world B's tutorial never
     * complete). Per-player {@code forget} on logout is not enough to lean on; this is the backstop.
     */
    @SubscribeEvent
    static void onServerStopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event) {
        clearSession();
    }

    /** Every in-memory record that belongs to one world. {@code ServerStartedEvent} rebuilds the indexes. */
    static void clearSession() {
        Givers.clearSession();
        Hud.clearSession();
        JournalPanel.clearSession();
        EscortBars.clearSession();
        Rewards.clearSession();
        QuestEngine.clearSession();
        Minis.clear();
        FlagStore.forget();
        com.sablednah.chronicler.data.QuestItem.CURRENT = null;
    }

    /** How much per-world in-memory state is held right now; the self-test's window onto {@link #clearSession}. */
    static int sessionStateCount() {
        return Givers.sessionStateCount() + Hud.sessionStateCount() + JournalPanel.sessionStateCount()
                + EscortBars.activeCount() + Rewards.spawnedCount() + QuestEngine.settlingCount() + QuestEngine.wordedCount()
                + (FlagStore.cached() == null ? 0 : 1) + (com.sablednah.chronicler.data.QuestItem.CURRENT == null ? 0 : 1);
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        Givers.forget(event.getEntity().getUUID());
        Markers.forget(event.getEntity().getUUID());
        JournalPanel.forget(event.getEntity().getUUID());
        Hud.forget(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer player) EscortBars.forget(player);
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
