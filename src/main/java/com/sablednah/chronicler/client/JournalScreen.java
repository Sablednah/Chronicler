package com.sablednah.chronicler.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.lwjgl.glfw.GLFW;

import com.mojang.blaze3d.platform.InputConstants;
import com.sablednah.chronicler.core.QuestMap;
import com.sablednah.chronicler.network.JournalPayload;
import com.sablednah.chronicler.network.JournalPayload.Button;
import com.sablednah.chronicler.network.JournalPayload.ChapterView;
import com.sablednah.chronicler.network.JournalPayload.External;
import com.sablednah.chronicler.network.JournalPayload.QuestView;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The journal as a panel. Left: the chapters, each with its quests indented
 * under it. Right: the selected chapter's quest map -- a column per step of
 * prerequisites, stand-ins for the ones from other chapters -- or one quest's
 * page with its buttons.
 *
 * <p>Everything drawn is what {@link ClientJournal} last received, text
 * included; nothing here knows a rule. A button sends its command back, the
 * server runs it as the player and answers with a fresh journal, and the panel
 * asks again every two seconds while open so a kill counts while you read.</p>
 *
 * <p>Drawn by hand in the family's way (LegendQuest's handbook): immediate-mode
 * hotspots rebuilt every frame, scissored columns, no vanilla grey widgets.</p>
 */
public final class JournalScreen extends Screen {

    // A field journal: dark leather, brass fittings.
    private static final int LEATHER_TOP = 0xF41E1813;
    private static final int LEATHER_BOTTOM = 0xF4110E0B;
    private static final int BRASS = 0xFF7A5C2E;
    private static final int BRASS_DIM = 0x807A5C2E;
    private static final int GOLD = 0xFFD9A441;
    private static final int DIVIDER = 0xFF3E352A;
    private static final int INSET = 0x30000000;
    private static final int SELECTED = 0x40D9A441;
    private static final int HOVER = 0x28FFFFFF;
    private static final int TEXT = 0xFFE6E0D4;
    private static final int NODE_BG = 0xFF1A1511;
    private static final int EDGE_DONE = 0xFF6FAF4A;
    private static final int EDGE_OPEN = 0xFF5A534B;

    private static final int HEADER_H = 28;
    private static final int ROW_H = 12;
    private static final int INDENT = 9;
    private static final int CELL_W = 88;
    private static final int CELL_H = 42;
    private static final int NODE = 22;
    private static final int REFRESH_TICKS = 40;

    /** A clickable region. {@code canvas}: on the map, decided on release so a drag is not a click. */
    private record Hot(int x0, int y0, int x1, int y1, Runnable action, boolean canvas) {}
    private record Row(ChapterView chapter, QuestView quest) {}
    private record Cell(int x, int y) {}

    private final List<Hot> hotspots = new ArrayList<>();
    private final Deque<String[]> history = new ArrayDeque<>();
    private final Set<String> collapsed = new HashSet<>();
    private final Map<String, List<QuestMap.Placed>> layouts = new HashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private JournalPayload laidOutFor;

    private String chapterId = "";
    private String questId = ""; // empty: the chapter's map
    private double listScroll, maxListScroll;
    private double pageScroll, maxPageScroll;
    private double panX, panY, maxPanX, maxPanY;
    private int canvasX0, canvasY0, canvasX1, canvasY1;
    private boolean pressedInCanvas, dragged;
    private double pressX, pressY;
    private int ticks;

    JournalScreen(String focus) {
        super(ClientJournal.get() == null ? Component.empty() : ClientJournal.get().title());
        JournalPayload p = ClientJournal.get();
        if (p != null) {
            // A chapter with nothing left to do starts folded; the reader can open it.
            for (ChapterView c : p.chapters()) {
                if (!c.quests().isEmpty() && c.quests().stream().allMatch(q -> q.status() == JournalPayload.COMPLETE)) {
                    collapsed.add(c.id());
                }
            }
        }
        focus(focus);
    }

