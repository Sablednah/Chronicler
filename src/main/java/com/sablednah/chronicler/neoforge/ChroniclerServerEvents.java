package com.sablednah.chronicler.neoforge;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.data.Chapter;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.OnDatapackSyncEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;

/** Server lifecycle: messages, the boot summary, the self-test hook. */
public final class ChroniclerServerEvents {

    @SubscribeEvent
    static void onAboutToStart(ServerAboutToStartEvent event) {
        Lang.load(); // generated on first run, merged thereafter
    }

    /**
     * The one permanent log line. It exists because an empty registry and a
     * working one look identical from the console, and "the server booted" has
     * shipped a broken registry before -- the dedicated server prints "Done"
     * with a RegistryDataLoader error above it while the client refuses to
     * open the world. Count off the registry, never off the docs.
     */
    @SubscribeEvent
    static void onStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        Registry<Chapter> chapters = server.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
        Registry<Quest> quests = server.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        String chapterNames = String.join(", ", chapters.keySet().stream()
                .map(id -> id.getPath()).sorted().toList());
        Givers.index(server);
        FlagStore flags = FlagStore.get(server); // primes the cache the spawn condition reads off-thread
        if (!flags.view().isEmpty()) {
            Chronicler.LOGGER.info("Chronicler: {} world flag(s) set: {}", flags.view().size(),
                    String.join(", ", flags.view().keySet()));
        }
        Chronicler.LOGGER.info("Chronicler: {} chapter(s) [{}], {} quest(s) loaded, {} with givers; {} op-placed giver(s)",
                chapters.size(), chapterNames, quests.size(), Givers.dataCount(), GiverStore.get(server).size());

        if (Boolean.getBoolean("chronicler.selftest")) {
            SelfTest.run(server);
        }
    }

    @SubscribeEvent
    static void onDatapackSync(OnDatapackSyncEvent event) {
        if (event.getPlayer() != null) return; // a join, not a /reload
        Lang.load(); // messages.yml IS reloadable -- text is not a frozen registry
        for (ServerPlayer player : event.getPlayerList().getPlayers()) {
            if (ChroniclerPermissions.isAdmin(player.createCommandSourceStack())) {
                Feedback.chat(player, Lang.get("cmd.reload_notice"));
            }
        }
    }

    private ChroniclerServerEvents() {}
}
