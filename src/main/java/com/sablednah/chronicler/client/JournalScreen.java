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


import com.mojang.blaze3d.platform.InputConstants;
import com.sablednah.chronicler.core.QuestMap;
import com.sablednah.chronicler.core.WebLayout;
import com.sablednah.chronicler.network.JournalPayload;
import com.sablednah.chronicler.network.JournalPayload.Button;
import com.sablednah.chronicler.network.JournalPayload.ChapterView;
import com.sablednah.chronicler.network.JournalPayload.External;
import com.sablednah.chronicler.network.JournalPayload.QuestView;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
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
    private static final int CELL_W = WebLayout.JOURNAL.cellW();
    private static final int CELL_H = WebLayout.JOURNAL.cellH();
    private static final int NODE = WebLayout.JOURNAL.node();
    private static final int REFRESH_TICKS = 40;

    /** A clickable region. {@code canvas}: on the map, decided on release so a drag is not a click. */
    private record Hot(int x0, int y0, int x1, int y1, Runnable action, boolean canvas) {}
    /** A line of the list: home ({@code chapter} null), the overview ({@code overview}), a chapter, or one of its quests. */
    private record Row(ChapterView chapter, QuestView quest, boolean overview) {
        Row(ChapterView chapter, QuestView quest) { this(chapter, quest, false); }
    }
    private record Cell(int x, int y) {}

    private final List<Hot> hotspots = new ArrayList<>();
    private final Deque<String[]> history = new ArrayDeque<>();
    private final Set<String> collapsed = new HashSet<>();
    private final Map<String, List<QuestMap.Placed>> layouts = new HashMap<>();
    private final Map<String, ItemStack> icons = new HashMap<>();
    private JournalPayload laidOutFor;

    /** The home page, in the chapter slot: not a chapter id any pack could use. */
    private static final String HOME = "~home";
    /** Every chapter on one canvas, each in its box; in the chapter slot like {@link #HOME}. */
    private static final String OVERVIEW = "~overview";
    private static final int BOX_HEAD = WebLayout.JOURNAL.boxHead();
    /** How far either side of a crossing the line behind fades. */
    private static final int FADE = 4;
    private static final double ZOOM_MIN = 0.35, ZOOM_MAX = 1.6;
    private Geo geo;
    private JournalPayload geoFor;
    private double zoom = 1;

    /** The overview worked out once per journal: the web, where it all goes, and each quest's status for line colour. */
    private record Geo(QuestMap.Web web, WebLayout.Layout layout, Map<String, Byte> status) {}
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
        // Asked for nothing in particular: the home page, which says what is on the go and on offer.
        goTo(HOME, "", false);
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
        out.add(new Row(null, null)); // home
        out.add(new Row(null, null, true)); // the overview, under it
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
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
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
        if (!HOME.equals(chapterId) && !OVERVIEW.equals(chapterId) && chapterById(chapterId) == null) {
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
        g.centeredText(font, p.title(), x + w / 2, y + 10, GOLD);
        g.text(font, p.progress(), x + w - 27 - font.width(p.progress()), y + 10, TEXT);
        g.fill(x + 7, y + HEADER_H - 5, x + w - 7, y + HEADER_H - 4, DIVIDER);

        if (p.chapters().isEmpty()) {
            int ly = top() + 6;
            for (FormattedCharSequence s : font.split(label("panel.empty"), w - 28)) {
                g.text(font, s, x + 14, ly, TEXT);
                ly += 10;
            }
            return;
        }
        renderList(g, p, mouseX, mouseY);
        if (HOME.equals(chapterId)) {
            canvasX1 = canvasX0;
            renderHome(g, p, mouseX, mouseY);
            return;
        }
        if (OVERVIEW.equals(chapterId)) {
            renderOverview(g, p, mouseX, mouseY);
            return;
        }
        ChapterView ch = chapterById(chapterId);
        if (ch == null) return;
        if (questId.isEmpty()) {
            renderMap(g, ch, mouseX, mouseY);
        } else {
            canvasX1 = canvasX0; // no map on screen
            renderPage(g, ch, findQuest(questId), mouseX, mouseY);
        }
    }

    private void renderList(GuiGraphicsExtractor g, JournalPayload p, int mouseX, int mouseY) {
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
                if (row.overview()) {
                    boolean selected = OVERVIEW.equals(chapterId);
                    if (selected) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, SELECTED);
                    else if (hover) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, HOVER);
                    drawName(g, label("panel.overview"), lx + 4 + INDENT, ry, lw - 8 - INDENT, GOLD, hover || selected);
                    hotspots.add(new Hot(lx, y0, lx + lw, y1, () -> goTo(OVERVIEW, "", true), false));
                } else if (row.chapter() == null) {
                    boolean selected = HOME.equals(chapterId);
                    if (selected) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, SELECTED);
                    else if (hover) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, HOVER);
                    drawName(g, label("panel.home"), lx + 4, ry, lw - 8, GOLD, hover || selected);
                    hotspots.add(new Hot(lx, y0, lx + lw, y1, () -> goTo(HOME, "", true), false));
                } else if (row.quest() == null) {
                    ChapterView c = row.chapter();
                    boolean selected = questId.isEmpty() && c.id().equals(chapterId);
                    if (selected) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, SELECTED);
                    else if (hover) g.fill(lx + 1, ry - 2, lx + lw - 1, ry + ROW_H - 2, HOVER);
                    boolean folded = collapsed.contains(c.id());
                    g.text(font, label(folded ? "panel.glyph.closed" : "panel.glyph.open"), lx + 3, ry, TEXT);
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
                    g.text(font, glyph(q.status()), gx, ry, TEXT);
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

    private void renderMap(GuiGraphicsExtractor g, ChapterView ch, int mouseX, int mouseY) {
        int px = paneX(), pw = paneW(), y = top();
        int nameX = px;
        if (!ch.icon().isEmpty()) {
            g.item(icon(ch.icon()), px, y);
            nameX += 19;
        }
        g.text(font, bold(ch.name()), nameX, y + 4, GOLD);
        y += 18;
        int shown = 0;
        for (Component line : ch.lines()) {
            for (FormattedCharSequence s : font.split(line, pw)) {
                if (shown++ >= 4) break; // the map is the point; a long blurb must not push it off the page
                g.text(font, s, px, y, TEXT);
                y += 10;
            }
        }
        if (!ch.buttons().isEmpty()) y = buttonRow(g, ch.buttons(), px, y + 1, pw, mouseX, mouseY, top(), bottom()) + 2;
        g.text(font, label("panel.map.hint"), px, y + 1, TEXT);
        y += 12;

        int cx0 = px, cy0 = y, cx1 = px + pw, cy1 = bottom();
        canvasX0 = cx0; canvasY0 = cy0; canvasX1 = cx1; canvasY1 = cy1;
        g.fill(cx0, cy0, cx1, cy1, INSET);
        frame(g, cx0, cy0, cx1, cy1, BRASS_DIM, 1);
        if (ch.quests().isEmpty() || cy1 - cy0 < 20) {
            g.text(font, label("panel.map.none"), cx0 + 6, cy0 + 6, TEXT);
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
            g.text(font, label, c.x() + NODE / 2 - font.width(label) / 2, c.y() + NODE + 3,
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

    /**
     * Every chapter at once, joined up ({@link QuestMap#web}): a box per chapter, left to right by what follows what,
     * each lined up with the first chapter it follows; inside, the chapter's own map; between boxes, a line from the
     * quest that opens the next. What is not open yet stays hidden behind a dotted line to a "?".
     */
    private void renderOverview(GuiGraphicsExtractor g, JournalPayload p, int mouseX, int mouseY) {
        int px = paneX(), pw = paneW(), y = top();
        g.text(font, label("panel.overview"), px, y + 4, GOLD);
        y += 16;
        for (FormattedCharSequence s : font.split(label("panel.overview.hint"), pw)) {
            g.text(font, s, px, y, TEXT);
            y += 10;
        }
        y += 2;
        int cx0 = px, cy0 = y, cx1 = px + pw, cy1 = bottom();
        canvasX0 = cx0; canvasY0 = cy0; canvasX1 = cx1; canvasY1 = cy1;
        g.fill(cx0, cy0, cx1, cy1, INSET);
        frame(g, cx0, cy0, cx1, cy1, BRASS_DIM, 1);
        if (cy1 - cy0 < 20) return;

        Geo geo = geo(p);
        double z = zoom;
        WebLayout.Layout lay = geo.layout();
        maxPanX = Math.max(0, lay.w() - (cx1 - cx0 - 12) / z);
        maxPanY = Math.max(0, lay.h() - (cy1 - cy0 - 12) / z);
        panX = clamp(panX, 0, maxPanX);
        panY = clamp(panY, 0, maxPanY);
        // Drawn in the overview's own units, scaled and panned; the mouse is taken back into them.
        double mx = (mouseX - cx0 - 6) / z + panX, my = (mouseY - cy0 - 6) / z + panY;
        double vx0 = panX - 6 / z, vy0 = panY - 6 / z, vx1 = panX + (cx1 - cx0) / z, vy1 = panY + (cy1 - cy0) / z;
        boolean mouseInCanvas = mouseX >= cx0 && mouseX < cx1 && mouseY >= cy0 && mouseY < cy1;
        List<Component> tooltip = null;
        Map<String, ChapterView> chapters = new HashMap<>();
        for (ChapterView c : p.chapters()) chapters.put(c.id(), c);

        g.enableScissor(cx0 + 1, cy0 + 1, cx1 - 1, cy1 - 1);
        g.pose().pushMatrix();
        g.pose().translate(cx0 + 6, cy0 + 6);
        g.pose().scale((float) z, (float) z);
        g.pose().translate((float) -panX, (float) -panY);
        // Boxes first, then every line over them, then the quests on top.
        for (QuestMap.Area a : geo.web().areas()) {
            ChapterView c = chapters.get(a.chapter());
            WebLayout.Box b = lay.boxes().get(a.chapter());
            int bx = b.x(), by = b.y(), bw = b.w(), bh = b.h();
            if (bx > vx1 || bx + bw < vx0 || by > vy1 || by + bh < vy0) continue;
            g.fill(bx, by, bx + bw, by + bh, 0x18000000);
            frame(g, bx, by, bx + bw, by + bh, BRASS_DIM, 1);
            Component title = bold(c.name());
            int tw = Math.min(font.width(title), bw - 12);
            boolean titleHover = mouseInCanvas && mx >= bx + 4 && mx < bx + 8 + tw && my >= by + 2 && my < by + BOX_HEAD - 2;
            g.text(font, ellipsis(titleHover ? title.copy().withStyle(ChatFormatting.UNDERLINE) : title, bw - 12), bx + 6, by + 4, GOLD);
            String chapter = c.id();
            canvasHot(bx + 4, by + 2, bx + 8 + tw, by + BOX_HEAD - 2, () -> goTo(chapter, "", true));
        }
        for (WebLayout.Path path : lay.paths()) {
            int colour = geo.status().getOrDefault(path.link().from(), JournalPayload.LOCKED) == JournalPayload.COMPLETE ? EDGE_DONE : EDGE_OPEN;
            int faded = (0x50 << 24) | (colour & 0xFFFFFF);
            for (WebLayout.Seg seg : path.segs()) {
                if (seg.x0() > vx1 || seg.x1() < vx0 || seg.y0() > vy1 || seg.y1() < vy0) continue;
                drawSeg(g, seg, colour, faded, path.link().hidden() ? 3 : 1, path.fades());
            }
        }
        for (QuestMap.Area a : geo.web().areas()) {
            ChapterView c = chapters.get(a.chapter());
            for (QuestMap.Placed pl : a.placed()) {
                WebLayout.Point cell = lay.cells().get(pl.id());
                if (cell.x() + CELL_W < vx0 || cell.x() - CELL_W > vx1 || cell.y() + CELL_H < vy0 || cell.y() > vy1) continue;
                int nx0 = cell.x() - (CELL_W - NODE) / 2 + 2, nx1 = nx0 + CELL_W - 4;
                boolean hover = mouseInCanvas && mx >= nx0 && mx < nx1 && my >= cell.y() - 2 && my < cell.y() + NODE + 12;
                if (QuestMap.hidden(pl.id())) {
                    mysteryNode(g, cell.x(), cell.y());
                    if (hover && mx >= cell.x() && mx < cell.x() + NODE && my >= cell.y() && my < cell.y() + NODE) {
                        tooltip = List.of(label("panel.overview.locked"));
                    }
                    continue;
                }
                QuestView q = findIn(c, pl.id());
                if (q == null) continue;
                node(g, cell.x(), cell.y(), q.icon(), q.status(), false, hover);
                FormattedCharSequence name = ellipsis(q.name(), CELL_W - 8);
                g.text(font, name, cell.x() + NODE / 2 - font.width(name) / 2, cell.y() + NODE + 3, nameColour(q.status()));
                if (hover) tooltip = List.of(q.name(), statusName(q.status()));
                String chapter = c.id(), quest = q.id();
                canvasHot(nx0, cell.y() - 2, nx1, cell.y() + NODE + 12, () -> goTo(chapter, quest, true));
            }
        }
        g.pose().popMatrix();
        g.disableScissor();
        if (tooltip != null) g.setTooltipForNextFrame(font, tooltip, Optional.empty(), mouseX, mouseY);
    }

    /** A click target given in overview units, kept on screen in screen units and clipped to the canvas. */
    private void canvasHot(int x0, int y0, int x1, int y1, Runnable action) {
        double z = zoom;
        int sx0 = (int) Math.floor(canvasX0 + 6 + (x0 - panX) * z), sx1 = (int) Math.ceil(canvasX0 + 6 + (x1 - panX) * z);
        int sy0 = (int) Math.floor(canvasY0 + 6 + (y0 - panY) * z), sy1 = (int) Math.ceil(canvasY0 + 6 + (y1 - panY) * z);
        sx0 = Math.max(sx0, canvasX0); sy0 = Math.max(sy0, canvasY0);
        sx1 = Math.min(sx1, canvasX1); sy1 = Math.min(sy1, canvasY1);
        if (sx1 > sx0 && sy1 > sy0) hotspots.add(new Hot(sx0, sy0, sx1, sy1, action, true));
    }

    /** One piece of a line, faded for {@link #FADE} either side of each place it passes behind another. */
    private static void drawSeg(GuiGraphicsExtractor g, WebLayout.Seg s, int colour, int faded, int step, List<WebLayout.Point> fades) {
        boolean hz = s.horizontal();
        int a0 = hz ? s.x0() : s.y0(), a1 = hz ? s.x1() : s.y1(), fixed = hz ? s.y0() : s.x0();
        List<Integer> at = new ArrayList<>();
        for (WebLayout.Point f : fades) {
            if (hz ? f.y() == fixed && f.x() >= a0 && f.x() <= a1 : f.x() == fixed && f.y() >= a0 && f.y() <= a1) at.add(hz ? f.x() : f.y());
        }
        at.sort(Integer::compare);
        int pos = a0;
        for (int c : at) {
            run(g, hz, fixed, pos, Math.min(a1, c - FADE - 1), colour, step);
            run(g, hz, fixed, Math.max(pos, c - FADE), Math.min(a1, c + FADE), faded, step);
            pos = Math.max(pos, c + FADE + 1);
        }
        run(g, hz, fixed, pos, a1, colour, step);
    }

    private static void run(GuiGraphicsExtractor g, boolean hz, int fixed, int lo, int hi, int colour, int step) {
        if (lo > hi) return;
        if (step == 1) {
            if (hz) g.fill(lo, fixed, hi + 1, fixed + 1, colour);
            else g.fill(fixed, lo, fixed + 1, hi + 1, colour);
            return;
        }
        for (int q = lo + Math.floorMod(-lo, step); q <= hi; q += step) {
            if (hz) g.fill(q, fixed, q + 1, fixed + 1, colour);
            else g.fill(fixed, q, fixed + 1, q + 1, colour);
        }
    }

    /** The overview's layout ({@link WebLayout}), worked out once per journal the server sends. */
    private Geo geo(JournalPayload p) {
        if (geo != null && geoFor == p) return geo;
        List<QuestMap.Chapter> chapters = new ArrayList<>();
        Map<String, Byte> status = new HashMap<>();
        for (ChapterView c : p.chapters()) {
            List<QuestMap.Entry> quests = new ArrayList<>();
            for (QuestView q : c.quests()) {
                quests.add(new QuestMap.Entry(q.id(), q.requires(), q.status() != JournalPayload.LOCKED));
                status.put(q.id(), q.status());
            }
            chapters.add(new QuestMap.Chapter(c.id(), quests));
        }
        QuestMap.Web web = QuestMap.web(chapters);
        geo = new Geo(web, WebLayout.of(web, WebLayout.JOURNAL), status);
        geoFor = p;
        return geo;
    }

    /** Something not open yet: an empty frame of dots with a question mark. */
    private void mysteryNode(GuiGraphicsExtractor g, int x, int y) {
        g.fill(x, y, x + NODE, y + NODE, NODE_BG);
        for (int i = 0; i < NODE; i += 3) {
            g.fill(x + i, y, x + i + 1, y + 1, EDGE_OPEN);
            g.fill(x + i, y + NODE - 1, x + i + 1, y + NODE, EDGE_OPEN);
            g.fill(x, y + i, x + 1, y + i + 1, EDGE_OPEN);
            g.fill(x + NODE - 1, y + i, x + NODE, y + i + 1, EDGE_OPEN);
        }
        Component q = label("panel.overview.unknown");
        g.text(font, q, x + NODE / 2 - font.width(q) / 2 + 1, y + NODE / 2 - 4, 0xFF8A837A);
    }

    /** On the go, on offer, the story so far: every line was worded by the server; a quest line is a link to its page. */
    private void renderHome(GuiGraphicsExtractor g, JournalPayload p, int mouseX, int mouseY) {
        int px = paneX(), pw = paneW() - 6, top = top(), bottom = bottom();
        g.enableScissor(px, top, px + pw, bottom);
        int y = top + 1 - (int) pageScroll;
        var home = p.home();

        g.text(font, bold(label("panel.home.active")), px, y, GOLD);
        y += 12;
        if (home.active().isEmpty()) y = paragraph(g, label("panel.home.nothing_active"), px, y, pw);
        for (var e : home.active()) y = entry(g, e, px, y, pw, mouseX, mouseY, top, bottom);

        y += 6;
        g.text(font, bold(label("panel.home.offers")), px, y, GOLD);
        y += 12;
        if (home.offers().isEmpty()) y = paragraph(g, label("panel.home.nothing_offered"), px, y, pw);
        for (var e : home.offers()) y = entry(g, e, px, y, pw, mouseX, mouseY, top, bottom);

        y += 6;
        g.text(font, bold(label("panel.home.recap")), px, y, GOLD);
        y += 12;
        if (home.recap().isEmpty()) y = paragraph(g, label("panel.home.no_recap"), px, y, pw);
        for (Component line : home.recap()) y = paragraph(g, line, px, y, pw) + 4;

        g.disableScissor();
        int contentH = y + (int) pageScroll - top;
        maxPageScroll = Math.max(0, contentH + 4 - (bottom - top));
        pageScroll = clamp(pageScroll, 0, maxPageScroll);
        scrollbar(g, px + pw + 3, top, bottom, pageScroll, maxPageScroll, contentH + 4);
    }

    /** A home-page line: a link to the quest's page when it names one, wrapped to the pane. */
    private int entry(GuiGraphicsExtractor g, JournalPayload.Entry e, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom) {
        ChapterView c = e.questId().isEmpty() ? null : chapterOf(e.questId());
        if (c == null) return paragraph(g, e.line(), x, y, w);
        String chapter = c.id(), id = e.questId();
        link(g, e.line(), x, y, w, mouseX, mouseY, clipTop, clipBottom, () -> goTo(chapter, id, true));
        return y + 11;
    }

    private int paragraph(GuiGraphicsExtractor g, Component text, int x, int y, int w) {
        for (FormattedCharSequence s : font.split(text, w)) {
            g.text(font, s, x, y, TEXT);
            y += 10;
        }
        return y;
    }

    private void renderPage(GuiGraphicsExtractor g, ChapterView ch, QuestView q, int mouseX, int mouseY) {
        if (q == null) return;
        int px = paneX(), pw = paneW() - 6, top = top(), bottom = bottom();
        g.enableScissor(px, top, px + pw, bottom);
        int y = top + 1 - (int) pageScroll;

        link(g, label("panel.to_map"), px, y, pw, mouseX, mouseY, top, bottom, () -> goTo(ch.id(), "", true));
        y += 14;
        g.item(icon(q.icon()), px, y);
        g.text(font, glyph(q.status()), px + 13, y - 3, TEXT);
        int nameY = y + 4;
        for (FormattedCharSequence s : font.split(bold(q.name()), pw - 22)) {
            g.text(font, s, px + 21, nameY, nameColour(q.status()));
            nameY += 10;
        }
        y = Math.max(y + 20, nameY + 4);
        if (!q.buttons().isEmpty()) y = buttonRow(g, q.buttons(), px, y, pw, mouseX, mouseY, top, bottom) + 5;

        for (Component line : q.lines()) {
            for (FormattedCharSequence s : font.split(line, pw)) {
                g.text(font, s, px, y, TEXT);
                y += 10;
            }
        }
        if (!q.requires().isEmpty()) {
            y += 4;
            g.text(font, label("panel.requires"), px, y, TEXT);
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

    private void node(GuiGraphicsExtractor g, int x, int y, String iconId, byte status, boolean external, boolean hover) {
        g.fill(x, y, x + NODE, y + NODE, NODE_BG);
        int colour = hover ? 0xFFFFFFFF : external ? BRASS_DIM : frameColour(status);
        frame(g, x, y, x + NODE, y + NODE, colour, status == JournalPayload.ACTIVE && !external ? 2 : 1);
        g.item(icon(iconId), x + 3, y + 3);
        if (external || status == JournalPayload.LOCKED) g.fill(x + 1, y + 1, x + NODE - 1, y + NODE - 1, 0x80000000);
        g.text(font, glyph(status), x + NODE - 4, y - 4, TEXT);
    }

    private static void edge(GuiGraphicsExtractor g, Cell from, Cell to, int colour) {
        int x0 = from.x() + NODE, y0 = from.y() + NODE / 2;
        int x1 = to.x() - 1, y1 = to.y() + NODE / 2;
        if (x1 <= x0) return; // only a cycle points backwards; the page still lists it
        int mid = Math.max(x0 + 2, x1 - (CELL_W - NODE) / 2);
        g.fill(x0, y0, mid + 1, y0 + 1, colour);
        g.fill(mid, Math.min(y0, y1), mid + 1, Math.max(y0, y1) + 1, colour);
        g.fill(mid, y1, x1 + 1, y1 + 1, colour);
    }

    /** A row of buttons that wraps; returns the y below it. Hover shows the button's tip. */
    private int buttonRow(GuiGraphicsExtractor g, List<Button> buttons, int x, int y, int maxW, int mouseX, int mouseY, int clipTop, int clipBottom) {
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
            g.text(font, b.label(), bx + 5, y + 3, TEXT);
            if (hover) g.setTooltipForNextFrame(font, b.tip(), mouseX, mouseY);
            String command = b.command();
            addClipped(bx, y, bx + w, y + 14, clipTop, clipBottom, () -> ClientJournal.run(command));
            bx += w + 4;
        }
        return y + 14;
    }

    private void link(GuiGraphicsExtractor g, Component text, int x, int y, int w, int mouseX, int mouseY, int clipTop, int clipBottom, Runnable action) {
        boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y - 1 && mouseY < y + 10
                && mouseY >= clipTop && mouseY < clipBottom;
        if (hover) g.fill(x - 1, y - 1, x + w, y + 10, HOVER);
        g.text(font, ellipsis(hover ? text.copy().withStyle(ChatFormatting.UNDERLINE) : text, w), x, y, 0xFF8FC8E8);
        addClipped(x, y - 1, x + w, y + 10, clipTop, clipBottom, action);
    }

    private void addClipped(int x0, int y0, int x1, int y1, int clipTop, int clipBottom, Runnable action) {
        int top = Math.max(y0, clipTop), bottom = Math.min(y1, clipBottom);
        if (bottom > top) hotspots.add(new Hot(x0, top, x1, bottom, action, false));
    }

    private void chip(GuiGraphicsExtractor g, int x, int y, int w, Component label, boolean enabled, int mouseX, int mouseY, Runnable action) {
        int h = 14;
        boolean hover = enabled && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, hover ? 0xFF33291E : 0xFF221A12);
        frame(g, x, y, x + w, y + h, hover ? GOLD : BRASS, 1);
        g.text(font, label, x + (w - font.width(label)) / 2, y + 3, enabled ? TEXT : 0xFF5A534B);
        if (enabled) hotspots.add(new Hot(x, y, x + w, y + h, action, false));
    }

    /**
     * A name in the list: trimmed to an ellipsis, except under the cursor or the
     * selection, where it slides so the whole of it can be read (the handbook's trick).
     */
    private void drawName(GuiGraphicsExtractor g, Component text, int x, int y, int avail, int colour, boolean live) {
        int over = font.width(text) - avail;
        if (over <= 0) {
            g.text(font, text, x, y, colour);
            return;
        }
        if (!live) {
            g.text(font, ellipsis(text, avail), x, y, colour);
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
        g.text(font, text, x - offset, y, colour);
        g.disableScissor();
    }

    private FormattedCharSequence ellipsis(Component text, int width) {
        if (font.width(text) <= width) return text.getVisualOrderText();
        FormattedText cut = font.substrByWidth(text, Math.max(0, width - font.width("…")));
        return Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of("…")));
    }

    private void scrollbar(GuiGraphicsExtractor g, int x, int top, int bottom, double scroll, double max, int contentH) {
        if (max <= 0) return;
        int h = bottom - top;
        g.fill(x, top, x + 2, bottom, 0x50000000);
        int thumb = Math.max(10, h * h / Math.max(h, contentH));
        int thumbY = top + (int) ((h - thumb) * (scroll / max));
        g.fill(x, thumbY, x + 2, thumbY + thumb, 0xC0D9A441);
    }

    private static void frame(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int colour, int t) {
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
        // 26.3 moved input to SDL, which numbers the mouse from 1 (left=1, right=3, back=4): the named
        // constants follow the backend, a literal 0 does not -- it made the journal unclickable.
        if (event.button() == InputConstants.MOUSE_BUTTON_4) {
            goBack();
            return true;
        }
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
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
        if (pressedInCanvas && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (Math.abs(event.x() - pressX) + Math.abs(event.y() - pressY) > 3) dragged = true;
            if (dragged) {
                double z = OVERVIEW.equals(chapterId) ? zoom : 1; // the overview pans in its own (scaled) units
                panX = clamp(panX - dx / z, 0, maxPanX);
                panY = clamp(panY - dy / z, 0, maxPanY);
            }
            return true;
        }
        return super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (pressedInCanvas && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
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
        } else if (OVERVIEW.equals(chapterId)) {
            // The wheel zooms, about the point under the cursor; drag to move.
            if (scrollY == 0) return true;
            double old = zoom;
            zoom = clamp(zoom * (scrollY > 0 ? 1.15 : 1 / 1.15), ZOOM_MIN, ZOOM_MAX);
            double ax = (mouseX - canvasX0 - 6) / old + panX, ay = (mouseY - canvasY0 - 6) / old + panY;
            panX = Math.max(0, ax - (mouseX - canvasX0 - 6) / zoom);
            panY = Math.max(0, ay - (mouseY - canvasY0 - 6) / zoom);
        } else if (questId.isEmpty() && !HOME.equals(chapterId)) {
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
