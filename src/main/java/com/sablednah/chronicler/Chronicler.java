package com.sablednah.chronicler;

import com.mojang.logging.LogUtils;
import com.sablednah.chronicler.neoforge.ChroniclerAttachments;
import com.sablednah.chronicler.neoforge.ChroniclerCommands;
import com.sablednah.chronicler.neoforge.ChroniclerPermissions;
import com.sablednah.chronicler.neoforge.ChroniclerServerEvents;
import com.sablednah.chronicler.yaml.YamlConfigPack;

import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;

/**
 * Chronicler — data-driven quests, chapters and storylines.
 *
 * <p>Server-authoritative: vanilla clients play the whole thing through chat,
 * the action bar, titles, books and vanilla-visible entities. Loader-light
 * logic lives under {@code core}; NeoForge glue under {@code neoforge}; wire
 * formats under {@code network}; content records under {@code data}.</p>
 *
 * <p>Every sibling mod is a <b>soft</b> dependency. Only the guarded classes
 * under {@code neoforge/compat} may import {@code com.sablednah.legendquest},
 * {@code com.sablednah.standards}, {@code com.sablednah.zombiemod} or
 * {@code me.daddychurchill.CityWorld}; everything else talks to a neutral
 * bridge that answers sensibly on a server that has never heard of them.</p>
 */
@Mod(Chronicler.MODID)
public class Chronicler {
    // Must match mod_id in gradle.properties and modId in neoforge.mods.toml.
    public static final String MODID = "chronicler";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Chronicler(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("Chronicler initialising");

        modContainer.registerConfig(ModConfig.Type.COMMON, ChroniclerConfig.SPEC);

        // Mod bus: registries, attachments, the YAML front door.
        modEventBus.addListener(ChroniclerRegistries::register);
        ChroniclerAttachments.register(modEventBus);
        modEventBus.addListener(this::onAddPackFinders);
        com.sablednah.chronicler.neoforge.QuestItemLoot.register(modEventBus);

        // Game bus: server lifecycle, commands, permissions.
        NeoForge.EVENT_BUS.register(ChroniclerServerEvents.class);
        NeoForge.EVENT_BUS.register(ChroniclerPermissions.class);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent event) ->
                ChroniclerCommands.register(event.getDispatcher()));

        // Soft integrations. The isLoaded check sits HERE, outside the guarded
        // classes, because naming a class is what loads it. Each is wrapped so
        // a sibling that is present but older than the API we compiled against
        // costs one seam, not the server -- LinkageError, not Exception, since
        // a missing class is an Error. (LegendQuest paid for this lesson.)
        //
        // Standards' economy and groups are consumed; the rest are designed in
        // docs/DESIGN.md and each lands with a real consumer on the far side.
        // Built-in trackers and granters register in static blocks; touch them
        // here so nothing asks before they exist.
        com.sablednah.chronicler.neoforge.Trackers.init();
        com.sablednah.chronicler.neoforge.Rewards.init();
        com.sablednah.chronicler.data.GiverTypes.init();
        NeoForge.EVENT_BUS.register(com.sablednah.chronicler.neoforge.QuestEvents.class);

        if (ModList.get().isLoaded("standards")) {
            optionalIntegration("economy", com.sablednah.chronicler.neoforge.compat.StandardsEconomy::register);
            optionalIntegration("groups", com.sablednah.chronicler.neoforge.compat.StandardsGroups::register);
            // api/reputation ships in Standards 1.5.0; on an older Standards this
            // is the seam LinkageError takes away, and nothing else.
            optionalIntegration("reputation", com.sablednah.chronicler.neoforge.compat.StandardsReputation::register);
        }
        if (ModList.get().isLoaded("legendquest")) {
            optionalIntegration("character", com.sablednah.chronicler.neoforge.compat.LegendQuestCharacter::register);
        }
        if (ModList.get().isLoaded("zombiemod")) {
            // What we OFFER: a spawn condition on a world flag, into their public registry.
            optionalIntegration("spawn conditions", com.sablednah.chronicler.neoforge.compat.ZombieModConditions::register);
        }
        if (ModList.get().isLoaded("cast")) {
            optionalIntegration("npc givers", com.sablednah.chronicler.neoforge.compat.CastGivers::register);
        }
        if (ModList.get().isLoaded("cityworld")) {
            optionalIntegration("lots", com.sablednah.chronicler.neoforge.compat.CityWorldLots::register);
        }
    }

    /** Wire one optional sibling feature, surviving a sibling too old to have it. */
    public static void optionalIntegration(String feature, Runnable register) {
        try {
            register.run();
        } catch (LinkageError e) {
            LOGGER.warn("A sibling mod is installed but has no {} API this build can use"
                    + " -- that feature is off. Update it to re-enable. ({})", feature, e.toString());
        }
    }

    private void onAddPackFinders(AddPackFindersEvent event) {
        if (event.getPackType() == PackType.SERVER_DATA) {
            event.addRepositorySource(consumer -> consumer.accept(YamlConfigPack.makePack()));
            // The ZARP questline rides in the jar as a datapack: on when ZombieMod is here (or told to be),
            // otherwise listed and off, so /datapack enable can still turn it on by hand.
            boolean on;
            try {
                on = switch (ChroniclerConfig.ZARP.get()) {
                    case ON -> true;
                    case OFF -> false;
                    case AUTO -> net.neoforged.fml.ModList.get().isLoaded("zombiemod");
                };
            } catch (IllegalStateException e) {
                on = net.neoforged.fml.ModList.get().isLoaded("zombiemod"); // config not loaded yet: the auto rule
            }
            // Registered only when on: a world remembers an enabled pack by name, so merely marking it
            // optional would leave it running in any world that once had it. Absent, the world drops it.
            if (on) {
                event.addPackFinders(Identifier.fromNamespaceAndPath(MODID, "datapacks/zarp") /* the path is from the jar root, not data/ */,
                        PackType.SERVER_DATA, net.minecraft.network.chat.Component.literal("Chronicler: ZARP"),
                        net.minecraft.server.packs.repository.PackSource.BUILT_IN, true, net.minecraft.server.packs.repository.Pack.Position.TOP);
            }
            LOGGER.info("Chronicler: ZARP questline datapack is {}", on ? "on" : "off (content.zarp)");
        }
    }
}
