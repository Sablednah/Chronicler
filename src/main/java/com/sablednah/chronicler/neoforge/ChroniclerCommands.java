package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.Chapter;
import com.sablednah.chronicler.data.ObjectiveTypes;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardTypes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;

/**
 * The command surface. {@code /quest} for players, {@code /chronicler} for
 * administering the mod itself and nothing else.
 *
 * <p>{@code /quests} is a second literal built from the same tree, not a
 * Brigadier redirect: a redirect node's requirement is ANDed with every child
 * and a redirect ignores children merged into it later -- both bit sibling
 * mods. Each subcommand carries its own bar; the roots carry none, so nothing
 * a player is meant to see is ever behind an op check by accident.</p>
 */
public final class ChroniclerCommands {

    private static final DynamicCommandExceptionType ERROR_UNKNOWN_QUEST =
            new DynamicCommandExceptionType(id -> Feedback.colored(Lang.fmt("cmd.unknown_quest", "id", id)));
    private static final DynamicCommandExceptionType ERROR_AMBIGUOUS =
            new DynamicCommandExceptionType(ids -> Feedback.colored(Lang.fmt("cmd.ambiguous", "ids", ids)));

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(questTree("quest"));
        dispatcher.register(questTree("quests"));

        dispatcher.register(Commands.literal("chronicler")
                .then(Commands.literal("reload")
                        .requires(ChroniclerPermissions::isAdmin)
                        .executes(ChroniclerCommands::reload))
                .then(Commands.literal("status")
                        .requires(ChroniclerPermissions::isAdmin)
                        .executes(ChroniclerCommands::status)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> questTree(String root) {
        return Commands.literal(root)
                .then(Commands.literal("list").executes(ChroniclerCommands::list))
                .then(Commands.literal("log").executes(ChroniclerCommands::log))
                .then(Commands.literal("info")
                        .then(Commands.argument("quest", ResourceKeyArgument.key(ChroniclerRegistries.QUEST))
                                .suggests(ChroniclerCommands::suggestQuests)
                                .executes(ChroniclerCommands::info)));
    }

    // --- resolution: accept the bare name, report ambiguity, never guess ---

    /**
     * {@code first_steps} instead of {@code chronicler:first_steps}. An id with
     * no namespace parses as {@code minecraft:<path>} -- the signal it was
     * omitted -- so match on path across every namespace. Exact wins; two packs
     * sharing a short name is reported. (ZombieMod's resolver, via LegendQuest.)
     */
    private static Holder.Reference<Quest> resolveQuest(CommandContext<CommandSourceStack> ctx)
            throws CommandSyntaxException {
        ResourceKey<Quest> typed = ResourceKeyArgument.getRegistryKey(ctx, "quest",
                ChroniclerRegistries.QUEST, ERROR_UNKNOWN_QUEST);
        Registry<Quest> quests = ctx.getSource().registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        var exact = quests.get(typed);
        if (exact.isPresent()) return exact.get();

        Identifier id = typed.identifier();
        if (!id.getNamespace().equals(Identifier.DEFAULT_NAMESPACE)) {
            throw ERROR_UNKNOWN_QUEST.create(id);
        }
        var byPath = quests.listElements()
                .filter(h -> h.key().identifier().getPath().equals(id.getPath()))
                .toList();
        return switch (byPath.size()) {
            case 1 -> byPath.getFirst();
            case 0 -> throw ERROR_UNKNOWN_QUEST.create(id.getPath());
            default -> throw ERROR_AMBIGUOUS.create(byPath.stream()
                    .map(h -> h.key().identifier().toString()).collect(Collectors.joining(", ")));
        };
    }

    private static java.util.concurrent.CompletableFuture<Suggestions> suggestQuests(
            CommandContext<CommandSourceStack> ctx, SuggestionsBuilder builder) {
        Registry<Quest> quests = ctx.getSource().registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        Map<String, Integer> pathCounts = new java.util.HashMap<>();
        List<Identifier> all = quests.keySet().stream().toList();
        all.forEach(id -> pathCounts.merge(id.getPath(), 1, Integer::sum));
        List<String> out = new ArrayList<>();
        for (Identifier id : all) {
            out.add(pathCounts.get(id.getPath()) == 1 ? id.getPath() : id.toString());
        }
        return SharedSuggestionProvider.suggest(out, builder);
    }

    // --- /quest list ---

    private static int list(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Registry<Chapter> chapters = source.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
        Registry<Quest> quests = source.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        boolean seeHidden = com.sablednah.chronicler.ChroniclerConfig.SHOW_HIDDEN_TO_OPS.get()
                && ChroniclerPermissions.isAdmin(source);
        QuestLog log = source.getEntity() instanceof ServerPlayer p ? journal(p) : null;

        if (quests.size() == 0) {
            source.sendSuccess(() -> Feedback.colored(Lang.get("cmd.list.empty")), false);
            return 0;
        }

        // Group by chapter, chapters by order then name, quests by order then name.
        Map<Identifier, List<Holder.Reference<Quest>>> byChapter = new TreeMap<>(Comparator
                .comparingInt((Identifier c) -> chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, c))
                        .map(h -> h.value().order()).orElse(Integer.MAX_VALUE))
                .thenComparing(Identifier::toString));
        quests.listElements().forEach(h -> byChapter
                .computeIfAbsent(h.value().chapter(), k -> new ArrayList<>()).add(h));

        List<String> lines = new ArrayList<>();
        lines.add(Lang.get("cmd.list.header"));
        int shown = 0;
        for (var entry : byChapter.entrySet()) {
            var chapter = chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, entry.getKey()));
            lines.add(chapter.map(h -> Lang.fmt("cmd.list.chapter",
                            "name", h.value().name(),
                            "description", h.value().description().orElse("")))
                    .orElseGet(() -> Lang.fmt("cmd.list.orphan_chapter", "id", entry.getKey())));
            entry.getValue().sort(Comparator.comparingInt((Holder.Reference<Quest> h) -> h.value().order())
                    .thenComparing(h -> h.key().identifier().toString()));
            for (var h : entry.getValue()) {
                Quest q = h.value();
                if (q.hidden() && !seeHidden) continue;
                Identifier id = h.key().identifier();
                String status = statusOf(q, id, log);
                if (q.hidden()) status = Lang.get("status.hidden") + " " + status;
                if (q.repeatable()) status = status + " " + Lang.get("status.repeatable");
                lines.add(Lang.fmt("cmd.list.quest", "name", q.name(), "id", shortId(id, quests), "status", status));
                shown++;
            }
        }
        String joined = String.join("\n", lines);
        source.sendSuccess(() -> Feedback.colored(joined), false);
        return shown;
    }

    private static String statusOf(Quest q, Identifier id, QuestLog log) {
        if (log == null) return Lang.get("status.available");
        if (log.isActive(id)) return Lang.get("status.active");
        if (log.isComplete(id) && !q.repeatable()) return Lang.get("status.complete");
        boolean locked = q.requires().stream().anyMatch(r -> !log.isComplete(r));
        return locked ? Lang.get("status.locked") : Lang.get("status.available");
    }

    /** Bare path when unambiguous across namespaces, else the full id. */
    private static String shortId(Identifier id, Registry<Quest> quests) {
        long sharing = quests.keySet().stream().filter(o -> o.getPath().equals(id.getPath())).count();
        return sharing == 1 ? id.getPath() : id.toString();
    }

    // --- /quest info <quest> ---

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        Holder.Reference<Quest> holder = resolveQuest(ctx);
        Quest q = holder.value();
        Identifier id = holder.key().identifier();
        Registry<Chapter> chapters = source.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
        Registry<Quest> quests = source.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);

        List<String> lines = new ArrayList<>();
        lines.add(Lang.fmt("cmd.info.header", "name", q.name(), "id", id));
        String chapterName = chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, q.chapter()))
                .map(h -> h.value().name()).orElse(q.chapter().toString());
        lines.add(Lang.fmt("cmd.info.chapter", "chapter", chapterName));
        q.description().ifPresent(d -> lines.add(Lang.fmt("cmd.info.description", "description", d)));
        if (!q.requires().isEmpty()) {
            lines.add(Lang.fmt("cmd.info.requires", "list", q.requires().stream()
                    .map(r -> quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, r))
                            .map(h -> h.value().name()).orElse(r.toString()))
                    .collect(Collectors.joining(", "))));
        }
        lines.add(Lang.get("cmd.info.objectives"));
        if (q.objectives().isEmpty()) lines.add(Lang.get("cmd.info.none"));
        q.objectives().forEach(o -> lines.add(Lang.fmt("cmd.info.objective", "line", o.describe())));
        lines.add(Lang.get("cmd.info.rewards"));
        if (q.rewards().isEmpty()) lines.add(Lang.get("cmd.info.none"));
        q.rewards().forEach(r -> lines.add(Lang.fmt("cmd.info.reward", "line", r.describe())));

        String joined = String.join("\n", lines);
        source.sendSuccess(() -> Feedback.colored(joined), false);
        return 1;
    }

    // --- /quest log ---

    private static int log(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        QuestLog log = journal(player);
        if (log.activeCount() == 0 && log.completedCount() == 0) {
            Feedback.chat(player, Lang.get("cmd.log.empty"));
            return 0;
        }
        Feedback.chat(player, Lang.fmt("cmd.log.header",
                "active", log.activeCount(), "completed", log.completedCount()));
        return log.activeCount() + log.completedCount();
    }

    public static QuestLog journal(ServerPlayer player) {
        return player.getData(ChroniclerAttachments.JOURNAL);
    }

    // --- /chronicler ---

    private static int reload(CommandContext<CommandSourceStack> ctx) {
        Lang.load();
        ctx.getSource().sendSuccess(() -> Feedback.colored(Lang.get("cmd.reload_notice")), true);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        Registry<Chapter> chapters = source.registryAccess().lookupOrThrow(ChroniclerRegistries.CHAPTER);
        Registry<Quest> quests = source.registryAccess().lookupOrThrow(ChroniclerRegistries.QUEST);
        List<String> siblings = new ArrayList<>();
        for (String id : List.of("legendquest", "standards", "zombiemod", "cityworld")) {
            if (ModList.get().isLoaded(id)) siblings.add(id);
        }
        String lines = String.join("\n",
                Lang.fmt("cmd.status", "chapters", chapters.size(), "quests", quests.size(),
                        "objectives", ObjectiveTypes.TYPES.size(), "rewards", RewardTypes.TYPES.size()),
                // The path exists to be COPIED, which is why it must never carry a section code.
                Lang.fmt("cmd.status.config", "path", FMLPaths.CONFIGDIR.get().resolve(Chronicler.MODID).toAbsolutePath()),
                Lang.fmt("cmd.status.siblings", "list", siblings.isEmpty() ? "none" : String.join(", ", siblings)));
        source.sendSuccess(() -> Feedback.colored(lines), false);
        return quests.size();
    }

    private ChroniclerCommands() {}
}
