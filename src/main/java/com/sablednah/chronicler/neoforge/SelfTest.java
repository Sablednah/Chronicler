package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.ChroniclerIds;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;

/**
 * Headless checks, run on {@code ServerStartedEvent} when
 * {@code ./gradlew runServer -Pselftest}. Gradle cannot pipe stdin to a dev
 * server console, so this is the only headless route to "does it work".
 * Rules, all learned next door:
 *
 * <ul>
 * <li><b>Parse AND execute.</b> {@code parse} alone proves nothing.</li>
 * <li><b>Both directions.</b> A tree that matches anything passes every
 *     positive assertion, so a deliberately bad command must fail; a cow
 *     must not count as a zombie.</li>
 * <li><b>Call the real code.</b> The engine is driven through
 *     {@code QuestEngine} with a {@code FakePlayer} -- a real
 *     {@code ServerPlayer} with a journal -- never a re-derivation.</li>
 * <li><b>Print the failures, not a count.</b></li>
 * </ul>
 *
 * <p>What this cannot see: anything a client draws (the action bar, the title
 * card, whether a button is clickable) and anything a second real player does.
 * Those need the dev client and, for packets, a genuinely vanilla one.</p>
 */
public final class SelfTest {

    private static final List<String> FAILURES = new ArrayList<>();
    private static int passed;

