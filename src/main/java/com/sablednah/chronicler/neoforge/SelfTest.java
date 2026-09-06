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

    private static FakePlayer fake(MinecraftServer server, String name) {
        return new FakePlayer(server.overworld(),
                new GameProfile(UUID.nameUUIDFromBytes(("chronicler:" + name).getBytes()), name));
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