    /** Open at a quest, or -- asked for nothing -- where the reader most likely wants to be. */
    void focus(String focus) {
        JournalPayload p = ClientJournal.get();
        if (p == null) return;
        if (!focus.isEmpty() && findQuest(focus) != null) {
            goTo(chapterOf(focus).id(), focus, !chapterId.isEmpty());
            return;
        }
        if (!chapterId.isEmpty()) return; // already open: leave the reader where they are
        for (ChapterView c : p.chapters()) {
            for (QuestView q : c.quests()) if (q.tracked()) { goTo(c.id(), q.id(), false); return; }
        }
        for (ChapterView c : p.chapters()) {
            for (QuestView q : c.quests()) if (q.status() == JournalPayload.ACTIVE) { goTo(c.id(), q.id(), false); return; }
        }
        if (!p.chapters().isEmpty()) goTo(p.chapters().getFirst().id(), "", false);
    }

    private void goTo(String chapter, String quest, boolean remember) {
        if (remember && !chapterId.isEmpty() && !(chapter.equals(chapterId) && quest.equals(questId))) {
            history.push(new String[] {chapterId, questId});
        }
        if (!chapter.equals(chapterId)) {
            panX = 0;
            panY = 0;
        }
        chapterId = chapter;
        questId = quest;
        pageScroll = 0;
        collapsed.remove(chapter);
    }

    private void goBack() {
        String[] to = history.poll();
        if (to == null) return;
        if (!to[0].equals(chapterId)) {
            panX = 0;
            panY = 0;
        }
        chapterId = to[0];
        questId = to[1];
        pageScroll = 0;
        collapsed.remove(chapterId);
    }

    // --- lookups ---

    private ChapterView chapterById(String id) {
        JournalPayload p = ClientJournal.get();
        if (p == null) return null;
        for (ChapterView c : p.chapters()) if (c.id().equals(id)) return c;
        return null;
    }

    private QuestView findQuest(String id) {
        ChapterView c = chapterOf(id);
        if (c == null) return null;
        for (QuestView q : c.quests()) if (q.id().equals(id)) return q;
        return null;
    }

    private ChapterView chapterOf(String questId) {
        JournalPayload p = ClientJournal.get();
        if (p == null) return null;
        for (ChapterView c : p.chapters()) {
            for (QuestView q : c.quests()) if (q.id().equals(questId)) return c;
        }
        return null;
    }

    private Component label(String key) {
        JournalPayload p = ClientJournal.get();
        return p == null ? Component.literal(key) : p.labels().getOrDefault(key, Component.literal(key));
    }

    private Component glyph(byte status) {
        return label(switch (status) {
            case JournalPayload.AVAILABLE -> "panel.glyph.available";
            case JournalPayload.ACTIVE -> "panel.glyph.active";
            case JournalPayload.COMPLETE -> "panel.glyph.complete";
            case JournalPayload.COOLDOWN -> "panel.glyph.cooldown";
            default -> "panel.glyph.locked";
        });
    }

    private Component statusName(byte status) {
        return label(switch (status) {
            case JournalPayload.AVAILABLE -> "status.available";
            case JournalPayload.ACTIVE -> "status.active";
            case JournalPayload.COMPLETE -> "status.complete";
            default -> "status.locked";
        });
    }

    private static int frameColour(byte status) {
        return switch (status) {
            case JournalPayload.AVAILABLE -> 0xFFE8C84A;
            case JournalPayload.ACTIVE -> 0xFF55C4E0;
            case JournalPayload.COMPLETE -> 0xFF6FAF4A;
            case JournalPayload.COOLDOWN -> 0xFF7A746C;
            default -> 0xFF4A443E;
        };
    }

    private static int nameColour(byte status) {
        return switch (status) {
            case JournalPayload.AVAILABLE -> 0xFFF0E8D8;
            case JournalPayload.ACTIVE -> 0xFFFFFFFF;
            case JournalPayload.COMPLETE -> 0xFF9CC77E;
            default -> 0xFF8A837A;
        };
    }

    // --- layout ---

