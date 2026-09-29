package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.ParseResults;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.core.QuestMap;
import com.sablednah.chronicler.data.ChroniclerIds;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.network.JournalPayload;
import com.sablednah.chronicler.network.WaypointsPayload;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
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

        // The quest map's layout, which the client draws and the self-test can check.
        var map = QuestMap.layout(List.of(new QuestMap.Node("a", List.of(), 0), new QuestMap.Node("b", List.of("a"), 1),
                new QuestMap.Node("c", List.of("b"), 2), new QuestMap.Node("d", List.of("elsewhere:x"), 3)));
        java.util.function.Function<String, QuestMap.Placed> cell = cid -> map.stream().filter(m -> m.id().equals(cid)).findFirst().orElseThrow();
        check("map: each prerequisite steps one column right",
                cell.apply("b").col() == cell.apply("a").col() + 1 && cell.apply("c").col() == cell.apply("b").col() + 1);
        check("map: a chain runs straight across", cell.apply("a").row() == cell.apply("b").row() && cell.apply("b").row() == cell.apply("c").row());
        check("map: a prerequisite from another chapter stands in on the left",
                cell.apply("elsewhere:x").external() && cell.apply("elsewhere:x").col() == 0 && cell.apply("d").col() == 1);
        check("map: no two quests share a cell", map.stream().map(m -> m.col() + "," + m.row()).distinct().count() == map.size());
        check("map: a cycle is laid out, not recursed into for ever", QuestMap.layout(List.of(
                new QuestMap.Node("p", List.of("q"), 0), new QuestMap.Node("q", List.of("p"), 1))).size() == 2);

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

            // The journal panel: what a modded client is sent. The book's answers, in one payload.
            var panel = JournalPanel.build(solo, true, "");
            var prologueView = panel.chapters().stream().filter(c -> c.id().equals("chronicler:prologue")).findFirst().orElse(null);
            check("panel lists the prologue", prologueView != null);
            java.util.function.Function<String, JournalPayload.QuestView> view = qid -> prologueView == null ? null
                    : prologueView.quests().stream().filter(q -> q.id().equals(qid)).findFirst().orElse(null);
            var stepsView = view.apply(firstSteps.toString());
            var thingsView = view.apply(things.toString());
            var watchView = view.apply("chronicler:night_watch");
            check("panel: first_steps is complete", stepsView != null && stepsView.status() == JournalPayload.COMPLETE);
            check("panel: things_in_the_dark is under way", thingsView != null && thingsView.status() == JournalPayload.ACTIVE);
            check("panel: night_watch is listed while locked", watchView != null && watchView.status() == JournalPayload.LOCKED);
            check("panel: night_watch's map edge runs from things_in_the_dark", watchView != null && watchView.requires().contains(things.toString()));
            check("panel: hot_foot (hidden) is not listed before it is found", view.apply("chronicler:hot_foot") == null);
            check("panel: an active quest offers Abandon and never Accept", thingsView != null
                    && thingsView.buttons().stream().anyMatch(b -> b.command().equals("quest abandon " + things))
                    && thingsView.buttons().stream().noneMatch(b -> b.command().startsWith("quest accept")));
            check("panel: an available quest's page is not a second Info button", panel.chapters().stream()
                    .flatMap(c -> c.quests().stream()).flatMap(q -> q.buttons().stream()).noneMatch(b -> b.command().startsWith("quest info")));
            // An unresolved Lang key comes back as the key itself, so a raw "panel.x" in the text is a missing def.
            String rawKey = panelText(panel).stream()
                    .filter(s -> s.contains("§") || s.contains("panel.") || s.contains("status.") || s.contains("cmd."))
                    .findFirst().orElse(null);
            check("panel text has no section signs or raw keys" + (rawKey == null ? "" : " (found: " + rawKey + ")"), rawKey == null);
            var wire = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), server.registryAccess());
            JournalPayload.CODEC.encode(wire, panel);
            var back = JournalPayload.CODEC.decode(wire);
            check("panel payload survives the wire", wire.readableBytes() == 0 && back.open()
                    && panelText(back).equals(panelText(panel)) && back.labels().keySet().equals(panel.labels().keySet())
                    && back.chapters().size() == panel.chapters().size());

            // A giver that borrows another quest's NPC ("of") is described as that NPC, never its filler name.
            // ZARP only: never_lived borrows the_camp's Dr Okafor with name "-" -- which printed "From: -".
            quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, Identifier.parse("zarp:never_lived"))).ifPresent(nl -> {
                String where = Givers.describe(server, nl.value());
                String camp = quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, Identifier.parse("zarp:the_camp")))
                        .map(c -> Givers.describe(server, c.value())).orElse("");
                check("a borrowed NPC giver reads as the NPC it borrows (never_lived: '" + where + "', the_camp: '" + camp + "')",
                        !camp.isEmpty() && where.equals(camp) && !where.equals(Lang.fmt("giver.npc", "name", "-")));
            });

            // Visibility: always lists a locked quest, unlocked waits for the prerequisite, found waits to be started.
            Identifier nightWatch = ChroniclerIds.of("night_watch");
            Quest watch = quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, nightWatch)).get().value();
            Quest watchUnlocked = new Quest(watch.name(), watch.description(), watch.chapter(), watch.requires(), watch.objectives(),
                    watch.rewards(), watch.repeatable(), watch.hidden(), watch.order(), watch.scope(), watch.scale(), watch.giver(),
                    watch.stages(), watch.availability(), watch.cooldown(), new Quest.Extras(watch.extras().counts(),
                    watch.extras().locked(), java.util.Optional.of(com.sablednah.chronicler.core.QuestVisibility.UNLOCKED), watch.extras().icon()));
            check("visibility always: a locked quest is listed", QuestEngine.visible(solo, nightWatch, watch));
            check("visibility unlocked: not listed while its prerequisite is open", !QuestEngine.visible(solo, nightWatch, watchUnlocked));
            Identifier hotFoot = ChroniclerIds.of("hot_foot");
            check("visibility found: hot_foot is hidden until started", !QuestEngine.visible(solo, hotFoot,
                    quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, hotFoot)).get().value()));

            // The panel's buttons run as the player -- and only /quest ones.
            QuestEngine.journal(solo).track(firstSteps);
            JournalPanel.request(solo, com.sablednah.chronicler.network.JournalRequestPayload.RUN, "quests abandon " + things);
            check("panel runs nothing that is not /quest (an abandon under /quests was refused)", QuestEngine.journal(solo).isActive(things));
            JournalPanel.request(solo, com.sablednah.chronicler.network.JournalRequestPayload.RUN, "quest track " + things);
            check("panel runs its own buttons as the player", QuestEngine.journal(solo).tracked().map(things::equals).orElse(false));

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
            check("visibility unlocked: listed once its prerequisite is done", QuestEngine.visible(solo, nightWatch, watchUnlocked));

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
                    // npc_remove with leave: what is left of them stands where they did, and gives a quest.
                    Identifier leaveQuest = ChroniclerIds.of("selftest_leave");
                    var standing = cast.byId(server, npc).map(p -> net.minecraft.core.BlockPos.containing(p.pos()));
                    GiverStore.get(server).setPlacedFor(leaveQuest, npc);
                    Rewards.grant(solo, new com.sablednah.chronicler.data.RewardTypes.NpcRemove(java.util.Optional.empty(), java.util.Optional.empty(),
                            java.util.Optional.of("minecraft:smithing_table"), java.util.Optional.of(firstSteps)),
                            leaveQuest, quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, firstSteps)).get().value());
                    boolean benchStands = standing.map(b -> server.overworld().getBlockState(b).is(net.minecraft.world.level.block.Blocks.SMITHING_TABLE)).orElse(false);
                    boolean benchGives = standing.flatMap(b -> Givers.questAt(server.overworld(), b)).map(firstSteps::equals).orElse(false);
                    check("npc_remove leave: the NPC is gone, the bench stands where they were and gives the quest (known "
                            + standing.isPresent() + ", bench " + benchStands + ", gives " + benchGives + ")",
                            standing.isPresent() && benchStands && benchGives && cast.byId(server, npc).isEmpty());
                    standing.ifPresent(b -> {
                        GiverStore.get(server).remove(server.overworld(), b);
                        server.overworld().setBlockAndUpdate(b, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                    });
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

            // Player flags from another mod (Threadwork watching Factions): the same store a
            // {"type": "flag", "player": true} reward writes and the flag objective reads.
            {
                var Q = com.sablednah.chronicler.api.Quests.class;
                var waiting = new com.sablednah.chronicler.data.ObjectiveTypes.FlagSet("selftest_claimed_hut", true, true);
                check("api: a player flag nobody set reads false, and its objective waits",
                        !com.sablednah.chronicler.api.Quests.playerFlag(solo, "selftest_claimed_hut")
                        && Trackers.of(waiting).poll(solo, waiting).orElse(-1) == 0);
                com.sablednah.chronicler.api.Quests.setPlayerFlag(solo, "  SelfTest_Claimed_Hut ", true);
                check("api: setPlayerFlag normalises like the flag reward, and playerFlag reads it back",
                        com.sablednah.chronicler.api.Quests.playerFlag(solo, "selftest_claimed_hut")
                        && QuestEngine.journal(solo).hasFlag("selftest_claimed_hut"));
                check("api: a waiting player-flag objective sees it on its next check", Trackers.of(waiting).poll(solo, waiting).orElse(-1) == 1);
                check("api: a player flag is not a world flag, nor anybody else's", !com.sablednah.chronicler.api.Quests.flag(solo, "selftest_claimed_hut")
                        && !com.sablednah.chronicler.api.Quests.playerFlag(fake(server, "ChroniclerTestD"), "selftest_claimed_hut"));
                com.sablednah.chronicler.api.Quests.setPlayerFlag(solo, "selftest_claimed_hut", false);
                var labelled = com.sablednah.chronicler.data.ObjectiveTypes.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE,
                        com.google.gson.JsonParser.parseString("{\"type\": \"flag\", \"name\": \"founded_a_faction\", \"player\": true, \"label\": \"Found your faction: /f create <name>\"}")).result();
                check("flag: a label is the line the journal shows", labelled.map(o -> o.describe().equals("Found your faction: /f create <name>")).orElse(false));
                check("flag: unlabelled, the flag's name still reads", !waiting.describe().isBlank() && !waiting.describe().contains("_"));
                check("api: setPlayerFlag false clears it", !com.sablednah.chronicler.api.Quests.playerFlag(solo, "selftest_claimed_hut"));
                check("api: Threadwork's reflective lookup finds both by name", java.util.Arrays.stream(Q.getMethods()).map(java.lang.reflect.Method::getName)
                        .filter(n -> n.equals("playerFlag") || n.equals("setPlayerFlag")).count() == 2);

                CommandSourceStack asSolo = server.createCommandSourceStack().withEntity(solo);
                command(server, asSolo, "chronicler flag player @s selftest_set_home", true);
                check("command: /chronicler flag player sets the player's own flag", QuestEngine.journal(solo).hasFlag("selftest_set_home"));
                command(server, asSolo, "chronicler flag player @s", true);
                command(server, asSolo, "chronicler flag player @s selftest_set_home false", true);
                check("command: ... false clears it", !QuestEngine.journal(solo).hasFlag("selftest_set_home"));
                command(server, asSolo, "chronicler flag player @s selftest_set_home maybe", false);
            }

            // The quest tracker HUD: the same answers as /quest, as data for a modded client's left-hand panel.
            {
                var hud = Hud.build(solo);
                var hudHotFoot = hud.quests().stream().filter(q -> q.id().equals(ChroniclerIds.of("hot_foot").toString())).findFirst();
                check("hud: an active quest is listed, named, with a line per objective",
                        hudHotFoot.isPresent() && !hudHotFoot.get().name().getString().isBlank() && !hudHotFoot.get().lines().isEmpty());
                check("hud: nothing in it is a raw message key", hud.quests().stream().allMatch(q ->
                        !q.name().getString().contains("hud.") && q.lines().stream().noneMatch(l -> l.getString().contains("hud."))));
                QuestEngine.journal(solo).track(ChroniclerIds.of("hot_foot"));
                check("hud: the tracked quest comes first, and is marked", !Hud.build(solo).quests().isEmpty()
                        && Hud.build(solo).quests().getFirst().id().equals(ChroniclerIds.of("hot_foot").toString())
                        && Hud.build(solo).quests().getFirst().tracked());
                check("hud: two builds of an unchanged journal are equal (so an unchanged HUD is not resent)", Hud.build(solo).equals(Hud.build(solo)));
                try { Hud.sync(solo); check("hud: syncing to a player with no client half sends nothing and does not throw", true); }
                catch (RuntimeException e) { check("hud: sync on a fake player threw " + e, false); }
            }

            // Achievements (2026-09-17): one generated advancement per chapter and per ending, granted
            // through the real vanilla API, never touched directly by the engine otherwise.
            //
            // A FakePlayer proves the generation and lookup: it is never sent through
            // PlayerList#placeNewPlayer, which is what seeds a real join's PlayerAdvancements from the
            // manager, so PlayerAdvancements#award is a silent no-op on it forever after -- true even
            // for a genuinely vanilla advancement (checked below against minecraft:story/root), so it
            // is not this feature's bug to fix. Whether a criterion actually lands, and the toast that
            // goes with it, wants a real client, the same boundary as everything else FakePlayer cannot
            // see (SelfTest's own class doc, and CLAUDE.md's Known traps).
            {
                var advancements = server.getAdvancements();
                var vanillaRoot = advancements.get(Identifier.withDefaultNamespace("story/root"));
                check("achievements: FakePlayer cannot award even a real vanilla advancement (the known gap, not this feature's)",
                        vanillaRoot != null && !solo.getAdvancements().award(vanillaRoot, vanillaRoot.value().criteria().keySet().iterator().next()));

                java.util.function.Function<Identifier, java.util.Optional<Identifier>> parentOf = id ->
                        java.util.Optional.ofNullable(advancements.get(id)).flatMap(h -> h.value().parent());
                check("achievements: the root advancement generated and loaded", advancements.get(Achievements.ROOT) != null);
                var prologueChapter = Achievements.chapterId(ChroniclerIds.of("prologue"));
                var errandsChapter = Achievements.chapterId(ChroniclerIds.of("errands"));
                check("achievements: a built-in chapter generated one (is the fantasy prologue's pack.mcmeta on the classpath?)",
                        advancements.get(prologueChapter) != null);
                check("achievements: a chapter with no 'requires' chains to the previous chapter by order, not root",
                        parentOf.apply(errandsChapter).map(prologueChapter::equals).orElse(false));
                check("achievements: the first chapter in its pack chains to root",
                        parentOf.apply(prologueChapter).map(Achievements.ROOT::equals).orElse(false));

                check("achievements: granting a known id finds it and does not throw",
                        Achievements.grant(server, solo, Achievements.ROOT) && Achievements.grant(server, solo, Achievements.ROOT));
                check("achievements: an unknown id is refused, not thrown", !Achievements.grant(server, solo, ChroniclerIds.of("no_such_advancement")));

                // Another mod's built-in pack opts in (Threadwork's copy of ZARP): the same scanner, resolved through
                // the caller's own class so two jars carrying /datapacks/zarp each find their own.
                check("achievements: a registered source's pack is scanned through its anchor class",
                        com.sablednah.chronicler.yaml.AchievementPack.chaptersIn(SelfTest.class, "/datapacks/prologue").contains(ChroniclerIds.of("prologue")));
                check("achievements: a source path that is not there finds nothing, and does not throw",
                        com.sablednah.chronicler.yaml.AchievementPack.chaptersIn(SelfTest.class, "/datapacks/no_such_pack").isEmpty());
                int sources = com.sablednah.chronicler.yaml.AchievementPack.sourceCount();
                com.sablednah.chronicler.api.Quests.registerAchievementSource(SelfTest.class, "datapacks/prologue/");
                com.sablednah.chronicler.api.Quests.registerAchievementSource(SelfTest.class, "/datapacks/prologue");
                check("achievements: registering a source twice (either spelling) keeps one",
                        com.sablednah.chronicler.yaml.AchievementPack.sourceCount() == sources + 1);

                Identifier zarpFinale = Identifier.fromNamespaceAndPath("zarp", "finale");
                var pzHolder = quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, Identifier.fromNamespaceAndPath("zarp", "patient_zero")));
                if (pzHolder.isPresent()) {
                    var cure = Achievements.endingId(zarpFinale, "cure");
                    check("achievements: a ZARP ending generated one, parented to its own chapter (not the fantasy prologue's)",
                            advancements.get(cure) != null && parentOf.apply(cure).map(Achievements.chapterId(zarpFinale)::equals).orElse(false));
                    check("achievements: an ending is hidden and framed as a challenge", advancements.get(cure) != null
                            && advancements.get(cure).value().display()
                                    .map(d -> d.isHidden() && d.getType() == net.minecraft.advancements.AdvancementType.CHALLENGE).orElse(false));
                    // reachEnding calls Achievements.grantOrWarn for a fresh ending; it must not throw, and
                    // must not warn (the id it computes has to be exactly the one generated above).
                    int before = FAILURES.size();
                    QuestEngine.reachEnding(solo, pzHolder.get().value(), "cure");
                    check("achievements: reaching an ending grants without a log warning (the id matches what was generated)", FAILURES.size() == before);
                }

                // The reward type: any quest can grant any advancement by id, not only Chronicler's own.
                Rewards.grant(solo, new com.sablednah.chronicler.data.RewardTypes.Advancement(errandsChapter), ChroniclerIds.of("selftest"),
                        quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, ChroniclerIds.of("hot_foot"))).get().value());
                // Rewards.grant swallows a bad reward rather than take the rest of the list down with it (Rewards.java's own contract).
                Rewards.grant(solo, new com.sablednah.chronicler.data.RewardTypes.Advancement(ChroniclerIds.of("no_such_advancement")), ChroniclerIds.of("selftest"),
                        quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, ChroniclerIds.of("hot_foot"))).get().value());
                check("achievements: the reward type ran both ways without throwing", true);
            }

            // Deadlines: a clock in the past fails the beat on the next poll; with no fall-back, the quest is dropped.
            QuestLog.Entry hf = QuestEngine.journal(solo).entry(ChroniclerIds.of("hot_foot"));
            hf.deadlineAt = Math.max(0L, server.overworld().getGameTime() - 1); // a fresh world is at tick 0, and -1 means no deadline
            check("deadline: clock shows 0:00 when out", QuestEngine.clock(hf.deadlineAt - server.overworld().getGameTime()).equals("0:00"));
            QuestEngine.poll(solo);
            check("deadline: running out abandons a quest with no fall-back", !QuestEngine.journal(solo).isActive(ChroniclerIds.of("hot_foot")));
            check("deadline: clock formats minutes", QuestEngine.clock(20L * 299).equals("4:59"));

            // settle: an objective satisfied THIS instant must not credit until it has held for real
            // seconds, continuously -- reported in play as an escort ending, and Phil appearing, the
            // moment a line is crossed rather than once the player is actually inside. Game time is
            // frozen for the whole of a synchronous self-test, so "holds long enough" is proven by
            // seeding an old-enough start rather than by waiting real ticks nothing here can pass.
            {
                Identifier settleQuest = ChroniclerIds.of("selftest_settle");
                int settleStage = 0;
                long now = server.overworld().getGameTime();
                check("settle: satisfied this instant does not credit yet",
                        !QuestEngine.settled(solo, settleQuest, settleStage, 0, true, now, 2));
                check("settle: still not, an instant later (frozen time, same call again)",
                        !QuestEngine.settled(solo, settleQuest, settleStage, 0, true, now, 2));
                check("settle: a break in the middle resets it, not just delays it",
                        !QuestEngine.settled(solo, settleQuest, settleStage, 0, false, now, 2)
                                && !QuestEngine.settled(solo, settleQuest, settleStage, 0, true, now, 2));
                check("settle: held long enough (simulated -- nothing here can pass real ticks) credits, once",
                        QuestEngine.settled(solo, settleQuest, settleStage, 0, true, now + 41, 2)
                                && !QuestEngine.settled(solo, settleQuest, settleStage, 0, true, now + 41, 2));
                // settleSeconds() defaults to 0 for every objective type that does not ask for a delay, and
                // the poll loop only calls settled() at all when it is > 0 -- so the hundreds of ordinary
                // Visit/Kill/Collect completions elsewhere in this very run, all instant, are that case's
                // real coverage; settled() itself was never meant to be asked "is zero seconds enough".
                check("settle: real content is unaffected unless it opts in", true);
            }

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
            minis(server, solo);
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

        // around / min_radius: "five dead at the fence" -- a ring round world spawn, however far off the player is.
        {
            var ops = com.mojang.serialization.JsonOps.INSTANCE;
            var fence = com.sablednah.chronicler.data.RewardTypes.CODEC.parse(ops, com.google.gson.JsonParser.parseString(
                    "{\"type\": \"spawn\", \"entity\": \"minecraft:zombie\", \"around\": \"spawn\", \"min_radius\": 17, \"radius\": 24}")).result();
            check("spawn: around and min_radius parse", fence.isPresent() && fence.get() instanceof com.sablednah.chronicler.data.RewardTypes.Spawn sp
                    && sp.around().equals("spawn") && sp.minRadius() == 17D);
            check("spawn: an around that is not player/spawn/giver refuses the file",
                    com.sablednah.chronicler.data.RewardTypes.CODEC.parse(ops, com.google.gson.JsonParser.parseString(
                            "{\"type\": \"spawn\", \"entity\": \"minecraft:zombie\", \"around\": \"moon\"}")).error().isPresent());
            var plain = com.sablednah.chronicler.data.RewardTypes.CODEC.parse(ops, com.google.gson.JsonParser.parseString(
                    "{\"type\": \"spawn\", \"entity\": \"minecraft:zombie\"}")).result();
            check("spawn: left out, around is the player and min_radius 2 (as before)", plain.isPresent()
                    && plain.get() instanceof com.sablednah.chronicler.data.RewardTypes.Spawn sp && sp.around().equals("player") && sp.minRadius() == 2D);
            if (fence.isPresent() && plain.isPresent()) {
                var fenceSpec = (com.sablednah.chronicler.data.RewardTypes.Spawn) fence.get();
                var nearSpec = new com.sablednah.chronicler.data.RewardTypes.Spawn(java.util.Optional.of(Identifier.withDefaultNamespace("zombie")),
                        java.util.Optional.empty(), 1, 4D, java.util.Optional.empty(), java.util.Optional.empty(), 0D, java.util.Map.of(), false, "giver", 0D);
                double px = solo.getX(), py = solo.getY(), pz = solo.getZ();
                solo.setPos(spawn.getX() + 120.5D, py, spawn.getZ() + 0.5D); // far enough that "round the player" could not pass as "round spawn"
                boolean ring = true, near = true;
                for (int n = 0; n < 12; n++) {
                    var at = Rewards.spawnPoint(solo, fenceSpec, beacon);
                    double d = Math.hypot(at.pos().getX() + 0.5D - (spawn.getX() + 0.5D), at.pos().getZ() + 0.5D - (spawn.getZ() + 0.5D));
                    if (at.dimension() != net.minecraft.world.level.Level.OVERWORLD || d < 16D || d > 25D) ring = false;
                    var by = Rewards.spawnPoint(solo, nearSpec, ChroniclerIds.of("no_such_quest"));
                    if (Math.hypot(by.pos().getX() + 0.5D - solo.getX(), by.pos().getZ() + 0.5D - solo.getZ()) > 5D) near = false;
                }
                solo.setPos(px, py, pz);
                check("spawn: around spawn lands between min_radius and radius of world spawn, not of the player", ring);
                check("spawn: around a giver the quest does not have falls back to the player", near);
            }
        }
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
            check("zarp: all 28 quests loaded (" + zq + ")", zq == 28); // 21 since Wrench's Notes (2026-09-15), 26 with the errands, 28 with The Plan and the bank job
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
            // The spawn block stays clear. /spawn (Standards) lands on it exactly, and ZARP's campfire once
            // sat on it. Checked on the DATA, since the dev world keeps whatever camp it placed first.
            List<String> onSpawn = new ArrayList<>();
            QuestEngine.quests(server).listElements().forEach(h -> h.value().giver().ifPresent(g -> {
                if (g instanceof com.sablednah.chronicler.data.GiverTypes.Position p && p.nearSpawn().isPresent()) {
                    int ox = p.nearSpawn().get().get(0), oz = p.nearSpawn().get().get(1);
                    if (p.block().isPresent() && ox == 0 && oz == 0) onSpawn.add(h.key().identifier() + " block");
                    for (var d : p.decor()) if (ox + d.offset().get(0) == 0 && oz + d.offset().get(2) == 0) onSpawn.add(h.key().identifier() + " " + d.block());
                }
                if (g instanceof com.sablednah.chronicler.data.GiverTypes.NpcGiver n && n.nearSpawn().isPresent()
                        && n.nearSpawn().get().get(0) == 0 && n.nearSpawn().get().get(1) == 0) onSpawn.add(h.key().identifier() + " npc");
            }));
            check("near-spawn givers keep the spawn block clear" + (onSpawn.isEmpty() ? "" : " (on it: " + onSpawn + ")"), onSpawn.isEmpty());
            check("zarp: the campfire was dropped near spawn", fireAt.isPresent()
                    && fireAt.get().distManhattan(spawn) < 8 && level.getBlockState(fireAt.get()).is(net.minecraft.world.level.block.Blocks.CAMPFIRE));
            // Dry ground: a column whose surface is water resolves to the nearest column that is not.
            {
                var wet = spawn.offset(-14, 0, -14);
                // Flood each column of the 3x3 at ITS OWN surface, and put back exactly what was there. Flooding every
                // column at the centre's height only wets the ones level with it: on the 26.1 dev world this corner is
                // a pit ten blocks deep, the neighbours kept dry grass on top, the finder rightly took one a block
                // away, and the check read "moved false" on ground that was never wet.
                List<BlockPos> tops = new ArrayList<>();
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                    tops.add(level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wet.offset(dx, 0, dz)));
                }
                java.util.Map<BlockPos, net.minecraft.world.level.block.state.BlockState> before = new java.util.LinkedHashMap<>();
                for (BlockPos top : tops) {
                    before.putIfAbsent(top.below(), level.getBlockState(top.below()));
                    before.putIfAbsent(top, level.getBlockState(top));
                }
                for (BlockPos top : tops) {
                    level.setBlockAndUpdate(top.below(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState());
                    level.setBlockAndUpdate(top, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
                }
                var found = Givers.dryColumn(level, wet.getX(), wet.getZ());
                boolean moved = Math.max(Math.abs(found.getX() - wet.getX()), Math.abs(found.getZ() - wet.getZ())) >= 2;
                boolean isDry = level.getFluidState(found.below()).isEmpty() && level.getFluidState(found).isEmpty();
                check("placement: a wet column resolves to nearby dry ground (moved " + moved + ", dry " + isDry + ")", moved && isDry);
                before.forEach(level::setBlockAndUpdate);
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
                // No Rewards.decorate() ran for this synthetic bed, so Rewards.SPAWNED holds nothing for
                // it -- exactly a server restart between the real spawn and this death. own_kill's fallback
                // must still find the beat's own on_enter spawn (by tag) and use that instead.
                // (lastSpawned is reset to 0 at the start of every grant, not cumulative -- ==1, not a before/after delta.)
                QuestEngine.onUnownedDeath(solo, bed); bed.discard();
                check("own_kill: a self-destructed boss does not count", log.entry(hc) != null && log.entry(hc).stage == 2 && log.entry(hc).progress.get(0) == 0);
                check("own_kill: a fresh one is spawned even with no live record of the first (a restart survived)", Rewards.lastSpawned() == 1);
                // A mutation (ZombieMod: a Walker turning Runner) never fires LivingDeathEvent at all --
                // Mutations.mutate() replaces the mob with Entity#discard(), which is a silent removal,
                // and copies only persistent-data NBT across, never Chronicler's vanilla scoreboard tags.
                // QuestEvents.onLeave is the only thing that ever sees this: EntityLeaveLevelEvent with
                // DISCARDED, on the OLD (still-tagged) entity, before it disappears for good.
                Zombie mutated = new Zombie(level); mutated.snapTo(spawn.getX(), spawn.getY(), spawn.getZ(), 0F, 0F);
                mutated.addTag(Trackers.TAG_PREFIX + "first_bed"); mutated.addTag(Rewards.FOR_PREFIX + solo.getUUID());
                mutated.discard();
                net.neoforged.neoforge.common.NeoForge.EVENT_BUS.post(new net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent(mutated, level));
                check("own_kill: a mutation (discard, no death event) still respawns", log.entry(hc) != null && log.entry(hc).stage == 2 && Rewards.lastSpawned() == 1);
                bed = new Zombie(level); bed.snapTo(spawn.getX(), spawn.getY(), spawn.getZ(), 0F, 0F);
                bed.addTag(Trackers.TAG_PREFIX + "first_bed"); bed.addTag(Rewards.FOR_PREFIX + solo.getUUID());
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

    /**
     * Mini quests (2026-09-16): templates with holes, filled where they start -- the loader's stand-in
     * check, a fetch to a placed person, an escort that follows and arrives and offers the next errand,
     * the jammed door, a tagged kill, a wild offer that lapses, and the refusals.
     */
    private static void minis(MinecraftServer server, FakePlayer solo) {
        var level = server.overworld();
        QuestLog log = QuestEngine.journal(solo);
        log.clear();
        var cast = Npcs.provider().orElse(null);
        Identifier errand = ChroniclerIds.of("mini_village_errand");
        Identifier scholarQuest = ChroniclerIds.of("mini_archaeologist");
        Identifier door = ChroniclerIds.of("mini_jammed_door");
        Identifier pests = ChroniclerIds.of("mini_pests");
        Identifier hall = ChroniclerIds.of("mini_town_hall");
        Identifier satchel = ChroniclerIds.of("mini_lost_satchel");

        // --- the holes, loader-light ---
        var mini = QuestEngine.quest(server, errand).flatMap(h -> h.value().mini()).orElse(null);
        check("minis: the village errand loads as a template, with its file kept", mini != null
                && QuestEngine.quest(server, errand).flatMap(h -> h.value().template()).isPresent());
        if (mini == null) return;
        var json = com.google.gson.JsonParser.parseString("{\"n\": \"{count}\", \"t\": \"Bring {count} {wants.name}\", \"p\": \"{asker.pos}\", \"c\": \"{player}\", \"mini\": {\"k\": \"{count}\"}}");
        var filled = com.sablednah.chronicler.data.Slots.fill(json, java.util.Map.of("count", "4", "wants.name", "Bread", "asker.pos", "1 2 3"), mini).getAsJsonObject();
        check("slots: a whole-string hole becomes a number", filled.get("n").isJsonPrimitive() && filled.get("n").getAsJsonPrimitive().isNumber() && filled.get("n").getAsInt() == 4);
        check("slots: a hole in text is text", filled.get("t").getAsString().equals("Bring 4 Bread"));
        check("slots: .pos becomes a list", filled.get("p").isJsonArray() && filled.get("p").getAsJsonArray().size() == 3);
        check("slots: {player} is the command's, left alone", filled.get("c").getAsString().equals("{player}"));
        check("slots: the mini block is left as written", filled.getAsJsonObject("mini").get("k").getAsString().equals("{count}"));
        check("minis: every built-in template decodes filled with its stand-ins", Minis.templates(server).size() >= 6);
        // A template with a mistake is refused at load, not when someone starts it.
        var bad = com.google.gson.JsonParser.parseString("{\"name\": \"x\", \"chapter\": \"errands\", \"objectives\": [{\"type\": \"kill\", \"count\": \"{who}\"}],"
                + "\"mini\": {\"slots\": {\"who\": {\"type\": \"pick\", \"pool\": [\"Bob\"]}}}}");
        check("minis: a hole that cannot be a number where one is wanted refuses the file", com.sablednah.chronicler.data.Quest.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, bad).error().isPresent());
        var badType = com.google.gson.JsonParser.parseString("{\"name\": \"x\", \"chapter\": \"errands\", \"mini\": {\"slots\": {\"who\": {\"type\": \"wizard\"}}}}");
        check("minis: an unknown slot type refuses the file", com.sablednah.chronicler.data.Quest.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, badType).error().isPresent());
        var reserved = com.google.gson.JsonParser.parseString("{\"name\": \"x\", \"chapter\": \"errands\", \"mini\": {\"slots\": {\"player\": {\"type\": \"here\"}}}}");
        check("minis: a slot may not be called {player}", com.sablednah.chronicler.data.Quest.CODEC.parse(com.mojang.serialization.JsonOps.INSTANCE, reserved).error().isPresent());
        if (cast == null) {
            check("minis: Cast is there to place people (it is required)", false);
            return;
        }
        int npcsBefore = GiverStore.get(server).minis().size();

        // --- a fetch: someone placed, the quest filled in, the hand-over to them by id ---
        var started = Minis.start(solo, errand, java.util.Map.of("wants", "minecraft:bread", "count", "4"), Minis.Anchor.of(solo), false);
        check("fetch: started (" + started.orElse("") + ")", started.isEmpty() && log.isActive(errand));
        QuestLog.Entry fe = log.entry(errand);
        java.util.UUID asker = fe == null ? null : java.util.UUID.fromString(fe.slots.getOrDefault("asker.id", Slots_NIL));
        check("fetch: the asker stands in the world, working for this quest", asker != null && cast.byId(server, asker).isPresent()
                && Minis.activeQuestOf(server, asker).map(errand::equals).orElse(false));
        var filledQuest = QuestEngine.questFor(solo, errand).orElseThrow();
        check("fetch: the name is filled in (" + filledQuest.name() + ")", filledQuest.name().startsWith("Bread for "));
        check("fetch: the target count came from the slot", fe != null && fe.targets.equals(List.of(4)));
        check("fetch: the objective names the asker (" + filledQuest.objectivesAt(0).getFirst().describe() + ")",
                filledQuest.objectivesAt(0).getFirst().describe().contains(filledQuest.name().substring("Bread for ".length()).replaceAll("&.", "")));
        check("fetch: the same filled quest comes back (objectives found by identity)", QuestEngine.questFor(solo, errand).orElseThrow() == filledQuest);
        check("fetch: a second start is refused while it runs", Minis.start(solo, errand, java.util.Map.of(), Minis.Anchor.of(solo), false).isPresent());
        solo.getInventory().add(new ItemStack(Items.BREAD, 4));
        check("fetch: a click on somebody else does not take it", !QuestEngine.onDeliver(solo, d -> d.npc().map(java.util.UUID.randomUUID().toString()::equals).orElse(false)) && log.isActive(errand));
        check("fetch: a click on the asker hands it over", asker != null && Givers.onUseNpc(solo, asker) && log.isComplete(errand));
        check("fetch: the bread is gone", Trackers.count(solo, Identifier.parse("minecraft:bread")) == 0);
        check("fetch: the asker has left, and the store forgot them", asker != null && cast.byId(server, asker).isEmpty()
                && GiverStore.get(server).minis().size() == npcsBefore);
        // The saved journal keeps the slots.
        var saved = QuestLog.MAP_CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, log).getOrThrow();
        QuestLog again = new QuestLog();
        again.start(errand, List.of(1), java.util.Map.of("wants", "minecraft:apple"));
        var round = QuestLog.MAP_CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                QuestLog.MAP_CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, again).getOrThrow()).getOrThrow();
        check("journal: slots survive a save", saved != null && round.entry(errand) != null && "minecraft:apple".equals(round.entry(errand).slots.get("wants")));

        // --- refusals: nothing to find, nobody left behind ---
        int before = GiverStore.get(server).minis().size();
        var noCity = Minis.start(solo, hall, java.util.Map.of(), Minis.Anchor.of(solo), false);
        check("refusal: a city errand where there is no city says so (" + noCity.orElse("") + ")", noCity.isPresent() && !log.isActive(hall));
        check("refusal: the person placed before the search failed was taken away again", GiverStore.get(server).minis().size() == before);
        check("refusal: a quest that is not a mini quest is refused", Minis.start(solo, ChroniclerIds.of("first_steps"), java.util.Map.of(), Minis.Anchor.of(solo), false).isPresent());

        // --- an escort: follows, arrives only with the player, and offers the next errand ---
        var spawnAt = solo.blockPosition();
        var villageAt = spawnAt.offset(200, 0, 0); // well outside the template's 64-block "at the village"
        var escort = Minis.start(solo, scholarQuest, java.util.Map.of("village", villageAt.getX() + " " + villageAt.getY() + " " + villageAt.getZ()), Minis.Anchor.of(solo), false);
        check("escort: started with the village handed over (" + escort.orElse("") + ")", escort.isEmpty() && log.isActive(scholarQuest));
        QuestLog.Entry ee = log.entry(scholarQuest);
        java.util.UUID scholar = ee == null ? null : java.util.UUID.fromString(ee.slots.getOrDefault("scholar.id", Slots_NIL));
        if (scholar != null && cast.byId(server, scholar).isPresent()) {
            QuestEngine.poll(solo);
            check("escort: the professor follows the player", cast.leaderOf(scholar).map(solo.getUUID()::equals).orElse(false));
            Vec3 village = Vec3.atBottomCenterOf(villageAt);
            cast.teleport(server, scholar, village);
            solo.snapTo(village.x + 40, village.y, village.z, 0F, 0F); // inside the village, too far from the professor
            QuestEngine.poll(solo);
            check("escort: at the village without the player is not there yet", log.isActive(scholarQuest) && log.entry(scholarQuest).progress.get(0) == 0);
            solo.snapTo(village.x + 3, village.y, village.z, 0F, 0F);
            QuestEngine.poll(solo);
            check("escort: at the village with the player, done", log.isComplete(scholarQuest));
            check("escort: the professor has gone into the village", cast.byId(server, scholar).isEmpty());
            var next = Minis.pendingValues(solo, errand);
            check("chain: the next errand is offered where this one ended", next.isPresent()
                    && next.get().containsKey("asker.id") && Math.abs(Integer.parseInt(next.get().getOrDefault("asker.x", "0")) - villageAt.getX()) <= 16);
            if (next.isPresent()) {
                java.util.UUID nextAsker = java.util.UUID.fromString(next.get().get("asker.id"));
                check("chain: accepting takes up that offer and that person", QuestEngine.accept(solo, errand).isEmpty()
                        && log.entry(errand) != null && nextAsker.toString().equals(log.entry(errand).slots.get("asker.id")));
                check("chain: abandoning sends them away", QuestEngine.abandon(solo, errand) && cast.byId(server, nextAsker).isEmpty());
            }
        } else {
            check("escort: the professor was placed", false);
            QuestEngine.abandon(solo, scholarQuest);
        }
        solo.snapTo(spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5, 0F, 0F);

        // --- the jammed door: a found block takes the delivery and will not open until then ---
        var doorAt = Givers.dryColumn(level, spawnAt.getX() + 3, spawnAt.getZ() + 3);
        var doorState = net.minecraft.world.level.block.Blocks.OAK_DOOR.defaultBlockState();
        level.setBlockAndUpdate(doorAt, doorState);
        level.setBlockAndUpdate(doorAt.above(), doorState.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.DOUBLE_BLOCK_HALF,
                net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER));
        try {
            var jam = Minis.start(solo, door, java.util.Map.of("count", "2"), Minis.Anchor.of(solo), false);
            QuestLog.Entry de = log.entry(door);
            check("door: started, and it found this door (" + jam.orElse("") + ")", jam.isEmpty() && de != null
                    && (doorAt.getX() + " " + doorAt.getY() + " " + doorAt.getZ()).equals(de.slots.get("door.pos")));
            check("door: using it with nothing in hand is refused, and it stays shut",
                    Givers.onUseBlock(solo, level, doorAt) && log.isActive(door) && !level.getBlockState(doorAt).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN));
            solo.getInventory().add(new ItemStack(Items.SLIME_BALL, 2));
            check("door: with the slime balls it gives way", Givers.onUseBlock(solo, level, doorAt) && log.isComplete(door));
            check("door: open, both halves", level.getBlockState(doorAt).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)
                    && level.getBlockState(doorAt.above()).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN));
        } finally {
            level.setBlockAndUpdate(doorAt.above(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(doorAt, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
            if (log.isActive(door)) QuestEngine.abandon(solo, door);
        }

        // --- a kill from slots: the pests spawn tagged, the tagged ones count ---
        var pest = Minis.start(solo, pests, java.util.Map.of("pest", "minecraft:zombie", "count", "3"), Minis.Anchor.of(solo), false);
        check("pests: started, three zombies put out (" + pest.orElse("") + ", " + Rewards.lastSpawned() + ")", pest.isEmpty() && Rewards.lastSpawned() == 3);
        for (int n = 0; n < 3; n++) {
            Zombie z = new Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
            z.addTag(Trackers.TAG_PREFIX + "pests");
            QuestEngine.onKill(solo, z);
            z.discard();
        }
        check("pests: three kills finish it", log.isComplete(pests));

        // --- around: a spot the right distance from the one who asked ---
        var s = Minis.start(solo, satchel, java.util.Map.of(), Minis.Anchor.of(solo), false);
        QuestLog.Entry se = log.entry(satchel);
        if (s.isEmpty() && se != null) {
            double d = Math.hypot(Integer.parseInt(se.slots.get("spot.x")) - Integer.parseInt(se.slots.get("asker.x")),
                    Integer.parseInt(se.slots.get("spot.z")) - Integer.parseInt(se.slots.get("asker.z")));
            check("around: the spot is 40-90 blocks from the asker, give or take the dry ground (" + (int) d + ")", d >= 30 && d <= 100);
            QuestEngine.abandon(solo, satchel);
        } else {
            check("around: the satchel errand started (" + s.orElse("") + ")", false);
        }

        // --- schematics: a CityWorld lot by the name of what was pasted there ---
        check("schematic: 'Chayats Bank' is chayats-bank", Places.schematicMatches(List.of("Lowrise", "ClipboardLot", "chayats-bank", "schematic=chayats-bank"), "Chayats Bank"));
        check("schematic: the Winchester is not the bank", !Places.schematicMatches(List.of("schematic=chayats-bank"), "winchester"));
        check("schematic: a lot word naming a schematic is not the schematic", !Places.schematicMatches(List.of("chayats-bank"), "chayats-bank"));
        check("lot: HouseLot is a house, and not a warehouse", Places.lotMatches(List.of("FloodedHouseLot"), "HouseLot") && !Places.lotMatches(List.of("WarehouseBuildingLot"), "HouseLot"));
        var noBank = Minis.start(solo, ChroniclerIds.of("mini_bank_run"), java.util.Map.of(), Minis.Anchor.of(solo), false);
        check("schematic: a bank run with no city to find the bank in is refused (" + noBank.orElse("") + ")", noBank.isPresent());

        // --- a kill of one tagged mob and nothing else (Phil) ---
        var philOnly = new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of(), 1, java.util.Optional.of("phil"), java.util.Optional.empty(), false);
        Zombie notPhil = new Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
        Zombie phil = new Zombie(net.minecraft.world.entity.EntityType.ZOMBIE, level);
        phil.addTag(Trackers.TAG_PREFIX + "phil");
        check("kill: a tag with no target counts only the tagged one", Trackers.of(philOnly).countsKill(solo, phil, philOnly) && !Trackers.of(philOnly).countsKill(solo, notPhil, philOnly));
        check("kill: neither tag nor target still counts anything", Trackers.of((com.sablednah.chronicler.data.ObjectiveSpec) new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of(), 1, java.util.Optional.empty(), java.util.Optional.empty(), false))
                .countsKill(solo, notPhil, new com.sablednah.chronicler.data.ObjectiveTypes.Kill(List.of(), 1, java.util.Optional.empty(), java.util.Optional.empty(), false)));
        phil.discard();
        notPhil.discard();

        // --- interior: the Y band keeps a "near a building" search out of a deep cave or off a high
        // roof (Mum, 45m under a house; Phil, on a roof) -- the band math itself, not terrain the
        // self-test cannot control.
        check("interior: the default band sits around sea level", Minis.defaultInteriorMinY(level) < level.getSeaLevel()
                && Minis.defaultInteriorMaxY(level) > level.getSeaLevel());
        BlockPos impossible = Minis.interiorNear(level, spawnAt, 10, level.getSeaLevel() + 400, level.getSeaLevel() + 401);
        check("interior: a band above the world's own height finds nothing and falls back outdoors, not a false match", impossible != null);

        // --- the prisoner: an outpost handed over, the guards, then an escort that fails on its sixth hit ---
        Identifier prisonerQuest = ChroniclerIds.of("mini_prisoner");
        var outpostAt = spawnAt.offset(0, 0, 300);
        var taken = Minis.start(solo, prisonerQuest, java.util.Map.of("outpost", outpostAt.getX() + " " + outpostAt.getY() + " " + outpostAt.getZ()), Minis.Anchor.of(solo), false);
        QuestLog.Entry pe = log.entry(prisonerQuest);
        check("prisoner: started, the prisoner placed near the outpost, not on its own roof (" + taken.orElse("") + ")", taken.isEmpty() && pe != null
                && Math.abs(Integer.parseInt(pe.slots.getOrDefault("prisoner.z", "0")) - outpostAt.getZ()) <= 11);
        if (pe != null) {
            java.util.UUID prisoner = java.util.UUID.fromString(pe.slots.get("prisoner.id"));
            java.util.UUID pAsker = java.util.UUID.fromString(pe.slots.get("asker.id"));
            solo.snapTo(outpostAt.getX() + 0.5, outpostAt.getY(), outpostAt.getZ() + 0.5, 0F, 0F);
            QuestEngine.poll(solo);
            check("prisoner: at the outpost, the guards come", log.entry(prisonerQuest) != null && log.entry(prisonerQuest).stage == 1 && Rewards.lastSpawned() == 3);
            for (int n = 0; n < 3; n++) {
                var guard = new net.minecraft.world.entity.monster.illager.Pillager(net.minecraft.world.entity.EntityType.PILLAGER, level);
                guard.addTag(Trackers.TAG_PREFIX + "outpost_guard");
                QuestEngine.onKill(solo, guard);
                guard.discard();
            }
            check("prisoner: guards down, the escort begins", log.entry(prisonerQuest) != null && log.entry(prisonerQuest).stage == 2);

            // Waypoints: the escort's own "go here" and the prisoner's live spot, for whatever draws a map (JourneyMap, if installed).
            var marks = Waypoints.build(solo).marks();
            var targetMark = marks.stream().filter(m -> m.id().equals(prisonerQuest + ":target")).findFirst().orElse(null);
            var npcMark = marks.stream().filter(m -> m.id().equals(prisoner.toString())).findFirst().orElse(null);
            check("waypoints: the escort's destination is marked (" + marks.stream().map(WaypointsPayload.Mark::id).toList() + ")",
                    targetMark != null && targetMark.kind() == WaypointsPayload.TARGET);
            check("waypoints: the prisoner is marked where they actually are", npcMark != null && npcMark.kind() == WaypointsPayload.NPC
                    && cast.byId(server, prisoner).map(p -> Math.abs(npcMark.x() - p.pos().x) < 1 && Math.abs(npcMark.z() - p.pos().z) < 1).orElse(false));
            check("waypoints: neither label is a raw key or a section sign", targetMark != null && npcMark != null
                    && !targetMark.label().contains("waypoint.") && !targetMark.label().contains("§")
                    && !npcMark.label().contains("waypoint.") && !npcMark.label().contains("§"));
            var wpWire = io.netty.buffer.Unpooled.buffer();
            var wpPayload = Waypoints.build(solo);
            WaypointsPayload.CODEC.encode(wpWire, wpPayload);
            var wpBack = WaypointsPayload.CODEC.decode(wpWire);
            check("waypoints payload survives the wire", wpWire.readableBytes() == 0 && wpBack.marks().size() == wpPayload.marks().size()
                    && wpBack.marks().stream().map(WaypointsPayload.Mark::id).toList().equals(wpPayload.marks().stream().map(WaypointsPayload.Mark::id).toList()));

            QuestEngine.onNpcHit(List.of(solo), pAsker);
            check("hits: a blow on somebody else is not counted", log.entry(prisonerQuest).tallies.isEmpty());
            for (int n = 0; n < 5; n++) QuestEngine.onNpcHit(List.of(solo), prisoner);
            check("hits: five blows are counted, and five is not six", log.isActive(prisonerQuest) && log.entry(prisonerQuest).tallies.getOrDefault("hits:" + prisoner, 0) == 5);
            int barsBefore = EscortBars.activeCount();
            EscortBars.sync(solo);
            check("hits: a bar exists while the escort is in danger (missed the action-bar line has no other way to know)", EscortBars.activeCount() > barsBefore);
            QuestLog saveHits = new QuestLog();
            saveHits.startFrom(prisonerQuest, log.entry(prisonerQuest));
            var reread = QuestLog.MAP_CODEC.codec().parse(com.mojang.serialization.JsonOps.INSTANCE,
                    QuestLog.MAP_CODEC.codec().encodeStart(com.mojang.serialization.JsonOps.INSTANCE, saveHits).getOrThrow()).getOrThrow();
            check("hits: the count survives a save", reread.entry(prisonerQuest) != null && reread.entry(prisonerQuest).tallies.getOrDefault("hits:" + prisoner, 0) == 5);
            QuestEngine.onNpcHit(List.of(solo), prisoner);
            check("hits: the sixth fails the escort", !log.isActive(prisonerQuest) && !log.isComplete(prisonerQuest));
            check("hits: and everyone placed for it has gone", cast.byId(server, prisoner).isEmpty() && cast.byId(server, pAsker).isEmpty());
            EscortBars.sync(solo);
            check("hits: the bar goes with the failed escort", EscortBars.activeCount() == barsBefore);
        }
        solo.snapTo(spawnAt.getX() + 0.5, spawnAt.getY(), spawnAt.getZ() + 0.5, 0F, 0F);

        // --- the wild: an offer made, standing, and lapsing with its person ---
        int standing = Minis.pendingCount();
        Minis.tickWild(solo); // spawn is not a village: nothing rolls
        check("wild: nothing is found outside the place it lives", Minis.pendingCount() == standing);
        var base = QuestEngine.quest(server, errand).orElseThrow().value();
        var wild = Minis.offerWild(solo, errand, base);
        var offered = Minis.pendingValues(solo, errand);
        check("wild: an offer stands, with its person (" + wild.orElse("") + ")", wild.isEmpty() && offered.isPresent()
                && cast.byId(server, java.util.UUID.fromString(offered.get().get("asker.id"))).isPresent());
        check("wild: the offer shows the filled-in quest", QuestEngine.questFor(solo, errand).map(q -> !q.name().equals(base.name()) || offered.get().get("wants").equals("minecraft:bread")).orElse(false));
        offered.ifPresent(o -> {
            java.util.UUID wildAsker = java.util.UUID.fromString(o.get("asker.id"));
            Minis.lapseAll(server);
            check("wild: a lapsed offer takes its person away", cast.byId(server, wildAsker).isEmpty() && Minis.pendingCount() == 0);
        });

        check("api: the mini quests are listed", com.sablednah.chronicler.api.Quests.minis(server).contains(errand)
                && com.sablednah.chronicler.api.Quests.isMini(solo, errand) && !com.sablednah.chronicler.api.Quests.isMini(solo, ChroniclerIds.of("first_steps")));
        CommandSourceStack console = server.createCommandSourceStack();
        command(server, console, "quest mini", true);
        command(server, console, "quest mini chronicler:mini_village_errand", false); // the console stands nowhere
        command(server, console, "quest mini no_such_quest", false);
        check("minis: nobody placed by the tests is left standing", GiverStore.get(server).minis().size() == npcsBefore);
        log.clear();
    }

    private static final String Slots_NIL = com.sablednah.chronicler.data.Slots.NIL_UUID;

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

    /** Every string in a panel payload, in order: for the no-raw-keys sweep and the round trip. */
    private static List<String> panelText(JournalPayload p) {
        List<String> out = new ArrayList<>();
        out.add(p.title().getString());
        out.add(p.progress().getString());
        p.labels().values().forEach(v -> out.add(v.getString())); // values only: the keys are named panel.* on purpose
        for (var c : p.chapters()) {
            out.add(c.id() + c.name().getString() + c.icon());
            c.lines().forEach(l -> out.add(l.getString()));
            c.buttons().forEach(b -> out.add(b.label().getString() + b.tip().getString() + b.command()));
            for (var q : c.quests()) {
                out.add(q.id() + q.name().getString() + q.status() + q.icon() + q.tracked() + q.requires());
                q.lines().forEach(l -> out.add(l.getString()));
                q.buttons().forEach(b -> out.add(b.label().getString() + b.tip().getString() + b.command()));
            }
            for (var e : c.externals()) out.add(e.id() + e.name().getString() + e.chapter().getString() + e.status() + e.icon());
        }
        return out;
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
