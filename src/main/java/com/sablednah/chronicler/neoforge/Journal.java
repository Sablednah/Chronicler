package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.WrittenBookContent;

/**
 * The journal: a vanilla written book whose pages are regenerated from the
 * player's quest log every time it is opened. This is the vanilla-client UI --
 * a contents page linking to a page per active quest, each with progress and
 * clickable Track / Abandon, then what is on offer with Accept links, then
 * what is done.
 *
 * <p>Two ways to read it. The <b>item</b>, marked in {@code CUSTOM_DATA} (never
 * by name -- a name is anvil-writable), is refreshed in the hand on right-click
 * before vanilla opens it. {@code /quest journal} opens the same pages with no
 * item at all: a fresh book is slipped into the off hand, the open packet is
 * sent, and the hand is restored in the same tick -- the client captures the
 * stack when the screen opens, so the restore lands after it has what it
 * needs. New players are handed the item on first join (config).</p>
 *
 * <p>Book pages are drawn on parchment: dark colours, and an empty unstyled
 * root on every page so a bold heading cannot bleed into the list under it
 * (ZombieMod's dex learned that one).</p>
 */
public final class Journal {

    /** The NBT key our marker lives under, inside the item's custom data. */
    private static final String MARKER = "chronicler:journal";
    private static final int LINES_PER_PAGE = 14;

    // --- the item ---

    public static boolean is(ItemStack stack) {
        if (stack.isEmpty() || !stack.is(Items.WRITTEN_BOOK)) return false;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        return data != null && data.contains(MARKER);
    }

    public static ItemStack make(ServerPlayer player) {
        ItemStack book = new ItemStack(Items.WRITTEN_BOOK);
        CompoundTag tag = new CompoundTag();
        tag.putString(MARKER, player.getUUID().toString());
        book.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        refresh(player, book);
        return book;
    }

    /** Rewrite the pages from the player's current log. */
    public static void refresh(ServerPlayer player, ItemStack book) {
        String title = Lang.get("journal.title");
        if (title.length() > WrittenBookContent.TITLE_MAX_LENGTH) title = title.substring(0, WrittenBookContent.TITLE_MAX_LENGTH);
        book.set(DataComponents.WRITTEN_BOOK_CONTENT, new WrittenBookContent(
                Filterable.passThrough(title), Lang.get("journal.author"), 0, pages(player), false));
    }

    /** Hand over a journal; tell the player. */
    public static void give(ServerPlayer player) {
        ItemStack book = make(player);
        player.getInventory().add(book);
        if (!book.isEmpty()) player.drop(book, false);
        QuestEngine.journal(player).markJournalGiven();
        Feedback.chat(player, Lang.get("msg.journal.given"));
    }

    /** First join, if configured and never given before. */
    public static void giveToNewPlayer(ServerPlayer player) {
        if (!ChroniclerConfig.JOURNAL_GIVE_NEW.get()) return;
        QuestLog log = QuestEngine.journal(player);
        if (log.journalGiven()) return;
        ItemStack book = make(player);
        player.getInventory().add(book);
        if (!book.isEmpty()) player.drop(book, false);
        log.markJournalGiven();
        Feedback.chat(player, Lang.get("msg.journal.new_player"));
    }

    // --- opening ---