    private int bookW() { return Math.min(width - 16, 520); }
    private int bookH() { return Math.min(height - 16, 300); }
    private int bookX() { return (width - bookW()) / 2; }
    private int bookY() { return (height - bookH()) / 2; }
    private int listX() { return bookX() + 7; }
    private int top() { return bookY() + HEADER_H; }
    private int bottom() { return bookY() + bookH() - 7; }
    private int paneX() { return listX() + listW() + 8; }
    private int paneW() { return bookX() + bookW() - 7 - paneX(); }

    /** Sized to the longest name, within a third of the book. */
    private int listW() {
        JournalPayload p = ClientJournal.get();
        int widest = 0;
        if (p != null) {
            for (ChapterView c : p.chapters()) {
                widest = Math.max(widest, font.width(bold(c.name())) + 14);
                for (QuestView q : c.quests()) widest = Math.max(widest, font.width(q.name()) + INDENT + 16);
            }
        }
        return Math.max(90, Math.min(Math.min(170, bookW() / 3), widest + 8));
    }

    private List<Row> rows(JournalPayload p) {
        List<Row> out = new ArrayList<>();
        for (ChapterView c : p.chapters()) {
            out.add(new Row(c, null));
            if (!collapsed.contains(c.id())) for (QuestView q : c.quests()) out.add(new Row(c, q));
        }
        return out;
    }

    private List<QuestMap.Placed> layout(ChapterView ch) {
        return layouts.computeIfAbsent(ch.id(), k -> {
            List<QuestMap.Node> nodes = new ArrayList<>();
            int order = 0;
            for (QuestView q : ch.quests()) nodes.add(new QuestMap.Node(q.id(), q.requires(), order++));
            return QuestMap.layout(nodes);
        });
    }

    // --- rendering ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        hotspots.clear();
        JournalPayload p = ClientJournal.get();
        if (p == null) {
            onClose();
            return;
        }
        if (p != laidOutFor) {
            layouts.clear();
            laidOutFor = p;
        }
        // A refresh can take away what was open (an abandoned quest that is only listed once found).
        if (chapterById(chapterId) == null) {
            chapterId = "";
            questId = "";
            focus("");
        }
        if (!questId.isEmpty() && findQuest(questId) == null) questId = "";

        int x = bookX(), y = bookY(), w = bookW(), h = bookH();
        g.fillGradient(x, y, x + w, y + h, LEATHER_TOP, LEATHER_BOTTOM);
        frame(g, x - 2, y - 2, x + w + 2, y + h + 2, BRASS, 2);
        frame(g, x + 2, y + 2, x + w - 2, y + h - 2, BRASS_DIM, 1);

        chip(g, x + 7, y + 7, 14, label("panel.back"), !history.isEmpty(), mouseX, mouseY, this::goBack);
        chip(g, x + w - 21, y + 7, 14, label("panel.close"), true, mouseX, mouseY, this::onClose);
        g.drawCenteredString(font, p.title(), x + w / 2, y + 10, GOLD);
        g.drawString(font, p.progress(), x + w - 27 - font.width(p.progress()), y + 10, TEXT);
        g.fill(x + 7, y + HEADER_H - 5, x + w - 7, y + HEADER_H - 4, DIVIDER);

