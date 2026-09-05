package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.mojang.brigadier.ParseResults;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.ChroniclerIds;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;

/**
 * Headless checks, run on {@code ServerStartedEvent} when
 * {@code ./gradlew runServer -Pselftest}. Gradle cannot pipe stdin to a dev
 * server console, so this is the only headless route to "does the command
 * actually work". Rules, all learned next door:
 *
 * <ul>
 * <li><b>Parse AND execute.</b> {@code parse} alone proves nothing.</li>
 * <li><b>Both directions.</b> A tree that matches anything passes every
 *     positive assertion, so a deliberately bad command must fail.</li>
 * <li><b>Call the real code.</b> A probe that re-derives the logic tests the
 *     duplicate.</li>
 * <li><b>Print the failures, not a count.</b> A misread count shipped a
 *     broken registry once.</li>
 * </ul>
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
        check("built-in chapter loaded", chapters.get(
                net.minecraft.resources.ResourceKey.create(ChroniclerRegistries.CHAPTER, ChroniclerIds.of("prologue"))).isPresent());
        check("built-in quests loaded", quests.size() >= 2);
        check("every quest names a real chapter", quests.listElements().allMatch(h -> chapters.get(
                net.minecraft.resources.ResourceKey.create(ChroniclerRegistries.CHAPTER, h.value().chapter())).isPresent()));
        check("every requires names a real quest", quests.listElements().allMatch(h -> h.value().requires().stream()
                .allMatch(r -> quests.get(net.minecraft.resources.ResourceKey.create(ChroniclerRegistries.QUEST, r)).isPresent())));
        check("objectives describe themselves", quests.listElements().allMatch(h ->
                h.value().objectives().stream().allMatch(o -> !o.describe().isBlank() && !o.describe().startsWith("obj."))));

        check("lang catalogue is non-trivial", Lang.catalogueSize() > 30);
        check("lang resolves terms", Lang.get("cmd.list.header").contains(Lang.term("quests")));
        check("unknown lang key returns the key", Lang.get("no.such.key").equals("no.such.key"));
        check("pretty() title-cases", Lang.pretty("minecraft:rotten_flesh").equals("Rotten Flesh"));
        check("colored() styles without section signs",
                !Feedback.colored("&6Hi &fthere").getString().contains("§")
                        && Feedback.colored("Tom & Jerry").getString().equals("Tom & Jerry"));

        QuestLog log = new QuestLog();
        log.start(ChroniclerIds.of("x"), 2);
        log.progress(ChroniclerIds.of("x")).set(0, 3);
        check("journal counters are live", log.progress(ChroniclerIds.of("x")).get(0) == 3);
        log.complete(ChroniclerIds.of("x"));
        check("journal completion counts", log.isComplete(ChroniclerIds.of("x")) && !log.isActive(ChroniclerIds.of("x")));

        CommandSourceStack source = server.createCommandSourceStack();
        command(server, source, "quest list", true);
        command(server, source, "quests list", true);
        command(server, source, "quest info first_steps", true);
        command(server, source, "quest info chronicler:first_steps", true);
        command(server, source, "chronicler status", true);
        command(server, source, "quest info no_such_quest", false);
        command(server, source, "quest sideways", false);

        Chronicler.LOGGER.info("=== Chronicler SelfTest: {} PASSED, {} FAILED ===", passed, FAILURES.size());
        for (String f : FAILURES) {
            Chronicler.LOGGER.error("  FAILED: {}", f);
        }
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