    /** Open the journal: the held item refreshed, or a fresh virtual copy. */
    public static void open(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack held = player.getItemInHand(hand);
            if (is(held)) {
                refresh(player, held);
                player.containerMenu.broadcastChanges();
                player.openItemGui(held, hand);
                return;
            }
        }
        ItemStack saved = player.getOffhandItem().copy();
        ItemStack book = make(player);
        player.setItemInHand(InteractionHand.OFF_HAND, book);
        player.containerMenu.broadcastChanges();
        player.openItemGui(book, InteractionHand.OFF_HAND);
        player.setItemInHand(InteractionHand.OFF_HAND, saved);
        player.containerMenu.broadcastChanges();
    }

    /** Right-click on the item: fresh pages before vanilla opens it. */
    public static void onUse(ServerPlayer player, ItemStack held, InteractionHand hand) {
        refresh(player, held);
        player.containerMenu.broadcastChanges();
        player.openItemGui(held, hand);
    }

    // --- pages ---

    public static List<Filterable<Component>> pages(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);
        List<Filterable<Component>> pages = new ArrayList<>();

        // Which quests get a page of their own, in journal order.
        List<Identifier> active = new ArrayList<>(log.activeView().keySet());
        List<Holder.Reference<Quest>> available = new ArrayList<>();
        List<Holder.Reference<Quest>> done = new ArrayList<>();
        QuestEngine.quests(server).listElements().forEach(h -> {
            Identifier id = h.key().identifier();
            if (log.isActive(id)) return;
            if (log.isComplete(id) && !h.value().repeatable()) { done.add(h); return; }
            if (h.value().hidden()) return;
            if (QuestEngine.available(player, id, h.value())) available.add(h);
        });

        // Page numbering: 1 = contents, then one per active quest, then available, then done.
        int firstQuestPage = 2;
        int availablePage = firstQuestPage + active.size();
        int availablePages = Math.max(1, (available.size() + 5) / 6);
        int donePage = availablePage + availablePages;

        // --- contents ---
        MutableComponent contents = Component.empty();
        contents.append(Feedback.colored(Lang.get("journal.heading"))).append("\n");
        contents.append(Feedback.colored(Lang.fmt("journal.counts",
                "active", log.activeCount(), "completed", log.completedCount()))).append("\n");
        var progress = QuestEngine.progress(player, java.util.Optional.empty());
        if (progress.total() > 0) contents.append(Feedback.colored(Lang.fmt("journal.progress", "percent", progress.percent()))).append("\n");
        contents.append("\n");
        if (active.isEmpty()) {
            contents.append(Feedback.colored(Lang.get("journal.none_active"))).append("\n");
        }
        for (int n = 0; n < active.size(); n++) {
            String name = QuestEngine.quest(server, active.get(n)).map(h -> h.value().name()).orElse(active.get(n).toString());
            boolean tracked = log.tracked().map(active.get(n)::equals).orElse(false);
            contents.append(pageLink(Lang.fmt(tracked ? "journal.contents.tracked" : "journal.contents.quest", "name", name),
                    firstQuestPage + n, Lang.get("journal.tip.turn"))).append("\n");
        }
        contents.append("\n");
        contents.append(pageLink(Lang.fmt("journal.contents.available", "count", available.size()), availablePage, Lang.get("journal.tip.turn"))).append("\n");
        contents.append(pageLink(Lang.fmt("journal.contents.done", "count", done.size()), donePage, Lang.get("journal.tip.turn")));
        pages.add(Filterable.passThrough(contents));

        // --- one page per active quest ---
        for (Identifier id : active) {
            MutableComponent page = Component.empty();
            var holder = QuestEngine.quest(server, id);
            QuestLog.Entry e = log.entry(id);
            if (holder.isEmpty() || e == null) {
                page.append(Feedback.colored(Lang.fmt("journal.quest.gone", "id", id)));
                pages.add(Filterable.passThrough(page));
                continue;
            }
            Quest q = holder.get().value();
            page.append(Feedback.colored(Lang.fmt("journal.quest.name", "name", q.name()))).append("\n");
            String chapter = QuestEngine.chapters(server)
                    .get(net.minecraft.resources.ResourceKey.create(com.sablednah.chronicler.ChroniclerRegistries.CHAPTER, q.chapter()))
                    .map(h -> h.value().name()).orElse(q.chapter().toString());
            page.append(Feedback.colored(Lang.fmt("journal.quest.chapter", "chapter", chapter))).append("\n");
            if (QuestEngine.scopeOf(server, q) == QuestScope.PARTY) {
                page.append(Feedback.colored(Lang.get("journal.quest.party"))).append("\n");
            }
            q.description().ifPresent(d -> page.append(Feedback.colored(Lang.fmt("journal.quest.description", "description", d))).append("\n"));
            List<com.sablednah.chronicler.data.ObjectiveSpec> objectives = QuestEngine.currentObjectives(q, e);
            if (q.beats().size() > 1) {
                page.append(Feedback.colored(Lang.fmt("journal.quest.stage", "stage", e.stage + 1, "stages", q.beats().size()))).append("\n");
                q.beats().get(Math.min(e.stage, q.beats().size() - 1)).text().ifPresent(t ->
                        page.append(Feedback.colored(Lang.fmt("journal.quest.text", "text", t))).append("\n"));
            }
            page.append("\n");
            com.sablednah.chronicler.data.Stage beat = q.beats().get(Math.min(e.stage, q.beats().size() - 1));
            if (beat.isDecision()) {
                page.append(Feedback.colored(Lang.get("journal.quest.decision"))).append("\n");
                for (int n = 0; n < beat.choices().size(); n++) {
                    page.append(commandLink(Lang.fmt("journal.choice", "label", beat.choices().get(n).label()),
                            "/quest choose " + id + " " + (n + 1), Lang.get("msg.choice.tip"))).append("\n");
                }
            }
            for (int n = 0; n < objectives.size() && n < e.targets.size(); n++) {
                String line = objectives.get(n).describe();
                page.append(Feedback.colored(e.objectiveDone(n)
                        ? Lang.fmt("journal.objective.done", "line", line)
                        : Lang.fmt("journal.objective.open", "line", line, "done", e.progress.get(n), "target", e.targets.get(n))))
                        .append("\n");
            }
            page.append("\n");
            boolean tracked = log.tracked().map(id::equals).orElse(false);
            if (!tracked) {
                page.append(commandLink(Lang.get("journal.link.track"), "/quest track " + id, Lang.get("button.track.tip"))).append("  ");
            }
            page.append(commandLink(Lang.get("journal.link.abandon"), "/quest abandon " + id, Lang.get("button.abandon.tip"))).append("\n");
            page.append(pageLink(Lang.get("journal.link.back"), 1, Lang.get("journal.tip.contents")));
            pages.add(Filterable.passThrough(page));
        }

        // --- available ---
        MutableComponent page = Component.empty();
        page.append(Feedback.colored(Lang.get("journal.available.heading"))).append("\n\n");
        if (available.isEmpty()) page.append(Feedback.colored(Lang.get("journal.available.none"))).append("\n");
        int onPage = 0;
        for (var h : available) {
            Identifier id = h.key().identifier();
            page.append(Feedback.colored(Lang.fmt("journal.available.quest", "name", h.value().name()))).append("\n");
            if (h.value().giver().isPresent()) {
                page.append(Feedback.colored(Lang.fmt("journal.available.where",
                        "where", h.value().giver().get().describe()))).append("\n");
            }
            page.append(commandLink(Lang.get("journal.link.accept"), "/quest accept " + id, Lang.get("button.accept.tip")))
                    .append("  ")
                    .append(commandLink(Lang.get("journal.link.info"), "/quest info " + id, Lang.get("button.info.tip")))
                    .append("\n");
            if (++onPage == 6) {
                page.append(pageLink(Lang.get("journal.link.back"), 1, Lang.get("journal.tip.contents")));
                pages.add(Filterable.passThrough(page));
                page = Component.empty();
                onPage = 0;
            }
        }
        if (onPage > 0 || available.isEmpty()) {
            page.append("\n").append(pageLink(Lang.get("journal.link.back"), 1, Lang.get("journal.tip.contents")));
            pages.add(Filterable.passThrough(page));
        }

        // --- done ---
        page = Component.empty();
        page.append(Feedback.colored(Lang.get("journal.done.heading"))).append("\n\n");
        if (done.isEmpty()) page.append(Feedback.colored(Lang.get("journal.done.none"))).append("\n");
        onPage = 0;
        for (var h : done) {
            int times = log.completions(h.key().identifier());
            page.append(Feedback.colored(Lang.fmt(times > 1 ? "journal.done.quest_times" : "journal.done.quest",
                    "name", h.value().name(), "times", times))).append("\n");
            if (++onPage == LINES_PER_PAGE - 3) {
                page.append(pageLink(Lang.get("journal.link.back"), 1, Lang.get("journal.tip.contents")));
                pages.add(Filterable.passThrough(page));
                page = Component.empty();
                onPage = 0;
            }
        }
        if (onPage > 0 || done.isEmpty()) {
            page.append("\n").append(pageLink(Lang.get("journal.link.back"), 1, Lang.get("journal.tip.contents")));
            pages.add(Filterable.passThrough(page));
        }
        return pages;
    }

    private static Component pageLink(String label, int page, String tooltip) {
        return Feedback.colored(label).copy().withStyle(s -> s
                .withClickEvent(new ClickEvent.ChangePage(page))
                .withHoverEvent(new HoverEvent.ShowText(Feedback.colored(tooltip))));
    }

    private static Component commandLink(String label, String command, String tooltip) {
        return Feedback.button(label, command, tooltip);
    }

    private Journal() {}
}
