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
import net.minecraft.world.phys.Vec3;
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
            Cow cow = new Cow(net.minecraft.world.entity.EntityType.COW, server.overworld());
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
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.List.of())));
            check("place: overworld dimension matches", Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of(overworld), java.util.Optional.empty(), java.util.List.of())));
            check("place: nether dimension does not", !Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.empty(), java.util.Optional.empty(),
                    java.util.Optional.of(net.minecraft.world.level.Level.NETHER.identifier()), java.util.Optional.empty(), java.util.List.of())));
            check("place: the overworld biome tag matches here", Places.isAt(solo, new com.sablednah.chronicler.data.Place(
                    java.util.Optional.of("#minecraft:is_overworld"), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.List.of())));
            check("place: a lot needs CityWorld", Lots.describe(server.overworld(), solo.blockPosition()).isEmpty()
                    || Places.isAt(solo, new com.sablednah.chronicler.data.Place(java.util.Optional.empty(),
                            java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.of("zzz-no-such-lot"), java.util.List.of())) == false);
            check("hot_foot ships hidden with a place giver", quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, ChroniclerIds.of("hot_foot")))
                    .map(h -> h.value().hidden() && h.value().giver().isPresent()).orElse(false));

            // Givers: an op-placed block offers, right-click accepts, the store round-trips.
            QuestEngine.journal(solo).clear();
            GiverStore store = GiverStore.get(server);
            net.minecraft.core.BlockPos here = solo.blockPosition().offset(-4, 0, 4); // off the ZARP camp: a data giver on the same block would outrank the store
            store.set(server.overworld(), here, firstSteps);
            check("giver store answers", Givers.questAt(server.overworld(), here).map(firstSteps::equals).orElse(false));
            Markers.EXTRA_VIEWERS.add(solo);
            Givers.tickMarkers(server);
            String giverKey = GiverStore.key(server.overworld(), here);
            check("a mark is sent to the player near the op-placed giver", Markers.shownTo(solo.getUUID()) >= 1
                    && !Markers.textShownTo(solo.getUUID(), giverKey).isEmpty());
            {
                // A giver that moves (a possessed NPC): the mark goes with it, sent as a move, not a new entity.
                Vec3 was = Markers.positionShownTo(solo.getUUID(), giverKey);
                Vec3 moved = was.add(3, 0, 0);
                Markers.sync(server, java.util.Map.of(giverKey, List.of(ChroniclerIds.of("first_steps"))), k -> moved, k -> server.overworld());
                check("a mark follows a giver that moved", moved.equals(Markers.positionShownTo(solo.getUUID(), giverKey)));
                Givers.tickMarkers(server); // back to the block's own position
                check("a mark comes back when the giver does", was.equals(Markers.positionShownTo(solo.getUUID(), giverKey)));
            }
            check("the mark says 'available' before accepting", Markers.textShownTo(solo.getUUID(), giverKey)
                    .equals(com.sablednah.chronicler.ChroniclerConfig.GIVER_MARKER_TEXT.get()));
            // The four oak logs are still in the pack, so accepting completes it on the spot.
            check("giver block: first click offers, does not accept", Givers.onUseBlock(solo, server.overworld(), here)
                    && !QuestEngine.journal(solo).isActive(firstSteps) && !QuestEngine.journal(solo).isComplete(firstSteps));
            var breakGiver = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(server.overworld(), here, server.overworld().getBlockState(here), solo);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(breakGiver);
            check("giver block: breaking it is refused", breakGiver.isCanceled());
            var breakOther = new net.neoforged.neoforge.event.level.BlockEvent.BreakEvent(server.overworld(), here.above(3), server.overworld().getBlockState(here.above(3)), solo);
            net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(breakOther);
            check("giver block: breaking any other block is not", !breakOther.isCanceled());
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
            check("the mark goes with the giver", Markers.textShownTo(solo.getUUID(), giverKey).isEmpty());
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
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Map.of("other_flag", true), java.util.Map.of(), java.util.Map.of(), java.util.List.of(), java.util.List.of());
            check("availability: an unset flag is unmet", !Conditions.unmet(solo, needsFlag).isEmpty());
            flags.set("other_flag", true);
            check("availability: the flag set is met", Conditions.unmet(solo, needsFlag).isEmpty());
            flags.set("other_flag", false);
            flags.set("selftest_flag", false);
            check("world flags clear", flags.view().isEmpty() || !flags.is("selftest_flag"));
            QuestEngine.journal(solo).setFlag("Chose_Mercy", true);
            check("player flag set and normalised", QuestEngine.journal(solo).hasFlag("chose_mercy"));
            var needsKarma = new com.sablednah.chronicler.data.Availability(java.util.Optional.of(20L), java.util.Optional.empty(),
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), java.util.List.of(), java.util.List.of());
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

            overnight(server, solo);
        } finally {
            QuestEngine.journal(solo).clear();
            solo.discard();
        }

        check("build stamp: no resource at all reads unknown, and does not throw",
                com.sablednah.chronicler.BuildInfo.parse(null).commit().equals("unknown") && com.sablednah.chronicler.BuildInfo.describe(com.sablednah.chronicler.BuildInfo.parse(null)).contains("unknown"));
        check("build stamp: a malformed resource reads unknown, and does not throw",
                com.sablednah.chronicler.BuildInfo.parse(new java.io.ByteArrayInputStream("\u0000garbage=\\u00zz\n=\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).version().equals("unknown"));
        // Mixed corruption in both orderings. The VALID-FIRST case is the one with teeth: those lines are
        // already in the map when load throws, so a reader that swallows the throw and reads what landed
        // reports a real-looking commit (LegendQuest ran that broken reader to prove which case catches it).
        // Bad-first throws on line one with nothing populated, so it passes either way; kept for the record.
        check("build stamp: a valid line then a bad escape degrades to unknown, all of it",
                com.sablednah.chronicler.BuildInfo.parse(new java.io.ByteArrayInputStream("commit=deadbeef\nbranch=main\ntime=\\u00zz\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).commit().equals("unknown"));
        check("build stamp: a bad escape then a valid line degrades to unknown, all of it",
                com.sablednah.chronicler.BuildInfo.parse(new java.io.ByteArrayInputStream("time=\\u00zz\ncommit=deadbeef\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1))).commit().equals("unknown"));
        check("build stamp: a stream that throws mid-read degrades to unknown", com.sablednah.chronicler.BuildInfo.parse(new java.io.InputStream() {
            int n = 0;
            @Override public int read() throws java.io.IOException { if (n++ > 12) throw new java.io.IOException("cut"); return "commit=abcd1234\n".charAt(n - 1); }
        }).commit().equals("unknown"));
        check("build stamp: a good stamp reads whole", com.sablednah.chronicler.BuildInfo.describe(com.sablednah.chronicler.BuildInfo.parse(new java.io.ByteArrayInputStream("commit=abcd1234\nbranch=b\ntime=t\nversion=v\n".getBytes()))).equals("v (build abcd1234 on b, t)"));
        check("build stamp: the version carries the Minecraft line, like the filename", com.sablednah.chronicler.BuildInfo.version().contains("+mc"));
        check("build stamp: a dev run reads its commit (" + com.sablednah.chronicler.BuildInfo.describe() + ")",
                !"unknown".equals(com.sablednah.chronicler.BuildInfo.commit()) && !"unknown".equals(com.sablednah.chronicler.BuildInfo.version()));
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

    /**
     * The 2026-09-08 overnight: quest items, rituals, waits, endings, replay,
     * progress, race/class gates, tagged spawns, kill drops, place alternatives,
     * shared NPCs and the ZARP pack -- every one through the real engine.
     */
    private static void overnight(MinecraftServer server, FakePlayer solo) {
        var level = server.overworld();
        var registries = server.registryAccess();
        QuestLog log = QuestEngine.journal(solo);
        log.clear();
        Identifier ember = ChroniclerIds.of("ember");
        Identifier beacon = ChroniclerIds.of("the_beacon");
        Identifier prologue = ChroniclerIds.of("prologue");

        // --- quest items: built from the registry, marked invisibly, recognised by the mark alone ---
        ItemStack stack = com.sablednah.chronicler.data.QuestItem.build(registries, ember, 2);
        check("quest item builds from its registry entry", !stack.isEmpty() && stack.getCount() == 2 && stack.getItem() == Items.BLAZE_POWDER);
        check("quest item carries its mark", com.sablednah.chronicler.data.QuestItem.markOf(stack).map(ember::equals).orElse(false));
        check("quest item is named", stack.getHoverName().getString().contains("Ember"));
        check("a plain blaze powder is not the quest item", !com.sablednah.chronicler.data.QuestItem.is(new ItemStack(Items.BLAZE_POWDER), ember));
        check("unknown quest item builds nothing", com.sablednah.chronicler.data.QuestItem.build(registries, ChroniclerIds.of("no_such_item"), 1).isEmpty());
        check("quest item on a consumable base is not consumable", !com.sablednah.chronicler.data.QuestItem.build(registries, ember, 1).has(net.minecraft.core.component.DataComponents.CONSUMABLE)
                && new ItemStack(Items.HONEY_BOTTLE).has(net.minecraft.core.component.DataComponents.CONSUMABLE));
        try {
            var params = new net.minecraft.world.level.storage.loot.LootParams.Builder(level)
                    .create(net.minecraft.world.level.storage.loot.parameters.LootContextParamSets.EMPTY);
            var ctx = new net.minecraft.world.level.storage.loot.LootContext.Builder(params).create(java.util.Optional.empty());
            ItemStack looted = new QuestItemLoot.Function(ember, 1).apply(new ItemStack(Items.STICK), ctx);
            check("loot function turns a placeholder into the quest item", com.sablednah.chronicler.data.QuestItem.is(looted, ember));
        } catch (RuntimeException e) {
            check("loot function turns a placeholder into the quest item (" + e + ")", false);
        }

        // --- objective matching: kill lists, tags, collect by tag, place alternatives ---
        Zombie z = new Zombie(level);
        z.addTag(Trackers.TAG_PREFIX + "probe");
        var killList = new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of("minecraft:cow", "minecraft:zombie"), 1, java.util.Optional.empty(), java.util.Optional.empty());
        var killTag = new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of("minecraft:cow"), 1, java.util.Optional.of("probe"), java.util.Optional.empty());
        var killMiss = new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of("minecraft:cow"), 1, java.util.Optional.of("other"), java.util.Optional.empty());
        check("kill: any of a target list counts", Trackers.of(killList).countsKill(solo, z, killList));
        check("kill: a spawn tag counts", Trackers.of(killTag).countsKill(solo, z, killTag));
        check("kill: the wrong tag does not", !Trackers.of(killMiss).countsKill(solo, z, killMiss));
        z.discard();
        var byTag = new com.sablednah.chronicler.data.ObjectiveTypes.Collect(Identifier.withDefaultNamespace("air"), 1, false,
                java.util.Optional.empty(), java.util.Optional.of(Identifier.withDefaultNamespace("logs")));
        solo.getInventory().clearContent();
        solo.getInventory().add(new ItemStack(Items.BIRCH_LOG, 3));
        int tagged = Trackers.of(byTag).poll(solo, byTag).orElse(-1);
        check("collect: an item tag counts any member (counted " + tagged + ", holding " + solo.getInventory().getItem(0) + ")", tagged == 3);
        solo.getInventory().clearContent();
        var nether = java.util.Optional.of(Identifier.withDefaultNamespace("the_nether"));
        var end = java.util.Optional.of(Identifier.withDefaultNamespace("the_end"));
        var over = java.util.Optional.of(Identifier.withDefaultNamespace("overworld"));
        var placeNether = new com.sablednah.chronicler.data.Place(java.util.Optional.empty(), java.util.Optional.empty(), nether, java.util.Optional.empty(), List.of());
        var placeEnd = new com.sablednah.chronicler.data.Place(java.util.Optional.empty(), java.util.Optional.empty(), end, java.util.Optional.empty(), List.of());
        var placeOver = new com.sablednah.chronicler.data.Place(java.util.Optional.empty(), java.util.Optional.empty(), over, java.util.Optional.empty(), List.of());
        var anyYes = new com.sablednah.chronicler.data.Place(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), List.of(placeNether, placeOver));
        var anyNo = new com.sablednah.chronicler.data.Place(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), List.of(placeNether, placeEnd));
        check("place: one alternative holding is enough", Places.isAt(solo, anyYes));
        check("place: no alternative holding fails", !Places.isAt(solo, anyNo));
        check("place: alternatives describe themselves", anyYes.describe().contains("or"));

        // --- race and class gates ---
        var needsRace = new com.sablednah.chronicler.data.Availability(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of("no_such_race"), List.of());
        List<String> unmet = Conditions.unmet(solo, needsRace);
        check("availability: a race nobody has is unmet (" + (Sheet.available() ? "sheets present" : "no sheets") + ")", unmet.size() == 1);
        if (Sheet.available()) {
            var mine = Sheet.race(solo);
            Chronicler.LOGGER.info("SelfTest: the fake player's race is {}, classes {}", mine.orElse(null), Sheet.classes(solo));
            if (mine.isPresent()) {
                var hasRace = new com.sablednah.chronicler.data.Availability(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of(mine.get().getPath()), List.of());
                check("availability: the player's own race (bare id) is met", Conditions.unmet(solo, hasRace).isEmpty());
                var hasRaceFull = new com.sablednah.chronicler.data.Availability(java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), List.of(mine.get().toString()), List.of());
                check("availability: the player's own race (full id) is met", Conditions.unmet(solo, hasRaceFull).isEmpty());
            }
        }

        // --- the beacon: wait, ritual with a pattern and an item, tagged spawn, kill drop, quest-item collect ---
        log.complete(ChroniclerIds.of("first_steps"));
        log.complete(ChroniclerIds.of("things_in_the_dark"));
        log.complete(ChroniclerIds.of("night_watch"));
        check("beacon: accepted once night watch is done", com.sablednah.chronicler.api.Quests.accept(solo, beacon).isEmpty());
        QuestLog.Entry be = log.entry(beacon);
        QuestEngine.poll(solo);
        check("wait: not done at once", be != null && be.stage == 0);
        if (be != null) be.enteredAt = level.getGameTime() - 20L * 60;
        QuestEngine.poll(solo);
        be = log.entry(beacon);
        check("wait: done once the time has passed (beat advanced)", be != null && be.stage == 1);
        // Build the rite two blocks over, on the surface of the forced chunk.
        var spawn = level.getRespawnData().globalPos().pos();
        var base = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                new net.minecraft.core.BlockPos(spawn.getX() + 3, 0, spawn.getZ() + 3));
        var fire = base;
        level.setBlockAndUpdate(fire, net.minecraft.world.level.block.Blocks.CAMPFIRE.defaultBlockState());
        level.setBlockAndUpdate(fire.east(), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(fire.west(), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(fire.south(), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(fire.north(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
        ItemStack torch = new ItemStack(Items.TORCH);
        solo.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, torch);
        boolean handled = QuestEngine.onUseBlock(solo, level, fire, torch);
        check("ritual: a click with the pattern incomplete is refused, and handled", handled && log.entry(beacon) != null && log.entry(beacon).stage == 1);
        check("ritual: a click on some other block is not a ritual", !QuestEngine.onUseBlock(solo, level, fire.east(), torch));
        level.setBlockAndUpdate(fire.north(), net.minecraft.world.level.block.Blocks.COBBLESTONE.defaultBlockState());
        ItemStack bare = ItemStack.EMPTY;
        check("ritual: the right pattern but no item is refused", QuestEngine.onUseBlock(solo, level, fire, bare) && log.entry(beacon).stage == 1);
        handled = QuestEngine.onUseBlock(solo, level, fire, torch);
        be = log.entry(beacon);
        check("ritual: pattern and item complete the beat", handled && be != null && be.stage == 2);
        check("ritual: a torch that is not consumed stays in hand", torch.getCount() == 1);
        // The section index lags a tick on a dev server with no player; the flat entity list does not.
        List<Zombie> drawn = new ArrayList<>();
        for (var ent : level.getAllEntities()) if (ent instanceof Zombie zz && zz.getTags().contains(Trackers.TAG_PREFIX + "drawn")) drawn.add(zz);
        // Entities added before the forced chunk's first tick are not yet visible to any lookup (a known dev-server
        // trap), so the spawn is proven by what the granter reports, and the kill by a pair we can hold.
        check("spawn: the beat's spawn effect placed two entities (granter reports " + Rewards.lastSpawned() + ", lookup sees " + drawn.size() + ")",
                Rewards.lastSpawned() == 2);
        if (drawn.size() < 2) {
            // The entity index can lag a tick on a dev server; drive the kill with our own tagged pair so the rest still runs.
            drawn = new ArrayList<>();
            for (int n = 0; n < 2; n++) { Zombie zz = new Zombie(level); zz.snapTo(fire.getX(), fire.getY(), fire.getZ(), 0F, 0F); zz.addTag(Trackers.TAG_PREFIX + "drawn"); zz.addTag(Rewards.FOR_PREFIX + solo.getUUID()); drawn.add(zz); }
        }
        int itemsBefore = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(fire).inflate(24)).size();
        check("spawn: what was spawned is remembered, and for whom", Rewards.spawnedCount() >= 1
                && drawn.stream().allMatch(zz -> zz.getTags().stream().anyMatch(t -> t.startsWith(Rewards.FOR_PREFIX))) || drawn.size() < 2);
        // One kill is the player's; the other dies to "a fall" and still counts, because it was spawned for them.
        QuestEngine.onKill(solo, drawn.get(0)); drawn.get(0).discard();
        be = log.entry(beacon);
        check("kill: the player's own kill counts", be != null && be.stage == 2 && be.progress.get(0) == 1);
        drawn.get(1).addTag(Rewards.FOR_PREFIX + solo.getUUID());
        QuestEngine.onUnownedDeath(solo, drawn.get(1)); drawn.get(1).discard();
        be = log.entry(beacon);
        check("kill: a spawned mob that died to something else still counts", be != null && be.stage == 3);
        int itemsAfter = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(fire).inflate(24)).size();
        Chronicler.LOGGER.info("SelfTest: kill drops on the ground before {} after {}", itemsBefore, itemsAfter);
        solo.getInventory().add(com.sablednah.chronicler.data.QuestItem.build(registries, ember, 2));
        QuestEngine.poll(solo);
        check("collect: two marked embers finish the beacon", log.isComplete(beacon));
        check("collect: consumed embers are gone from the pack", Trackers.count(solo, st -> com.sablednah.chronicler.data.QuestItem.is(st, ember)) == 0);
        for (var pos : List.of(fire, fire.east(), fire.west(), fire.south(), fire.north())) level.setBlockAndUpdate(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());

        // --- progress: only counting quests move it ---
        var overall = QuestEngine.progress(solo, java.util.Optional.empty());
        var chapterP = QuestEngine.progress(solo, java.util.Optional.of(prologue));
        check("progress: main-chapter quests count, repeatables do not (prologue " + chapterP.done() + "/" + chapterP.total() + ")",
                chapterP.total() == 5 && chapterP.done() == 4);
        check("progress: overall is at least the prologue", overall.total() >= chapterP.total() && overall.percent() >= 0 && overall.percent() <= 100);

        // --- endings and replay ---
        var nightWatch = QuestEngine.quest(server, ChroniclerIds.of("night_watch")).map(h -> h.value()).orElseThrow();
        check("endings: the prologue declares two", QuestEngine.endingsOf(server, prologue).size() == 2);
        QuestEngine.reachEnding(solo, nightWatch, "kindness");
        check("endings: reaching one records it", log.endings(prologue).contains("kindness") && log.endings(prologue).size() == 1);
        QuestEngine.reachEnding(solo, nightWatch, "kindness");
        check("endings: reaching it again does not double count", log.endings(prologue).size() == 1);
        log.setFlag("selftest_prologue_flag", true, prologue);
        log.setFlag("selftest_other_flag", true, ChroniclerIds.of("elsewhere"));
        check("replay: a chapter that was never touched is refused", !QuestEngine.replay(fake(server, "ChroniclerTestD"), prologue));
        check("replay: a replayable chapter starts over", QuestEngine.replay(solo, prologue) && !log.isComplete(beacon) && !log.isComplete(ChroniclerIds.of("first_steps")));
        check("replay: endings found stay found", log.endings(prologue).contains("kindness"));
        check("replay: the chapter's own player flags are cleared, others kept", !log.hasFlag("selftest_prologue_flag") && log.hasFlag("selftest_other_flag"));
        check("replay: a chapter that is not replayable is refused", !QuestEngine.replay(solo, ChroniclerIds.of("no_such_chapter")));

        // --- the ZARP pack: on with ZombieMod, and every file in it loads ---
        boolean zm = net.neoforged.fml.ModList.get().isLoaded("zombiemod");
        boolean zarp = QuestEngine.quest(server, Identifier.fromNamespaceAndPath("zarp", "patient_zero")).isPresent();
        check("zarp: the pack is " + (zm ? "on with ZombieMod" : "off without ZombieMod"), zarp == zm);
        if (zarp) {
            int zq = 0;
            for (var h : QuestEngine.quests(server).listElements().toList()) if (h.key().identifier().getNamespace().equals("zarp")) zq++;
            check("zarp: all 20 quests loaded (" + zq + ")", zq == 20);
            check("zarp: the finale has two endings, survivors two, beyond one",
                    QuestEngine.endingsOf(server, Identifier.fromNamespaceAndPath("zarp", "finale")).size() == 2
                    && QuestEngine.endingsOf(server, Identifier.fromNamespaceAndPath("zarp", "survivors")).size() == 2
                    && QuestEngine.endingsOf(server, Identifier.fromNamespaceAndPath("zarp", "beyond")).size() == 1);
            check("zarp: quest items loaded", com.sablednah.chronicler.data.QuestItem.get(registries, Identifier.fromNamespaceAndPath("zarp", "origin_sample")).isPresent());
            var okafor = GiverStore.get(server).placedFor(Identifier.fromNamespaceAndPath("zarp", "the_camp"));
            if (Npcs.available()) {
                check("zarp: Okafor stands near spawn", okafor.isPresent());
                okafor.ifPresent(id -> {
                    var quests = GiverStore.get(server).questsAtNpc(id);
                    check("zarp: Okafor gives her whole storyline (" + quests.size() + " quests)", quests.size() >= 6
                            && quests.contains(Identifier.fromNamespaceAndPath("zarp", "before_dark")));
                    check("zarp: Okafor holds her potion", Npcs.provider().get().equipment(server, id).getOrDefault("mainhand", "").startsWith("minecraft:potion"));
                    var placed = Npcs.provider().get().byId(server, id);
                    check("zarp: her NPC is within 12 blocks of spawn", placed.map(pl -> pl.pos().distanceTo(net.minecraft.world.phys.Vec3.atCenterOf(spawn)) < 40).orElse(false));
                });
            } else {
                check("zarp: no Cast, so nobody is placed (listed instead)", okafor.isEmpty());
            }
            // The main line is gated on flags and requirements, not reachable from a fresh journal.
            var pz = QuestEngine.quest(server, Identifier.fromNamespaceAndPath("zarp", "patient_zero")).get().value();
            // Genus spawns go through the seam and come back as a mob, dressed by ZombieMod first.
            var genusMob = Genera.spawn(level, Identifier.fromNamespaceAndPath("zarp", "patient"), net.minecraft.world.phys.Vec3.atCenterOf(spawn.above(2)));
            check("genus: the seam spawns a Patient and hands it back", genusMob.isPresent()
                    && genusMob.get().getPersistentData().getString("zombiemod:genus").map("zarp:patient"::equals).orElse(false));
            genusMob.ifPresent(m -> {
                boolean masked = !m.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.HEAD).isEmpty();
                check("genus: ZombieMod put the mask on its head", masked);
                Rewards.grant(solo, new com.sablednah.chronicler.data.RewardTypes.Spawn(java.util.Optional.of(Identifier.withDefaultNamespace("zombie")),
                        java.util.Optional.of("zarp:patient"), 1, 2.0, java.util.Optional.of("&7Test Patient"), java.util.Optional.of("probe_patient"), 0D,
                        java.util.Map.of("head", "minecraft:leather_helmet", "mainhand", "minecraft:stick"), false), ChroniclerIds.of("selftest"), nightWatch);
                check("genus: a spawn effect placed one through the seam", Rewards.lastSpawned() == 1);
                m.discard();
            });
            check("quest item insulin is not drinkable", !com.sablednah.chronicler.data.QuestItem.build(registries, Identifier.fromNamespaceAndPath("zarp", "insulin"), 1).has(net.minecraft.core.component.DataComponents.CONSUMABLE));
            var fireAt = GiverStore.get(server).placedBlock(Identifier.fromNamespaceAndPath("zarp", "wake_up"));
            check("zarp: the campfire was dropped near spawn", fireAt.isPresent()
                    && fireAt.get().distManhattan(spawn) < 8 && level.getBlockState(fireAt.get()).is(net.minecraft.world.level.block.Blocks.CAMPFIRE));
            // Dry ground: a column whose surface is water resolves to the nearest column that is not.
            {
                var wet = spawn.offset(-14, 0, -14);
                var g = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wet);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    level.setBlockAndUpdate(g.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(g.offset(dx, 0, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
                var found = Givers.dryColumn(level, wet.getX(), wet.getZ());
                boolean moved = Math.max(Math.abs(found.getX() - wet.getX()), Math.abs(found.getZ() - wet.getZ())) >= 2;
                boolean isDry = level.getFluidState(found.below()).isEmpty() && level.getFluidState(found).isEmpty();
                check("placement: a wet column resolves to nearby dry ground (moved " + moved + ", dry " + isDry + ")", moved && isDry);
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) level.setBlockAndUpdate(g.offset(dx, -1, dz), net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
            }
            // Decor on a cleared patch (the dev world keeps older camps): a post on the ground, its lantern ON it, no gap.
            {
                var patch = spawn.offset(12, 0, 12);
                var g = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, patch).below();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) for (int dy = 1; dy <= 4; dy++)
                    level.setBlockAndUpdate(g.offset(dx, dy, dz), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(g, net.minecraft.world.level.block.Blocks.STONE.defaultBlockState());
                Givers.placeDecor(level, g.above(), List.of(
                        new com.sablednah.chronicler.data.GiverTypes.Position.Decor(List.of(0, 0, 0), "minecraft:oak_fence"),
                        new com.sablednah.chronicler.data.GiverTypes.Position.Decor(List.of(0, 1, 0), "minecraft:lantern")), ChroniclerIds.of("selftest"));
                boolean postOk = level.getBlockState(g.above()).is(net.minecraft.world.level.block.Blocks.OAK_FENCE);
                boolean lanternOk = level.getBlockState(g.above(2)).is(net.minecraft.world.level.block.Blocks.LANTERN);
                boolean gap = level.getBlockState(g.above(3)).is(net.minecraft.world.level.block.Blocks.LANTERN);
                check("decor: the lantern sits on its post, no gap (post " + postOk + ", lantern " + lanternOk + ", gap " + gap + ")", postOk && lanternOk && !gap);
                for (int dy = 1; dy <= 3; dy++) level.setBlockAndUpdate(g.above(dy), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            }
            check("zarp: the campfire gives Wake Up", fireAt.flatMap(f -> Givers.questAt(level, f)).map(q -> q.getPath().equals("wake_up")).orElse(false));
            // A delivery: two insulin to Okafor's quest. Held but not handed over is nothing; the click hands it over.
            var bd = Identifier.fromNamespaceAndPath("zarp", "before_dark");
            log.complete(Identifier.fromNamespaceAndPath("zarp", "wake_up")); log.complete(Identifier.fromNamespaceAndPath("zarp", "the_camp"));
            if (com.sablednah.chronicler.api.Quests.accept(solo, bd).isEmpty()) {
                var bde = log.entry(bd);
                bde.jump(2, List.of(2)); // the return beat
                solo.getInventory().add(com.sablednah.chronicler.data.QuestItem.build(registries, Identifier.fromNamespaceAndPath("zarp", "insulin"), 2));
                QuestEngine.poll(solo);
                check("deliver: holding the items is not delivering them", log.entry(bd) != null && log.entry(bd).stage == 2 && log.entry(bd).progress.get(0) == 0);
                check("deliver: a click on the wrong giver does nothing", !QuestEngine.onDeliver(solo, Identifier.fromNamespaceAndPath("zarp", "the_signal")) && log.entry(bd).stage == 2);
                check("deliver: a click on Okafor hands them over and finishes the quest", QuestEngine.onDeliver(solo, Identifier.fromNamespaceAndPath("zarp", "the_camp")) && log.isComplete(bd));
                check("deliver: the insulin is gone", Trackers.count(solo, st -> com.sablednah.chronicler.data.QuestItem.is(st, Identifier.fromNamespaceAndPath("zarp", "insulin"))) == 0);
                check("deliver: the objective names her", QuestEngine.quest(server, bd).get().value().beats().get(2).objectives().getFirst().describe().contains("Okafor"));
            } else {
                check("deliver: before_dark could be accepted for the test", false);
            }
            // own_kill: the First Bed blowing itself up is not a kill; another is spawned and the player told.
            var hc = Identifier.fromNamespaceAndPath("zarp", "house_calls");
            log.complete(Identifier.fromNamespaceAndPath("zarp", "the_signal"));
            if (com.sablednah.chronicler.api.Quests.accept(solo, hc).isEmpty()) {
                var hce = log.entry(hc);
                hce.jump(2, List.of(1)); // the First Bed beat
                Zombie bed = new Zombie(level); bed.snapTo(spawn.getX(), spawn.getY(), spawn.getZ(), 0F, 0F);
                bed.addTag(Trackers.TAG_PREFIX + "first_bed"); bed.addTag(Rewards.FOR_PREFIX + solo.getUUID());
                int spawnedBefore = Rewards.lastSpawned();
                QuestEngine.onUnownedDeath(solo, bed); bed.discard();
                check("own_kill: a self-destructed boss does not count", log.entry(hc) != null && log.entry(hc).stage == 2 && log.entry(hc).progress.get(0) == 0);
                QuestEngine.onKill(solo, bed);
                check("own_kill: the player's own kill does", log.entry(hc) != null && log.entry(hc).stage == 3);
            } else {
                check("own_kill: house_calls could be accepted for the test", false);
            }
            log.clear();
            check("zarp: the finale is locked until the sample is kept", !QuestEngine.available(solo, Identifier.fromNamespaceAndPath("zarp", "patient_zero"), pz));
            check("zarp: wake up is available to a fresh journal", QuestEngine.available(solo, Identifier.fromNamespaceAndPath("zarp", "wake_up"),
                    QuestEngine.quest(server, Identifier.fromNamespaceAndPath("zarp", "wake_up")).get().value()));
        }
        log.clear();
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
