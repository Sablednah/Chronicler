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
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.Chapter;
import com.sablednah.chronicler.data.ObjectiveTypes;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardTypes;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceKeyArgument;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * and a redirect ignores children merged into it later. Each subcommand
 * carries its own bar; the roots carry none.</p>
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
                        .executes(ChroniclerCommands::status))
                .then(Commands.literal("journal")
                        .requires(ChroniclerPermissions::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ChroniclerCommands::journalGiveTo)))
                .then(Commands.literal("flag")
                        .requires(ChroniclerPermissions::isAdmin)
                        .then(Commands.literal("list").executes(ChroniclerCommands::flagList))
                        .then(Commands.literal("set")
                                .then(Commands.argument("flag", com.mojang.brigadier.arguments.StringArgumentType.string())
                                        .executes(ctx -> flagSet(ctx, true))
                                        .then(Commands.argument("value", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                                .executes(ctx -> flagSet(ctx, com.mojang.brigadier.arguments.BoolArgumentType.getBool(ctx, "value")))))))
                .then(Commands.literal("reset")
                        .requires(ChroniclerPermissions::isAdmin)
                        .then(Commands.argument("player", EntityArgument.player())
                                .executes(ChroniclerCommands::reset))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> questTree(String root) {
        return Commands.literal(root)
                .then(Commands.literal("list").executes(ChroniclerCommands::list))
                .then(Commands.literal("log").executes(ChroniclerCommands::log))
                .then(Commands.literal("info").then(questArg().executes(ChroniclerCommands::info)))
                .then(Commands.literal("accept").then(questArg().executes(ChroniclerCommands::accept)))
                .then(Commands.literal("abandon").then(questArg().executes(ChroniclerCommands::abandon)))
                .then(Commands.literal("track").then(questArg().executes(ChroniclerCommands::track)))
                .then(Commands.literal("choose")
                        .then(questArg().then(Commands.argument("option", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1))
                                .executes(ChroniclerCommands::choose))))
                .then(Commands.literal("journal")
                        .executes(ChroniclerCommands::journalOpen)
                        .then(Commands.literal("give").executes(ChroniclerCommands::journalGive)))
                .then(Commands.literal("giver")
                        .requires(ChroniclerPermissions::isAdmin)
                        .then(Commands.literal("set").then(questArg().executes(ChroniclerCommands::giverSet)))
                        .then(Commands.literal("remove").executes(ChroniclerCommands::giverRemove))
                        .then(Commands.literal("list").executes(ChroniclerCommands::giverList)));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, ?> questArg() {
        return Commands.argument("quest", ResourceKeyArgument.key(ChroniclerRegistries.QUEST))
                .suggests(ChroniclerCommands::suggestQuests);
    }

    // --- resolution: accept the bare name, report ambiguity, never guess ---

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
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;
        QuestLog log = player == null ? null : QuestEngine.journal(player);

        if (quests.size() == 0) {
            source.sendSuccess(() -> Feedback.colored(Lang.get("cmd.list.empty")), false);
            return 0;
        }

        // Main chapters first, then by order, then name.
        Map<Identifier, List<Holder.Reference<Quest>>> byChapter = new TreeMap<>(Comparator
                .comparing((Identifier c) -> !chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, c))
                        .map(h -> h.value().main()).orElse(false))
                .thenComparingInt(c -> chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, c))
                        .map(h -> h.value().order()).orElse(Integer.MAX_VALUE))
                .thenComparing(Identifier::toString));
        quests.listElements().forEach(h -> byChapter
                .computeIfAbsent(h.value().chapter(), k -> new ArrayList<>()).add(h));

        MutableComponent out = Feedback.colored(Lang.get("cmd.list.header")).copy();
        int shown = 0;
        for (var entry : byChapter.entrySet()) {
            var chapter = chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, entry.getKey()));
            out.append("\n").append(Feedback.colored(chapter.map(h -> Lang.fmt("cmd.list.chapter",
                            "name", h.value().name(),
                            "description", h.value().description().orElse("")))
                    .orElseGet(() -> Lang.fmt("cmd.list.orphan_chapter", "id", entry.getKey()))));
            entry.getValue().sort(Comparator.comparingInt((Holder.Reference<Quest> h) -> h.value().order())
                    .thenComparing(h -> h.key().identifier().toString()));
            for (var h : entry.getValue()) {
                Quest q = h.value();
                if (q.hidden() && !seeHidden) continue;
                Identifier id = h.key().identifier();
                String status = player == null ? statusOf(q, id, log) : statusOf(player, q, id);
                if (q.hidden()) status = Lang.get("status.hidden") + " " + status;
                if (q.repeatable()) status = status + " " + Lang.get("status.repeatable");
                if (QuestEngine.scopeOf(source.getServer(), q) == QuestScope.PARTY) status = status + " " + Lang.get("status.party");
                if (log != null && log.tracked().map(id::equals).orElse(false)) status = status + " " + Lang.get("status.tracked");
                out.append("\n").append(Feedback.colored(Lang.fmt("cmd.list.quest",
                        "name", q.name(), "id", shortId(id, quests), "status", status)));
                if (player != null) {
                    for (Component b : buttonsFor(player, id, q)) out.append(" ").append(b);
                }
                shown++;
            }
        }
        final Component result = out;
        source.sendSuccess(() -> result, false);
        return shown;
    }

    /** The buttons that make sense for this player's state -- never one that would be refused. */
    private static List<Component> buttonsFor(ServerPlayer player, Identifier id, Quest q) {
        QuestLog log = QuestEngine.journal(player);
        List<Component> out = new ArrayList<>();
        if (log.isActive(id)) {
            if (!log.tracked().map(id::equals).orElse(false)) {
                out.add(Feedback.button(Lang.get("button.track"), "/quest track " + id, Lang.get("button.track.tip")));
            }
            out.add(Feedback.button(Lang.get("button.abandon"), "/quest abandon " + id, Lang.get("button.abandon.tip")));
        } else if (QuestEngine.available(player, id, q)) {
            out.add(Feedback.button(Lang.get("button.accept"), "/quest accept " + id, Lang.get("button.accept.tip")));
            out.add(Feedback.button(Lang.get("button.info"), "/quest info " + id, Lang.get("button.info.tip")));
        }
        return out;
    }

    private static String statusOf(Quest q, Identifier id, QuestLog log) {
        if (log == null) return Lang.get("status.available");
        if (log.isActive(id)) return Lang.get("status.active");
        if (log.isComplete(id) && !q.repeatable()) return Lang.get("status.complete");
        boolean locked = q.requires().stream().anyMatch(r -> !log.isComplete(r));
        return locked ? Lang.get("status.locked") : Lang.get("status.available");
    }

    private static String statusOf(ServerPlayer player, Quest q, Identifier id) {
        QuestLog log = QuestEngine.journal(player);
        if (log.isActive(id)) return Lang.get("status.active");
        if (log.isComplete(id) && !q.repeatable()) return Lang.get("status.complete");
        if (q.repeatable() && QuestEngine.cooldownLeft(log, id, q) > 0) {
            return Lang.fmt("status.cooldown", "time", QuestEngine.clock(QuestEngine.cooldownLeft(log, id, q) / 50L));
        }
        return QuestEngine.available(player, id, q) ? Lang.get("status.available") : Lang.get("status.locked");
    }

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
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;
        QuestLog.Entry entry = player == null ? null : QuestEngine.journal(player).entry(id);

        List<String> lines = new ArrayList<>();
        lines.add(Lang.fmt("cmd.info.header", "name", q.name(), "id", id));
        String chapterName = chapters.get(ResourceKey.create(ChroniclerRegistries.CHAPTER, q.chapter()))
                .map(h -> h.value().name()).orElse(q.chapter().toString());
        lines.add(Lang.fmt("cmd.info.chapter", "chapter", chapterName));
        q.description().ifPresent(d -> lines.add(Lang.fmt("cmd.info.description", "description", d)));
        if (QuestEngine.scopeOf(source.getServer(), q) == QuestScope.PARTY) lines.add(Lang.get("cmd.info.scope_party"));
        q.giver().ifPresent(g -> lines.add(Lang.fmt("cmd.info.giver", "where", g.describe())));
        if (player != null) {
            QuestEngine.unmet(player, q).forEach(line -> lines.add(Lang.fmt("cmd.info.needs", "line", line)));
        }
        if (!q.requires().isEmpty()) {
            lines.add(Lang.fmt("cmd.info.requires", "list", q.requires().stream()
                    .map(r -> quests.get(ResourceKey.create(ChroniclerRegistries.QUEST, r))
                            .map(h -> h.value().name()).orElse(r.toString()))
                    .collect(Collectors.joining(", "))));
        }
        lines.add(Lang.get("cmd.info.objectives"));
        List<com.sablednah.chronicler.data.ObjectiveSpec> objectives =
                entry == null ? q.objectivesAt(0) : QuestEngine.currentObjectives(q, entry);
        if (q.beats().size() > 1) {
            lines.add(entry == null
                    ? Lang.fmt("cmd.info.stages", "count", q.beats().size())
                    : Lang.fmt("cmd.info.stage_now", "stage", entry.stage + 1, "stages", q.beats().size()));
        }
        if (objectives.isEmpty()) lines.add(Lang.get("cmd.info.none"));
        for (int n = 0; n < objectives.size(); n++) {
            String desc = objectives.get(n).describe();
            lines.add(entry == null || n >= entry.targets.size()
                    ? Lang.fmt("cmd.info.objective", "line", desc)
                    : Lang.fmt("cmd.info.progress", "line", desc, "done", entry.progress.get(n), "target", entry.targets.get(n)));
        }
        lines.add(Lang.get("cmd.info.rewards"));
        if (q.rewards().isEmpty()) lines.add(Lang.get("cmd.info.none"));
        q.rewards().forEach(r -> lines.add(Lang.fmt("cmd.info.reward", "line", r.describe())));

        MutableComponent out = Feedback.colored(String.join("\n", lines)).copy();
        if (player != null) {
            for (Component b : buttonsFor(player, id, q)) out.append(" ").append(b);
        }
        final Component result = out;
        source.sendSuccess(() -> result, false);
        return 1;
    }

    // --- /quest accept | abandon | track ---

    private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Identifier id = resolveQuest(ctx).key().identifier();
        var refusal = QuestEngine.accept(player, id);
        if (refusal.isEmpty()) return 1;
        if (refusal.get() == QuestEngine.Refusal.CONDITIONS) {
            Feedback.chat(player, Lang.get("msg.refuse.conditions"));
            QuestEngine.quest(player.level().getServer(), id).ifPresent(h ->
                    QuestEngine.unmet(player, h.value()).forEach(line ->
                            Feedback.chat(player, Lang.fmt("msg.refuse.condition_line", "line", line))));
            return 0;
        }
        if (refusal.get() == QuestEngine.Refusal.COOLDOWN) {
            long left = QuestEngine.quest(player.level().getServer(), id)
                    .map(h -> QuestEngine.cooldownLeft(QuestEngine.journal(player), id, h.value())).orElse(0L);
            Feedback.chat(player, Lang.fmt("msg.refuse.cooldown", "time", QuestEngine.clock(left / 50L)));
            return 0;
        }
        Feedback.chat(player, Lang.get(switch (refusal.get()) {
            case UNKNOWN -> "msg.refuse.unknown";
            case ALREADY_ACTIVE -> "msg.refuse.active";
            case ALREADY_COMPLETE -> "msg.refuse.complete";
            default -> "msg.refuse.locked";
        }));
        return 0;
    }

    private static int abandon(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Identifier id = resolveQuest(ctx).key().identifier();
        if (QuestEngine.abandon(player, id)) return 1;
        Feedback.chat(player, Lang.get("msg.not_active"));
        return 0;
    }

    private static int track(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Holder.Reference<Quest> holder = resolveQuest(ctx);
        if (QuestEngine.track(player, holder.key().identifier())) {
            Feedback.chat(player, Lang.fmt("msg.track", "name", holder.value().name()));
            return 1;
        }
        Feedback.chat(player, Lang.get("msg.not_active"));
        return 0;
    }

    private static int choose(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Identifier id = resolveQuest(ctx).key().identifier();
        int n = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx, "option");
        return QuestEngine.choose(player, id, n) ? 1 : 0;
    }

    // --- /quest journal ---

    private static int journalOpen(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Journal.open(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int journalGive(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Journal.give(ctx.getSource().getPlayerOrException());
        return 1;
    }

    private static int journalGiveTo(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Journal.give(EntityArgument.getPlayer(ctx, "player"));
        return 1;
    }

    // --- /quest giver set|remove|list (admin) ---

    private static java.util.Optional<net.minecraft.core.BlockPos> lookedAt(ServerPlayer player) {
        var hit = player.pick(6.0D, 0.0F, false);
        if (hit instanceof net.minecraft.world.phys.BlockHitResult b
                && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return java.util.Optional.of(b.getBlockPos());
        }
        return java.util.Optional.empty();
    }

    private static int giverSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Holder.Reference<Quest> holder = resolveQuest(ctx);
        // An NPC in the way wins over the block behind it.
        var npc = Npcs.provider().flatMap(c -> c.lookedAt(player, 6.0D));
        if (npc.isPresent()) {
            GiverStore.get(player.level().getServer()).setNpc(npc.get(), holder.key().identifier());
            Npcs.provider().get().ensureGiverRole(player.level().getServer(), npc.get());
            Feedback.chat(player, Lang.fmt("msg.giver.set_npc", "name", holder.value().name()));
            return 1;
        }
        var pos = lookedAt(player);
        if (pos.isEmpty()) {
            Feedback.chat(player, Lang.get("msg.giver.look"));
            return 0;
        }
        GiverStore.get(player.level().getServer()).set(player.level(), pos.get(), holder.key().identifier());
        Feedback.chat(player, Lang.fmt("msg.giver.set", "name", holder.value().name()));
        return 1;
    }

    private static int giverRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        var npc = Npcs.provider().flatMap(c -> c.lookedAt(player, 6.0D));
        if (npc.isPresent()) {
            boolean had = GiverStore.get(player.level().getServer()).removeNpc(npc.get());
            Feedback.chat(player, Lang.get(had ? "msg.giver.removed" : "msg.giver.none_here"));
            return had ? 1 : 0;
        }
        var pos = lookedAt(player);
        if (pos.isEmpty()) {
            Feedback.chat(player, Lang.get("msg.giver.look"));
            return 0;
        }
        boolean had = GiverStore.get(player.level().getServer()).remove(player.level(), pos.get());
        Feedback.chat(player, Lang.get(had ? "msg.giver.removed" : "msg.giver.none_here"));
        return had ? 1 : 0;
    }

    private static int giverList(CommandContext<CommandSourceStack> ctx) {
        GiverStore store = GiverStore.get(ctx.getSource().getServer());
        List<String> lines = new ArrayList<>();
        lines.add(Lang.fmt("msg.giver.list.header", "count", store.size()));
        store.view().forEach((k, v) -> lines.add(Lang.fmt("msg.giver.list.entry", "key", k, "quest", v)));
        String joined = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Feedback.colored(joined), false);
        return store.size();
    }

    // --- /quest log ---

    private static int log(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        QuestLog log = QuestEngine.journal(player);
        if (log.activeCount() == 0 && log.completedCount() == 0) {
            Feedback.chat(player, Lang.get("cmd.log.empty"));
            return 0;
        }
        MutableComponent out = Feedback.colored(Lang.fmt("cmd.log.header",
                "active", log.activeCount(), "completed", log.completedCount())).copy();
        for (var e : log.activeView().entrySet()) {
            var holder = QuestEngine.quest(player.level().getServer(), e.getKey());
            String name = holder.map(h -> h.value().name()).orElse(e.getKey().toString());
            String tracked = log.tracked().map(e.getKey()::equals).orElse(false) ? Lang.get("status.tracked") : "";
            out.append("\n").append(Feedback.colored(Lang.fmt("cmd.log.quest", "name", name, "progress", tracked)));
            holder.ifPresent(h -> {
                var objectives = QuestEngine.currentObjectives(h.value(), e.getValue());
                for (int n = 0; n < objectives.size() && n < e.getValue().targets.size(); n++) {
                    out.append("\n").append(Feedback.colored(Lang.fmt("cmd.log.objective",
                            "line", objectives.get(n).describe(),
                            "done", e.getValue().progress.get(n), "target", e.getValue().targets.get(n))));
                }
            });
            for (Component b : holder.map(h -> buttonsFor(player, e.getKey(), h.value())).orElse(List.of())) {
                out.append(" ").append(b);
            }
        }
        Feedback.chat(player, out);
        return log.activeCount() + log.completedCount();
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
        for (String id : List.of("legendquest", "standards", "zombiemod", "cityworld", "cast", "storyteller")) {
            if (ModList.get().isLoaded(id)) siblings.add(id);
        }
        String lines = String.join("\n",
                Lang.fmt("cmd.status", "chapters", chapters.size(), "quests", quests.size(),
                        "objectives", ObjectiveTypes.TYPES.size(), "rewards", RewardTypes.TYPES.size()),
                Lang.fmt("cmd.status.config", "path", FMLPaths.CONFIGDIR.get().resolve(Chronicler.MODID).toAbsolutePath()),
                Lang.fmt("cmd.status.siblings", "list", siblings.isEmpty() ? "none" : String.join(", ", siblings)),
                Lang.fmt("cmd.status.party", "provider", Party.providerName()),
                Lang.fmt("cmd.status.character", "provider", Sheet.providerName()));
        source.sendSuccess(() -> Feedback.colored(lines), false);
        return quests.size();
    }

    private static int flagSet(CommandContext<CommandSourceStack> ctx, boolean value) {
        String flag = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "flag");
        FlagStore.get(ctx.getSource().getServer()).set(flag, value);
        ctx.getSource().sendSuccess(() -> Feedback.colored(Lang.fmt("msg.flag.set", "flag", FlagStore.normalise(flag), "value", value)), true);
        return 1;
    }

    private static int flagList(CommandContext<CommandSourceStack> ctx) {
        var flags = FlagStore.get(ctx.getSource().getServer()).view();
        if (flags.isEmpty()) {
            ctx.getSource().sendSuccess(() -> Feedback.colored(Lang.get("msg.flag.list.none")), false);
            return 0;
        }
        List<String> lines = new ArrayList<>();
        lines.add(Lang.fmt("msg.flag.list.header", "count", flags.size()));
        flags.keySet().stream().sorted().forEach(f -> lines.add(Lang.fmt("msg.flag.list.entry", "flag", f)));
        String joined = String.join("\n", lines);
        ctx.getSource().sendSuccess(() -> Feedback.colored(joined), false);
        return flags.size();
    }

    private static int reset(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        QuestEngine.journal(target).clear();
        ctx.getSource().sendSuccess(() -> Feedback.colored(
                Lang.fmt("msg.reset", "player", target.getName().getString())), true);
        return 1;
    }

    private ChroniclerCommands() {}
}