    public static void run(MinecraftServer server) {
        FAILURES.clear();
        passed = 0;
        Chronicler.LOGGER.info("=== Chronicler SelfTest ===");

        var chapters = server.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
        var quests = server.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        Identifier firstSteps = ChroniclerIds.of("first_steps");
        Identifier things = ChroniclerIds.of("things_in_the_dark");
        check("built-in chapter loaded", chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, ChroniclerIds.of("prologue"))).isPresent());
        check("built-in quests loaded", quests.size() >= 2);
        check("every quest names a real chapter", quests.listElements().allMatch(h -> chapters.get(
                ResourceKey.create(ChroniclerRegistries.CHAPTER, h.value().chapter())).isPresent()));
        check("every requires names a real quest", quests.listElements().allMatch(h -> h.value().requires().stream()
                .allMatch(r -> quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, r)).isPresent())));
        check("objectives describe themselves", quests.listElements().allMatch(h ->
                h.value().objectives().stream().allMatch(o -> !o.describe().isBlank() && !o.describe().startsWith("obj."))));

        check("lang catalogue is non-trivial", Lang.catalogueSize() > 60);
        check("lang resolves terms", Lang.get("cmd.list.header").contains(Lang.term("quests")));
        check("unknown lang key returns the key", Lang.get("no.such.key").equals("no.such.key"));
        check("pretty() title-cases", Lang.pretty("minecraft:rotten_flesh").equals("Rotten Flesh"));
        check("colored() styles without section signs",
                !Feedback.colored("&6Hi &fthere").getString().contains("§")
                        && Feedback.colored("Tom & Jerry").getString().equals("Tom & Jerry"));

        QuestLog log = new QuestLog();
        log.start(ChroniclerIds.of("x"), List.of(2, 1));
        log.entry(ChroniclerIds.of("x")).progress.set(0, 3);
        check("journal counters are live", log.entry(ChroniclerIds.of("x")).progress.get(0) == 3);
        check("journal not done with one objective short", !log.entry(ChroniclerIds.of("x")).done());
        log.complete(ChroniclerIds.of("x"));
        check("journal completion counts", log.isComplete(ChroniclerIds.of("x")) && !log.isActive(ChroniclerIds.of("x")));

        // --- the engine, end to end, through the real code ---
        FakePlayer solo = fake(server, "ChroniclerTestA");
        try {
            QuestEngine.journal(solo).clear();
            check("things_in_the_dark is locked before first_steps",
                    QuestEngine.refusal(solo, things, quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, things)).get().value())
                            .map(r -> r == QuestEngine.Refusal.LOCKED).orElse(false));
            check("accept first_steps", QuestEngine.accept(solo, firstSteps).isEmpty());
            check("first_steps is active", QuestEngine.journal(solo).isActive(firstSteps));
            check("accepting twice is refused as active",
                    QuestEngine.accept(solo, firstSteps).map(r -> r == QuestEngine.Refusal.ALREADY_ACTIVE).orElse(false));
            QuestEngine.poll(solo);
            check("no logs, no progress", QuestEngine.journal(solo).entry(firstSteps).progress.get(0) == 0);
            solo.getInventory().add(new ItemStack(Items.OAK_LOG, 3));
            QuestEngine.poll(solo);
            check("three logs count three", QuestEngine.journal(solo).entry(firstSteps).progress.get(0) == 3);
            solo.getInventory().add(new ItemStack(Items.OAK_LOG, 1));
            QuestEngine.poll(solo);
            check("four logs completes first_steps", QuestEngine.journal(solo).isComplete(firstSteps)
                    && !QuestEngine.journal(solo).isActive(firstSteps));
            check("consume:false left the logs", Trackers.count(solo, Identifier.parse("minecraft:oak_log")) == 4);
            check("bread reward landed", Trackers.count(solo, Identifier.parse("minecraft:bread")) == 3);
            check("xp reward landed", solo.totalExperience >= 20);
            // Reputation: only provable with a store on the far side of the seam.
            if (Rep.available()) {
                int before = Rep.get(solo, "selftest_standing");
                var landed = Rep.adjust(solo, "selftest_standing", 7, "chronicler:selftest");
                check("reputation adjust lands (+7)", landed.isPresent() && landed.getAsInt() == before + 7);
                check("reputation reads back", Rep.get(solo, "selftest_standing") == before + 7);
                Rep.adjust(solo, "selftest_standing", -7, "chronicler:selftest");
                check("reputation restored", Rep.get(solo, "selftest_standing") == before);
            } else {
                Chronicler.LOGGER.info("SelfTest: no reputation store on this server -- seam untested, not failed");
                check("reputation reward without a store is a clean no-op", Rep.adjust(solo, "x", 1, "t").isEmpty());
            }

            check("done twice is refused as complete",
                    QuestEngine.accept(solo, firstSteps).map(r -> r == QuestEngine.Refusal.ALREADY_COMPLETE).orElse(false));

            // Kills: a zombie counts, a cow does not, and the fifth completes it.
            check("things_in_the_dark now accepts", QuestEngine.accept(solo, things).isEmpty());
            // The journal, while a quest is active and one is done: pages, links, the marker.
            check("journal is not just any written book", !Journal.is(new ItemStack(Items.WRITTEN_BOOK)));
            ItemStack journal = Journal.make(solo);
            check("journal item carries the marker", Journal.is(journal));
            var pages = Journal.pages(solo);
            check("journal has contents + quest + offer + done pages", pages.size() >= 4);
            String contents = pages.get(0).raw().getString();
            check("journal contents name the active quest", contents.contains("Things in the Dark"));
            check("journal pages carry no section signs", pages.stream().noneMatch(pg -> pg.raw().getString().contains("§")));
            check("journal quest page shows progress", pages.get(1).raw().getString().contains("/"));
            Journal.give(solo);
            check("journal given is remembered", QuestEngine.journal(solo).journalGiven());
            check("journal item in the pack", count(solo) == 1);
            Journal.giveToNewPlayer(solo);
            check("new-player give does not double up", count(solo) == 1);

            Zombie zombie = new Zombie(server.overworld());
            Cow cow = new Cow(net.minecraft.world.entity.EntityTypes.COW, server.overworld());
            QuestEngine.onKill(solo, cow);
            check("a cow is not a zombie", QuestEngine.journal(solo).entry(things).progress.get(0) == 0);
            check("genus matcher: plain zombie is not a harvester", !Trackers.matchesTarget(zombie, "zombiemod:harvester"));
            zombie.getPersistentData().putString("zombiemod:genus", "zombiemod:harvester");
            check("genus matcher: tagged zombie is a harvester", Trackers.matchesTarget(zombie, "zombiemod:harvester"));
            check("tag matcher: zombie is #minecraft:zombies", Trackers.matchesTarget(zombie, "#minecraft:zombies"));
            for (int i = 0; i < 4; i++) QuestEngine.onKill(solo, zombie);
            check("four kills is four", QuestEngine.journal(solo).entry(things).progress.get(0) == 4);
            QuestEngine.onKill(solo, zombie);
            check("fifth kill completes things_in_the_dark", QuestEngine.journal(solo).isComplete(things));
            check("iron sword reward landed", Trackers.count(solo, Identifier.parse("minecraft:iron_sword")) == 1);

            // Places: the world names them, we never spell coordinates.
            Identifier overworld = net.minecraft.world.level.Level.OVERWORLD.identifier();
            check("place: empty place is anywhere", Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty())));
            check("place: overworld dimension matches", Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(overworld), java.util.Optional.empty())));
            check("place: nether dimension does not", !Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.of(net.minecraft.world.level.Level.NETHER.identifier()), java.util.Optional.empty())));
            check("place: the overworld biome tag matches here", Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.of("#minecraft:is_overworld"), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty())));
            check("place: a lot needs CityWorld", Lots.describe(server.overworld(), solo.blockPosition()).isEmpty()
                    || Places.isAt(solo, new com.sablednah.chronicler.data.Place(java.util.Optional.empty(),
                            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("zzz-no-such-lot"))) == false);
            check("hot_foot ships hidden with a place giver", quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, ChroniclerIds.of("hot_foot")))
                    .map(h -> h.value().hidden() && h.value().giver().isPresent()).orElse(false));

            // Givers: an op-placed block offers, right-click accepts, the store round-trips.
            QuestEngine.journal(solo).clear();
            GiverStore store = GiverStore.get(server);
            net.minecraft.core.BlockPos here = solo.blockPosition();
            store.set(server.overworld(), here, firstSteps);
            check("giver store answers", Givers.questAt(server.overworld(), here).map(firstSteps::equals).orElse(false));
            Markers.EXTRA_VIEWERS.add(solo);
            Givers.tickMarkers(server);
            String giverKey = GiverStore.key(server.overworld(), here);
            check("a mark is sent to the player near the op-placed giver", Markers.shownTo(solo.getUUID()) == 1);
            check("the mark says 'available' before accepting", Markers.textShownTo(solo.getUUID(), giverKey)
                    .equals(com.sablednah.chronicler.ChroniclerConfig.GIVER_MARKER_TEXT.get()));
            // The four oak logs are still in the pack, so accepting completes it on the spot.
            check("giver block: first click offers, does not accept", Givers.onUseBlock(solo, server.overworld(), here)
                    && !QuestEngine.journal(solo).isActive(firstSteps) && !QuestEngine.journal(solo).isComplete(firstSteps));
            check("giver block: second click accepts (and the logs finish it)", Givers.onUseBlock(solo, server.overworld(), here)
                    && (QuestEngine.journal(solo).isActive(firstSteps) || QuestEngine.journal(solo).isComplete(firstSteps)));
            Givers.tickMarkers(server);
            check("the mark changes with the player's state", Markers.textShownTo(solo.getUUID(), giverKey)
                    .equals(QuestEngine.journal(solo).isComplete(firstSteps)
                            ? com.sablednah.chronicler.ChroniclerConfig.GIVER_MARKER_COMPLETE.get()
                            : com.sablednah.chronicler.ChroniclerConfig.GIVER_MARKER_ACTIVE.get()));
            check("giver block: again while active is not an error", Givers.onUseBlock(solo, server.overworld(), here));
            check("giver block: a plain block is not a giver", !Givers.onUseBlock(solo, server.overworld(), here.above(40)));
            check("giver store removes", store.remove(server.overworld(), here) && Givers.questAt(server.overworld(), here).isEmpty());
            Givers.tickMarkers(server);
            check("the mark goes with the giver", Markers.shownTo(solo.getUUID()) == 0);
            Markers.EXTRA_VIEWERS.remove(solo);
            QuestEngine.journal(solo).clear();
            QuestEngine.journal(solo).complete(firstSteps);
            QuestEngine.journal(solo).complete(things);

            // Stages: Night Watch is two beats; the first a torch, the second two kills.
            Identifier night = ChroniclerIds.of("night_watch");
            check("night_watch has three beats", quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, night))
                    .map(h -> h.value().beats().size() == 3).orElse(false));
            check("stages: accept", QuestEngine.accept(solo, night).isEmpty());
            check("stages: starts on beat 1", QuestEngine.journal(solo).entry(night).stage == 0);
            solo.getInventory().add(new ItemStack(Items.TORCH, 1));
            QuestEngine.poll(solo);
            QuestLog.Entry nw = QuestEngine.journal(solo).entry(night);
            check("stages: the torch moves it to beat 2", nw != null && nw.stage == 1);
            check("stages: beat 2 has fresh counters against 2", nw != null && nw.targets.equals(List.of(2)) && nw.progress.equals(List.of(0)));
            // The on_enter spawn is not asserted: an entity added this tick is not in the
            // index until the next one, and ServerStartedEvent is a single tick. A wrong
            // entity id logs a warning, which the boot check reads.
            QuestEngine.onKill(solo, zombie);
            check("stages: a kill counts on beat 2 only", QuestEngine.journal(solo).entry(night).progress.get(0) == 1);
            QuestEngine.onKill(solo, zombie);
            QuestLog.Entry decide = QuestEngine.journal(solo).entry(night);
            check("choices: finishing beat 2 lands on the decision, not the end", decide != null && decide.stage == 2
                    && !QuestEngine.journal(solo).isComplete(night));
            check("choices: an option out of range is refused", !QuestEngine.choose(solo, night, 3));
            check("choices: option 1 ends the quest", QuestEngine.choose(solo, night, 1) && QuestEngine.journal(solo).isComplete(night));
            check("choices: the effects fired (player flag)", QuestEngine.journal(solo).hasFlag("gave_the_torch"));
            check("stages: shield reward landed", Trackers.count(solo, Identifier.parse("minecraft:shield")) == 1);
            check("choices: nothing to choose once done", !QuestEngine.choose(solo, night, 1));

            // Flags and availability.
            FlagStore flags = FlagStore.get(server);
            flags.set("SelfTest_Flag", true);
            check("world flag set and normalised", flags.is("selftest_flag") && FlagStore.cached() == flags);
            var needsFlag = new com.sablednah.chronicler.data.Availability(java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Map.of("other_flag", true), java.util.Map.of(), java.util.Map.of());
            check("availability: an unset flag is unmet", !Conditions.unmet(solo, needsFlag).isEmpty());
            flags.set("other_flag", true);
            check("availability: the flag set is met", Conditions.unmet(solo, needsFlag).isEmpty());
            flags.set("other_flag", false);
            flags.set("selftest_flag", false);
            check("world flags clear", flags.view().isEmpty() || !flags.is("selftest_flag"));
            QuestEngine.journal(solo).setFlag("Chose_Mercy", true);
            check("player flag set and normalised", QuestEngine.journal(solo).hasFlag("chose_mercy"));
            var needsKarma = new com.sablednah.chronicler.data.Availability(java.util.Optional.of(20L), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of());
            if (Sheet.available()) {
                long before = Sheet.karma(solo).orElse(0L);
                check("character: karma reward lands", Sheet.addKarma(solo, 5) && Sheet.karma(solo).orElse(0L) == before + 5);
                Sheet.addKarma(solo, -5);
                check("character: level answers", Sheet.level(solo).isPresent());
                Chronicler.LOGGER.info("SelfTest: character sheets via {}", Sheet.providerName());
            } else {
                check("availability: karma needs a character system", Conditions.unmet(solo, needsKarma).size() == 1);
                check("character rewards without a sheet are clean no-ops", !Sheet.addKarma(solo, 5));
            }

            // Repeatable bounties on a cooldown: Cull can be done again, but not at once.
            Identifier cull = ChroniclerIds.of("cull");
            var cullQ = quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, cull)).map(h -> h.value());
            check("cull ships repeatable with a cooldown", cullQ.map(q -> q.repeatable() && q.cooldown() > 0).orElse(false));
            check("cooldown: accept the first time", QuestEngine.accept(solo, cull).isEmpty());
            for (int i = 0; i < 10; i++) QuestEngine.onKill(solo, zombie);
            check("cooldown: ten kills complete it", QuestEngine.journal(solo).isComplete(cull));
            check("cooldown: refused straight after", QuestEngine.accept(solo, cull).map(r -> r == QuestEngine.Refusal.COOLDOWN).orElse(false));
            QuestEngine.journal(solo).setCompletedAt(cull, 1L);
            check("cooldown: available again once it has passed", QuestEngine.accept(solo, cull).isEmpty()
                    && QuestEngine.journal(solo).completions(cull) == 1);
            QuestEngine.abandon(solo, cull);

            // NPC givers, when Cast is present: a person hands out a quest on right-click.
            if (Npcs.available()) {
                var cast = Npcs.provider().get();
                QuestEngine.journal(solo).clear();
                UUID npc = cast.spawnHuman(server.overworld(), solo.position().add(2, 0, 0), 0F, "Test Giver", java.util.Optional.empty());
                try {
                    GiverStore.get(server).setNpc(npc, firstSteps);
                    check("npc giver: first click offers", Givers.onUseNpc(solo, npc) && !QuestEngine.journal(solo).isActive(firstSteps));
                    check("npc giver: second click accepts (logs still in the pack finish it)", Givers.onUseNpc(solo, npc)
                            && (QuestEngine.journal(solo).isActive(firstSteps) || QuestEngine.journal(solo).isComplete(firstSteps)));
                    GiverStore.get(server).removeNpc(npc);
                    check("npc giver: an NPC with no quest is idle, not an error", Givers.onUseNpc(solo, npc));
                } finally {
                    cast.remove(server, npc);
                }
                QuestEngine.journal(solo).clear();
                QuestEngine.journal(solo).complete(firstSteps);
                QuestEngine.journal(solo).complete(things);
                Chronicler.LOGGER.info("SelfTest: NPC givers via Cast exercised");
            } else {
                check("npc giver kind loads without Cast (hot_foot has none; codec present)", com.sablednah.chronicler.data.GiverTypes.TYPES.size() >= 3);
            }

            // The API other mods call.
            check("api: hot_foot is available (hidden only hides the list)", com.sablednah.chronicler.api.Quests.isAvailable(solo, ChroniclerIds.of("hot_foot")));
            check("api: offer returns true for an available quest", com.sablednah.chronicler.api.Quests.offer(solo, ChroniclerIds.of("hot_foot"), "a test"));
            check("api: accept", com.sablednah.chronicler.api.Quests.accept(solo, ChroniclerIds.of("hot_foot")).isEmpty()
                    && com.sablednah.chronicler.api.Quests.isActive(solo, ChroniclerIds.of("hot_foot")));

            // Deadlines: a clock in the past fails the beat on the next poll; with no fall-back, the quest is dropped.
            QuestLog.Entry hf = QuestEngine.journal(solo).entry(ChroniclerIds.of("hot_foot"));
            hf.deadlineAt = Math.max(0L, server.overworld().getGameTime() - 1); // a fresh world is at tick 0, and -1 means no deadline
            check("deadline: clock shows 0:00 when out", QuestEngine.clock(hf.deadlineAt - server.overworld().getGameTime()).equals("0:00"));
            QuestEngine.poll(solo);
            check("deadline: running out abandons a quest with no fall-back", !QuestEngine.journal(solo).isActive(ChroniclerIds.of("hot_foot")));
            check("deadline: clock formats minutes", QuestEngine.clock(20L * 299).equals("4:59"));

            // Party pooling: two players, targets scaled, one quest between them.
            FakePlayer a = fake(server, "ChroniclerTestB");
            FakePlayer b = fake(server, "ChroniclerTestC");
            try {
                QuestEngine.journal(a).clear();
                QuestEngine.journal(b).clear();
                // Both have the prerequisite; the party is the two of them.
                QuestEngine.journal(a).complete(firstSteps);
                QuestEngine.journal(b).complete(firstSteps);
                Party.install("selftest", p -> List.of(a, b));
                check("party accept by A", QuestEngine.accept(a, things).isEmpty());
                check("party accept reached B", QuestEngine.journal(b).isActive(things));
                check("party target scaled to 10", QuestEngine.journal(a).entry(things).targets.get(0) == 10
                        && QuestEngine.journal(b).entry(things).targets.get(0) == 10);
                for (int i = 0; i < 6; i++) QuestEngine.onKill(a, zombie);
                check("A's six kills pooled to B", QuestEngine.journal(b).entry(things).progress.get(0) == 6);
                for (int i = 0; i < 3; i++) QuestEngine.onKill(b, zombie);
                check("nine of ten, nobody done", !QuestEngine.journal(a).isComplete(things));
                QuestEngine.onKill(b, zombie);
                check("tenth kill completes for both", QuestEngine.journal(a).isComplete(things)
                        && QuestEngine.journal(b).isComplete(things));
                check("both rewarded", Trackers.count(a, Identifier.parse("minecraft:iron_sword")) == 1
                        && Trackers.count(b, Identifier.parse("minecraft:iron_sword")) == 1);
            } finally {
                Party.reset();
                QuestEngine.journal(a).clear();
                QuestEngine.journal(b).clear();
                a.discard();
                b.discard();
            }
        } finally {
            QuestEngine.journal(solo).clear();
            solo.discard();
        }

        CommandSourceStack source = server.createCommandSourceStack();
        command(server, source, "quest list", true);
        command(server, source, "quests list", true);
        command(server, source, "quest info first_steps", true);
        command(server, source, "quest info chronicler:first_steps", true);
        command(server, source, "chronicler status", true);
        command(server, source, "quest info no_such_quest", false);
        command(server, source, "quest sideways", false);
        command(server, source, "quest accept first_steps", false); // console has no journal
        command(server, source, "quest journal", false);             // console has no hands
        command(server, source, "quest choose first_steps 1", false); // console has no journal
        command(server, source, "quest giver list", true);
        command(server, source, "chronicler flag list", true);
        command(server, source, "chronicler flag set selftest_cmd_flag false", true);
        command(server, source, "quest giver set first_steps", false); // console has no eyes

        Chronicler.LOGGER.info("=== Chronicler SelfTest: {} PASSED, {} FAILED ===", passed, FAILURES.size());
        for (String f : FAILURES) {
            Chronicler.LOGGER.error("  FAILED: {}", f);
        }
    }

    private static int count(net.minecraft.server.level.ServerPlayer player) {
        int n = 0;
        var inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) if (Journal.is(inv.getItem(i))) n++;
        return n;
    }

    /** Stood at the world spawn, in a chunk kept loaded: a FakePlayer defaults to 0,0,0 in an unloaded one. */
    private static FakePlayer fake(MinecraftServer server, String name) {
        FakePlayer p = new FakePlayer(server.overworld(),
                new GameProfile(UUID.nameUUIDFromBytes(("chronicler:" + name).getBytes()), name));
        var spawn = server.overworld().getRespawnData().globalPos().pos();
        server.overworld().setChunkForced(spawn.getX() >> 4, spawn.getZ() >> 4, true);
        p.snapTo(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5, 0F, 0F);
        return p;
    }

    /** Parse, verify the parse reached an executable node, then execute -- or prove it cannot. */
    private static void command(MinecraftServer server, CommandSourceStack source, String cmd, boolean expectOk) {
        var dispatcher = server.getCommands().getDispatcher();
        ParseResults<CommandSourceStack> parsed = dispatcher.parse(cmd, source);
        boolean parses = parsed.getExceptions().isEmpty()
                && !parsed.getReader().canRead()
                && parsed.getContext().getLastChild().getCommand() != null;
        if (!expectOk) {
            boolean refused = !parses;
            if (parses) {
                try {
                    dispatcher.execute(parsed);
                } catch (Exception e) {
                    refused = true;
                }
            }
            check("'" + cmd + "' is refused", refused);
            return;
        }
        if (!parses) {
            check("'" + cmd + "' parses to an executable node", false);
            return;
        }
        try {
            dispatcher.execute(parsed);
            check("'" + cmd + "' executes", true);
        } catch (Exception e) {
            check("'" + cmd + "' executes (" + e.getMessage() + ")", false);
        }
    }

    private static void check(String what, boolean ok) {
        if (ok) {
            passed++;
        } else {
            FAILURES.add(what);
        }
    }

    private SelfTest() {}
}