        if (p.chapters().isEmpty()) {
            int ly = top() + 6;
            for (FormattedCharSequence s : font.split(label("panel.empty"), w - 28)) {
                g.drawString(font, s, x + 14, ly, TEXT);
                ly += 10;
            }
            return;
        }
        renderList(g, p, mouseX, mouseY);
        ChapterView ch = chapterById(chapterId);
        if (ch == null) return;
        if (questId.isEmpty()) {
            renderMap(g, ch, mouseX, mouseY);
        } else {
            canvasX1 = canvasX0; // no map on screen
            renderPage(g, ch, findQuest(questId), mouseX, mouseY);
        }
    }

    private void renderList(GuiGraphics g, JournalPayload p, int mouseX, int mouseY) {
        int lx = listX(), lw = listW(), top = top(), bottom = bottom();
        g.fill(lx, top, lx + lw, bottom, INSET);
        List<Row> rows = rows(p);
        maxListScroll = Math.max(0, rows.size() * ROW_H + 6 - (bottom - top));
        listScroll = clamp(listScroll, 0, maxListScroll);

        g.enableScissor(lx, top, lx + lw, bottom);
        int ry = top + 4 - (int) listScroll;
        for (Row row : rows) {
            if (ry + ROW_H > top && ry - 2 < bottom) {
                int y0 = Math.max(ry - 2, top), y1 = Math.min(ry + ROW_H - 2, bottom);
                boolean hover = mouseX >= lx && mouseX < lx + lw && mouseY >= y0 && mouseY < y1;
                if (row.quest() == null) {
                    ChapterView c = row.chapter();
                    boolean selected = questId.isEmpty() && c.id().equals(chapterId);
                    if (selected) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, SELECTED);
                    else if (hover) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, HOVER);
                    boolean folded = collapsed.contains(c.id());
                    g.drawString(font, label(folded ? "panel.glyph.closed" : "panel.glyph.open"), lx + 3, ry, TEXT);
                    drawName(g, bold(c.name()), lx + 12, ry, lw - 16, GOLD, hover || selected);
                    String id = c.id();
                    // The arrow folds; the name opens the chapter's map (and folds it on a second click).
                    hotspots.add(new Hot(lx, y0, lx + 11, y1, () -> toggle(id), false));
                    hotspots.add(new Hot(lx + 11, y0, lx + lw, y1, () -> {
                        if (questId.isEmpty() && id.equals(chapterId)) toggle(id);
                        else goTo(id, "", true);
                    }, false));
                } else {
                    QuestView q = row.quest();
                    boolean selected = q.id().equals(questId);
                    if (selected) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, SELECTED);
                    else if (hover) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, HOVER);
                    int gx = lx + 3 + INDENT;
                    g.drawString(font, glyph(q.status()), gx, ry, TEXT);
                    Component name = q.tracked() ? q.name().copy().append(" ").append(label("panel.glyph.tracked")) : q.name();
                    drawName(g, name, gx + 9, ry, lx + lw - 4 - (gx + 9), nameColour(q.status()), hover || selected);
                    String chapter = row.chapter().id(), id = q.id();
                    hotspots.add(new Hot(lx, y0, lx + lw, y1, () -> goTo(chapter, id, true), false));
                }
            }
            ry += ROW_H;
        }
        g.disableScissor();
        scrollbar(g, lx + lw - 3, top, bottom, listScroll, maxListScroll, rows.size() * ROW_H + 6);
    }

    private void toggle(String chapter) {
        if (!collapsed.remove(chapter)) collapsed.add(chapter);
    }

    private void renderMap(GuiGraphics g, ChapterView ch, int mouseX, int mouseY) {
        int px = paneX(), pw = paneW(), y = top();
        int nameX = px;
        if (!ch.icon().isEmpty()) {
            g.renderItem(icon(ch.icon()), px, y);
            nameX += 19;
        }
        g.drawString(font, bold(ch.name()), nameX, y + 4, GOLD);
        y += 18;
        int shown = 0;
        for (Component line : ch.lines()) {
            for (FormattedCharSequence s : font.split(line, pw)) {
                if (shown++ >= 4) break; // the map is the point; a long blurb must not push it off the page
                g.drawString(font, s, px, y, TEXT);
                y += 10;
            }
        }
        if (!ch.buttons().isEmpty()) y = buttonRow(g, ch.buttons(), px, y + 1, pw, mouseX, mouseY, top(), bottom()) + 2;
        g.drawString(font, label("panel.map.hint"), px, y + 1, TEXT);
        y += 12;

        int cx0 = px, cy0 = y, cx1 = px + pw, cy1 = bottom();
        canvasX0 = cx0; canvasY0 = cy0; canvasX1 = cx1; canvasY1 = cy1;
        g.fill(cx0, cy0, cx1, cy1, INSET);
        frame(g, cx0, cy0, cx1, cy1, BRASS_DIM, 1);
        if (ch.quests().isEmpty() || cy1 - cy0 < 20) {
            g.drawString(font, label("panel.map.none"), cx0 + 6, cy0 + 6, TEXT);
            return;
        }

        List<QuestMap.Placed> placed = layout(ch);
        int cols = 1, rowCount = 1;
        for (QuestMap.Placed pl : placed) {
            cols = Math.max(cols, pl.col() + 1);
            rowCount = Math.max(rowCount, pl.row() + 1);
        }
        maxPanX = Math.max(0, cols * CELL_W + 12 - (cx1 - cx0));
        maxPanY = Math.max(0, rowCount * CELL_H + 10 - (cy1 - cy0));
        panX = clamp(panX, 0, maxPanX);
        panY = clamp(panY, 0, maxPanY);
        int ox = cx0 + 10 - (int) panX + (CELL_W - NODE) / 2 - 8;
        int oy = cy0 + 8 - (int) panY;
        Map<String, Cell> at = new HashMap<>();
        for (QuestMap.Placed pl : placed) at.put(pl.id(), new Cell(ox + pl.col() * CELL_W, oy + pl.row() * CELL_H));

        g.enableScissor(cx0 + 1, cy0 + 1, cx1 - 1, cy1 - 1);
        // Edges under the nodes. Green once the prerequisite is done: the path already walked.
        for (QuestView q : ch.quests()) {
            Cell to = at.get(q.id());
            if (to == null) continue;
            for (String r : q.requires()) {
                Cell from = at.get(r);
                if (from != null) edge(g, from, to, statusIn(ch, r) == JournalPayload.COMPLETE ? EDGE_DONE : EDGE_OPEN);
            }
        }
        List<Component> tooltip = null;
        boolean mouseInCanvas = mouseX >= cx0 && mouseX < cx1 && mouseY >= cy0 && mouseY < cy1;
        for (QuestMap.Placed pl : placed) {
            Cell c = at.get(pl.id());
            if (c.x() + CELL_W < cx0 || c.x() - CELL_W > cx1 || c.y() + CELL_H < cy0 || c.y() > cy1) continue;
            Component name;
            Component second;
            byte status;
            String iconId;
            String targetChapter;
            if (pl.external()) {
                External e = external(ch, pl.id());
                if (e == null) continue;
                name = e.name();
                second = e.chapter();
                status = e.status();
                iconId = e.icon();
                ChapterView home = chapterOf(pl.id());
                targetChapter = home == null ? null : home.id();
            } else {
                QuestView q = findIn(ch, pl.id());
                if (q == null) continue;
                name = q.name();
                second = statusName(q.status());
                status = q.status();
                iconId = q.icon();
                targetChapter = ch.id();
            }
            int bx0 = c.x() - (CELL_W - NODE) / 2 + 2, bx1 = bx0 + CELL_W - 4;
            boolean hover = mouseInCanvas && mouseX >= bx0 && mouseX < bx1 && mouseY >= c.y() - 2 && mouseY < c.y() + NODE + 12;
            node(g, c.x(), c.y(), iconId, status, pl.external(), hover);
            FormattedCharSequence label = ellipsis(pl.external() ? name.copy().withStyle(ChatFormatting.ITALIC) : name, CELL_W - 8);
            g.drawString(font, label, c.x() + NODE / 2 - font.width(label) / 2, c.y() + NODE + 3,
                    pl.external() ? 0xFF8A837A : nameColour(status));
            if (hover) tooltip = List.of(name, second);
            if (targetChapter != null) {
                String chapter = targetChapter, id = pl.id();
                hotspots.add(new Hot(Math.max(bx0, cx0), Math.max(c.y() - 2, cy0), Math.min(bx1, cx1), Math.min(c.y() + NODE + 12, cy1),
                        () -> goTo(chapter, id, true), true));
            }
        }
        g.disableScissor();
        if (tooltip != null) g.setTooltipForNextFrame(font, tooltip, Optional.empty(), mouseX, mouseY);
    }

    private void renderPage(GuiGraphics g, ChapterView ch, QuestView q, int mouseX, int mouseY) {
        if (q == null) return;
        int px = paneX(), pw = paneW() - 6, top = top(), bottom = bottom();
        g.enableScissor(px, top, px + pw, bottom);
        int y = top + 1 - (int) pageScroll;

        link(g, label("panel.to_map"), px, y, pw, mouseX, mouseY, top, bottom, () -> goTo(ch.id(), "", true));
        y += 14;
        g.renderItem(icon(q.icon()), px, y);
        g.drawString(font, glyph(q.status()), px + 13, y - 3, TEXT);
        int nameY = y + 4;
        for (FormattedCharSequence s : font.split(bold(q.name()), pw - 22)) {
            g.drawString(font, s, px + 21, nameY, nameColour(q.status()));
            nameY += 10;
        }
        y = Math.max(y + 20, nameY + 4);
        if (!q.buttons().isEmpty()) y = buttonRow(g, q.buttons(), px, y, pw, mouseX, mouseY, top, bottom) + 5;

        for (Component line : q.lines()) {
            for (FormattedCharSequence s : font.split(line, pw)) {
                g.drawString(font, s, px, y, TEXT);
                y += 10;
            }
        }
        if (!q.requires().isEmpty()) {
            y += 4;
            g.drawString(font, label("panel.requires"), px, y, TEXT);
            y += 11;
            for (String r : q.requires()) {
                QuestView rq = findQuest(r);
                ChapterView rc = chapterOf(r);
                if (rq == null || rc == null) continue;
                Component text = glyph(rq.status()).copy().append(" ").append(rq.name());
                String chapter = rc.id();
                link(g, text, px + 6, y, pw - 6, mouseX, mouseY, top, bottom, () -> goTo(chapter, r, true));
                y += 11;
            }
        }
        g.disableScissor();
        int contentH = y + (int) pageScroll - top;
        maxPageScroll = Math.max(0, contentH + 4 - (bottom - top));
        pageScroll = clamp(pageScroll, 0, maxPageScroll);
        scrollbar(g, px + pw + 3, top, bottom, pageScroll, maxPageScroll, contentH + 4);
    }

    // --- pieces ---

    private void node(GuiGraphics g, int x, int y, String iconId, byte status, boolean external, boolean hover) {
        g.fill(x, y, x + NODE, y + NODE, NODE_BG);
        int colour = hover ? 0xFFFFFFFF : external ? BRASS_DIM : frameColour(status);
        frame(g, x, y, x + NODE, y + NODE, colour, status == JournalPayload.ACTIVE && !external ? 2 : 1);
        g.renderItem(icon(iconId), x + 3, y + 3);
        if (external || status == JournalPayload.LOCKED) g.fill(x + 1, y + 1, x + NODE - 1, y + NODE - 1, 0x80000000);
        g.drawString(font, glyph(status), x + NODE - 4, y - 4, TEXT);
    }

    private static void edge(GuiGraphics g, Cell from, Cell to, int colour) {
        int x0 = from.x() + NODE, y0 = from.y() + NODE / 2;
        int x1 = to.x() - 1, y1 = to.y() + NODE / 2;
        if (x1 <= x0) return; // only a cycle points backwards; the page still lists it
        int mid = Math.max(x0 + 2, x1 - (CELL_W - NODE) / 2);
        g.fill(x0, y0, mid + 1, y0 + 1, colour);
        g.fill(mid, Math.min(y0, y1), mid + 1, Math.max(y0, y1) + 1, colour);
        g.fill(mid, y1, x1 + 1, y1 + 1, colour);
    }

    /** A row of buttons that wraps; returns the y below it. Hover shows the button's tip. */
    private int buttonRow(GuiGraphics g, List<Button> buttons, int x, int y, int maxW, int mouseX, int mouseY, int clipTop, int clipBottom) {
        int bx = x;
        for (Button b : buttons) {
            int w = font.width(b.label()) + 10;
            if (bx > x && bx + w > x + maxW) {
                bx = x;
                y += 16;
            }
            boolean hover = mouseX >= bx && mouseX < bx + w && mouseY >= y && mouseY < y + 14
                    && mouseY >= clipTop && mouseY < clipBottom;
            g.fill(bx, y, bx + w, y + 14, hover ? 0xFF33291E : 0xFF221A12);
            frame(g, bx, y, bx + w, y + 14, hover ? GOLD : BRASS, 1);
            g.drawString(font, b.label(), bx + 5, y + 3, TEXT);
            if (hover) g.setTooltipForNextFrame(font, b.tip(), mouseX, mouseY);
            String command = b.command();
            addClipped(bx, y, bx + w, y + 14, clipTop, clipBottom, () -> ClientJournal.run(command));
            bx += w + 4;
        }
        return y + 14;
    }

    private void link(GuiGraphics g, Component text, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 10
                && mouseY >= clipTop && mouseY < clipBottom;
        if (hover) g.fill(x - 1, y - 1, x + w, y + 10, HOVER);
        g.drawString(font, ellipsis(hover ? text.copy().withStyle(ChatFormatting.UNDERLINE) : text, w), x, y, 0xFF8FC8E8);
        addClipped(x, y - 1, x + w, y + 10, clipTop, clipBottom, action);
    }

    private void addClipped(int x0, int y0, int x1, int y1, int clipTop, int clipBottom, Runnable action) {
        int top = Math.max(y0, clipTop), bottom = Math.min(y1, clipBottom);
        if (bottom > top) hotspots.add(new Hot(x0, top, x1, bottom, action, false));
    }

    private void chip(GuiGraphics g, int x, int y, int w, Component label, boolean enabled, int mouseX, int mouseY, Runnable action) {
        int h = 14;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF33291E : 0xFF221A12);
        frame(g, x, y, x + w, y + h, hover ? GOLD : BRASS, 1);
        g.drawString(font, label, x + (w - font.width(label)) / 2, y + 3, enabled ? TEXT : 0xFF5A534B);
        if (enabled) hotspots.add(new Hot(x, y, x + w, y + h, action, false));
    }

    /**
     * A name in the list: trimmed to an ellipsis, except under the cursor or the
     * selection, where it slides so the whole of it can be read (the handbook's trick).
     */
    private void drawName(GuiGraphics g, Component text, int x, int y, int avail, int colour, boolean live) {
        int over = font.width(text) - avail;
        if (over <= 0) {
            g.drawString(font, text, x, y, colour);
            return;
        }
        if (!live) {
            g.drawString(font, ellipsis(text, avail), x, y, colour);
            return;
        }
        long travel = Math.max(1L, over * 22L);
        long dwell = 900L;
        long t = System.currentTimeMillis() % (2 * travel + 2 * dwell);
        int offset = t < dwell ? 0
                : t < dwell + travel ? (int) ((t - dwell) * over / travel)
                : t < 2 * dwell + travel ? over
                : over - (int) ((t - 2 * dwell - travel) * over / travel);
        g.enableScissor(x, y - 1, x + avail, y + 9);
        g.drawString(font, text, x - offset, y, colour);
        g.disableScissor();
    }

    private FormattedCharSequence ellipsis(Component text, int width) {
        if (font.width(text) <= width) return text.getVisualOrderText();
        FormattedText cut = font.substrByWidth(text, Math.max(0, width - font.width("…")));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
    }

    private void scrollbar(GuiGraphics g, int x, int top, int bottom, double scroll, double max, int contentH) {
        if (max <= 0) return;
        int h = bottom - top;
        g.fill(x, top, x + 2, bottom, 0x50000000);
        int thumb = Math.max(10, h * h / Math.max(h, contentH));
        int thumbY = top + (int) ((h - thumb) * (scroll / max));
        g.fill(x, thumbY, x + 2, thumbY + thumb, 0xC0D9A441);
    }

    private static void frame(GuiGraphics g, int x0, int y0, int x1, int y1, int colour, int t) {
        g.fill(x0, y0, x1, y0 + t, colour);
        g.fill(x0, y1 - t, x1, y1, colour);
        g.fill(x0, y0, x0 + t, y1, colour);
        g.fill(x1 - t, y0, x1, y1, colour);
    }

    private static Component bold(Component c) {
        return c.copy().withStyle(ChatFormatting.BOLD);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private ItemStack icon(String id) {
        return icons.computeIfAbsent(id, k -> {
            Identifier rl = k.isEmpty() ? null : Identifier.tryParse(k);
            if (rl == null || !BuiltInRegistries.ITEM.containsKey(rl)) return new ItemStack(Items.BOOK);
            return new ItemStack(BuiltInRegistries.ITEM.getValue(rl));
        });
    }

    private static QuestView findIn(ChapterView ch, String id) {
        for (QuestView q : ch.quests()) if (q.id().equals(id)) return q;
        return null;
    }

    private static External external(ChapterView ch, String id) {
        for (External e : ch.externals()) if (e.id().equals(id)) return e;
        return null;
    }

    private static byte statusIn(ChapterView ch, String id) {
        QuestView q = findIn(ch, id);
        if (q != null) return q.status();
        External e = external(ch, id);
        return e == null ? JournalPayload.LOCKED : e.status();
    }

    // --- input ---

    @Override
    public void tick() {
        super.tick();
        if (++ticks % REFRESH_TICKS == 0) ClientJournal.refresh();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) return true;
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_4) {
            goBack();
            return true;
        }
        if (event.button() != 0) return false;
        for (Hot hot : List.copyOf(hotspots)) {
            if (!hot.canvas() && event.x() >= hot.x0() && event.x() < hot.x1() && event.y() >= hot.y0() && event.y() < hot.y1()) {
                hot.action().run();
                return true;
            }
        }
        if (questId.isEmpty() && event.x() >= canvasX0 && event.x() < canvasX1 && event.y() >= canvasY0 && event.y() < canvasY1) {
            pressedInCanvas = true;
            dragged = false;
            pressX = event.x();
            pressY = event.y();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
        if (pressedInCanvas && event.button() == 0) {
            if (Math.abs(event.x() - pressX) + Math.abs(event.y() - pressY) > 3) dragged = true;
            if (dragged) {
                panX = clamp(panX - dx, 0, maxPanX);
                panY = clamp(panY - dy, 0, maxPanY);
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedInCanvas && event.button() == 0) {
            pressedInCanvas = false;
            if (!dragged) {
                for (Hot hot : List.copyOf(hotspots)) {
                    if (hot.canvas() && event.x() >= hot.x0() && event.x() < hot.x1() && event.y() >= hot.y0() && event.y() < hot.y1()) {
                        hot.action().run();
                        break;
                    }
                }
            }
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true;
        if (mouseX < paneX() - 4) {
            listScroll = clamp(listScroll - scrollY * ROW_H, 0, maxListScroll);
        } else if (questId.isEmpty()) {
            panY = clamp(panY - scrollY * 16, 0, maxPanY);
            panX = clamp(panX - scrollX * 16, 0, maxPanX);
        } else {
            pageScroll = clamp(pageScroll - scrollY * 12, 0, maxPageScroll);
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (super.keyPressed(event)) return true;
        if (ChroniclerKeys.JOURNAL.matches(event)) {
            onClose(); // the key that opened it closes it
            return true;
        }
        switch (event.key()) {
            case InputConstants.KEY_BACKSPACE -> goBack();
            case InputConstants.KEY_UP -> step(-1);
            case InputConstants.KEY_DOWN -> step(1);
            default -> { return false; }
        }
        return true;
    }

    /** Arrow keys walk the list. Browsing, so it leaves the back stack alone. */
    private void step(int direction) {
        JournalPayload p = ClientJournal.get();
        if (p == null) return;
        List<Row> rows = rows(p);
        int at = -1;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            boolean here = r.quest() == null ? questId.isEmpty() && r.chapter().id().equals(chapterId) : r.quest().id().equals(questId);
            if (here) { at = i; break; }
        }
        int next = Math.max(0, Math.min(rows.size() - 1, at + direction));
        if (next == at || rows.isEmpty()) return;
        Row r = rows.get(next);
        goTo(r.chapter().id(), r.quest() == null ? "" : r.quest().id(), false);
        int rowTop = next * ROW_H, rowBottom = rowTop + ROW_H + 6, viewH = bottom() - top();
        if (rowTop < listScroll) listScroll = rowTop;
        else if (rowBottom > listScroll + viewH) listScroll = rowBottom - viewH;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
