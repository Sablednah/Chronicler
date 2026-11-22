package com.sablednah.chronicler.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Where everything on the journal's overview goes, in its own units (the screen scales and pans them). Loader-light
 * so the self-test can check the routing on real chapters: a box per chapter in its {@link QuestMap.Area#col()
 * column}, each level with the first act it follows; the quests in each; and every line, routed so it never runs
 * through another chapter's box -- out of its own box, along a track of its own in the gap between columns, and, to
 * reach a box more than one column on, along a clear lane between the boxes in the way. Where two lines with nothing
 * in common still cross, the longer one fades there ({@link Path#fades()}) as it passes behind.
 */
public final class WebLayout {
    private WebLayout() {}

    /** Sizes, in the overview's units: a quest's cell, its icon square, a box's title band, the gaps. */
    public record Metrics(int cellW, int cellH, int node, int boxHead, int boxGap, int colGap) {}

    /** The journal screen's sizes (its chapter maps share the cell and icon); the self-test checks with these. */
    public static final Metrics JOURNAL = new Metrics(88, 42, 22, 15, 14, 30);

    public record Point(int x, int y) {}

    /** A chapter's box. */
    public record Box(int x, int y, int w, int h) {
        public boolean contains(int px, int py) {
            return px > x && px < x + w && py > y && py < y + h;
        }
    }

    /** An axis-aligned piece of a line, ends in order (x0 <= x1, y0 <= y1). */
    public record Seg(int x0, int y0, int x1, int y1) {
        public static Seg of(int ax, int ay, int bx, int by) {
            return new Seg(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
        }
        public boolean horizontal() { return y0 == y1; }
        public int length() { return (x1 - x0) + (y1 - y0); }
    }

    /** One line; {@code fades} are the points where it passes behind a line it has nothing to do with. */
    public record Path(QuestMap.Link link, List<Seg> segs, List<Point> fades) {
        public int length() { return segs.stream().mapToInt(Seg::length).sum(); }
    }

    public record Layout(Map<String, Box> boxes, Map<String, Point> cells, Map<String, String> chapterOf, List<Path> paths, int w, int h) {}

    /** Where parallel lines sit in a gap, by the order their quests claim it: centre first, then either side. */
    private static final int[] TRACKS = {0, 3, -3, 6, -6, 9, -9, 12, -12};

    public static Layout of(QuestMap.Web web, Metrics m) {
        // Boxes: columns as wide as their widest box.
        Map<Integer, Integer> colW = new HashMap<>();
        Map<String, int[]> size = new HashMap<>();
        int lastCol = 0;
        for (QuestMap.Area a : web.areas()) {
            int cols = 1, rows = 1;
            for (QuestMap.Placed pl : a.placed()) {
                cols = Math.max(cols, pl.col() + 1);
                rows = Math.max(rows, pl.row() + 1);
            }
            int w = Math.max(cols, 1) * m.cellW() + 8, h = m.boxHead() + rows * m.cellH() + 2;
            size.put(a.chapter(), new int[] {w, h});
            colW.merge(a.col(), w, Math::max);
            lastCol = Math.max(lastCol, a.col());
        }
        int[] colX = new int[lastCol + 2];
        for (int c = 0; c <= lastCol; c++) colX[c + 1] = colX[c] + colW.getOrDefault(c, 0) + m.colGap();
        Map<String, Box> boxes = new HashMap<>();
        Map<Integer, Integer> colBottom = new HashMap<>();
        List<QuestMap.Area> byCol = new ArrayList<>(web.areas());
        byCol.sort(Comparator.comparingInt(QuestMap.Area::col)); // stable: story order within a column
        int totalW = 0, totalH = 0;
        for (QuestMap.Area a : byCol) {
            int[] wh = size.get(a.chapter());
            int by = colBottom.getOrDefault(a.col(), 0);
            for (String before : a.after()) { // level with the act it follows, so a chain reads straight across
                Box b = boxes.get(before);
                if (b != null) { by = Math.max(by, b.y()); break; }
            }
            boxes.put(a.chapter(), new Box(colX[a.col()], by, wh[0], wh[1]));
            colBottom.put(a.col(), by + wh[1] + m.boxGap());
            totalW = Math.max(totalW, colX[a.col()] + wh[0]);
            totalH = Math.max(totalH, by + wh[1]);
        }

        Map<String, Point> cells = new HashMap<>();
        Map<String, QuestMap.Area> areaOf = new HashMap<>();
        Map<String, Integer> colOf = new HashMap<>();
        Map<String, String> chapterOf = new HashMap<>();
        for (QuestMap.Area a : web.areas()) {
            Box b = boxes.get(a.chapter());
            int nx = b.x() + (m.cellW() - m.node()) / 2, ny = b.y() + m.boxHead() + 4;
            for (QuestMap.Placed pl : a.placed()) {
                cells.put(pl.id(), new Point(nx + pl.col() * m.cellW(), ny + pl.row() * m.cellH()));
                areaOf.put(pl.id(), a);
                colOf.put(pl.id(), pl.col());
                chapterOf.put(pl.id(), a.chapter());
            }
        }

        // Tracks: each quest a line leaves from gets its own place in a gap, so two lines share a run only when they
        // come from the same quest (a branch, honestly drawn).
        Map<String, Integer> claimed = new HashMap<>();
        Map<String, Integer> taken = new HashMap<>();
        BiFunction<String, String, Integer> track = (gap, from) ->
                TRACKS[claimed.computeIfAbsent(gap + "|" + from, k -> taken.merge(gap, 1, Integer::sum) - 1) % TRACKS.length];
        List<Path> paths = new ArrayList<>();
        int bottom = totalH;
        int half = m.node() / 2;
        for (QuestMap.Link l : web.links()) {
            Point f = cells.get(l.from()), t = cells.get(l.to());
            if (f == null || t == null) continue;
            QuestMap.Area fa = areaOf.get(l.from()), ta = areaOf.get(l.to());
            int x0 = f.x() + m.node(), y0 = f.y() + half, x1 = t.x() - 1, y1 = t.y() + half;
            List<Seg> segs = new ArrayList<>();
            if (fa == ta) {
                if (x1 <= x0) continue; // only a cycle points backwards
                int nudge = Math.max(-3, Math.min(3, track.apply("box|" + ta.chapter() + "|" + colOf.get(l.to()), l.from())));
                int mid = Math.max(x0 + 2, x1 - (m.cellW() - m.node()) / 2 + nudge);
                segs.add(Seg.of(x0, y0, mid, y0));
                segs.add(Seg.of(mid, y0, mid, y1));
                segs.add(Seg.of(mid, y1, x1, y1));
            } else {
                int sc = fa.col(), tc = ta.col();
                if (tc <= sc) continue;
                int xs = colX[sc + 1] - m.colGap() / 2 + track.apply("gap|" + sc, l.from());
                if (tc == sc + 1) {
                    segs.add(Seg.of(x0, y0, xs, y0));
                    segs.add(Seg.of(xs, y0, xs, y1));
                    segs.add(Seg.of(xs, y1, x1, y1));
                } else {
                    int xt = colX[tc] - m.colGap() / 2 + track.apply("gap|" + (tc - 1), l.from());
                    int lane = lane(web, boxes, sc + 1, tc - 1, (y0 + y1) / 2, l.from(), claimed, taken);
                    bottom = Math.max(bottom, lane + 6);
                    segs.add(Seg.of(x0, y0, xs, y0));
                    segs.add(Seg.of(xs, y0, xs, lane));
                    segs.add(Seg.of(xs, lane, xt, lane));
                    segs.add(Seg.of(xt, lane, xt, y1));
                    segs.add(Seg.of(xt, y1, x1, y1));
                }
            }
            segs.removeIf(sg -> sg.length() == 0);
            paths.add(new Path(l, segs, new ArrayList<>()));
        }

        // Crossings between lines with nothing in common: the longer one goes behind.
        for (int i = 0; i < paths.size(); i++) {
            Path a = paths.get(i);
            for (int j = i + 1; j < paths.size(); j++) {
                Path b = paths.get(j);
                if (related(a.link(), b.link())) continue;
                Path behind = a.length() >= b.length() ? a : b;
                for (Seg sa : a.segs()) {
                    for (Seg sb : b.segs()) {
                        Point x = crossing(sa, sb);
                        if (x != null) behind.fades().add(x);
                    }
                }
            }
        }
        return new Layout(boxes, cells, chapterOf, paths, totalW, bottom);
    }

    /** Two lines that share a quest at either end meet honestly; only unrelated ones "cross". */
    public static boolean related(QuestMap.Link a, QuestMap.Link b) {
        return a.from().equals(b.from()) || a.to().equals(b.to()) || a.to().equals(b.from()) || b.to().equals(a.from());
    }

    /** Where a horizontal and a vertical piece cross inside both (not at an end), or null. */
    public static Point crossing(Seg sa, Seg sb) {
        if (sa.horizontal() == sb.horizontal()) return null;
        Seg h = sa.horizontal() ? sa : sb, v = sa.horizontal() ? sb : sa;
        if (v.x0() > h.x0() + 1 && v.x0() < h.x1() - 1 && h.y0() > v.y0() + 1 && h.y0() < v.y1() - 1) return new Point(v.x0(), h.y0());
        return null;
    }

    /**
     * A height at which a line can cross columns {@code c0..c1} without entering any box in them: the clear band
     * between boxes nearest to where it wants to be, or below them all. Lines from different quests in one band
     * take tracks of their own.
     */
    private static int lane(QuestMap.Web web, Map<String, Box> boxes, int c0, int c1, int want, String from,
                            Map<String, Integer> claimed, Map<String, Integer> taken) {
        List<int[]> blocked = new ArrayList<>();
        for (QuestMap.Area a : web.areas()) {
            if (a.col() < c0 || a.col() > c1) continue;
            Box b = boxes.get(a.chapter());
            blocked.add(new int[] {b.y() - 4, b.y() + b.h() + 4});
        }
        if (blocked.isEmpty()) return want;
        blocked.sort(Comparator.comparingInt(r -> r[0]));
        List<int[]> merged = new ArrayList<>();
        for (int[] r : blocked) {
            int[] last = merged.isEmpty() ? null : merged.get(merged.size() - 1);
            if (last != null && r[0] <= last[1]) last[1] = Math.max(last[1], r[1]);
            else merged.add(r.clone());
        }
        // Clear bands: above the first box (when there is room), between boxes, and open-ended below the last.
        List<int[]> bands = new ArrayList<>();
        if (merged.get(0)[0] > 2) bands.add(new int[] {Math.min(0, merged.get(0)[0] - 6), merged.get(0)[0]});
        for (int i = 1; i < merged.size(); i++) bands.add(new int[] {merged.get(i - 1)[1], merged.get(i)[0]});
        int below = merged.get(merged.size() - 1)[1];
        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int[] band : bands) {
            int d = want < band[0] ? band[0] - want : want > band[1] ? want - band[1] : 0;
            if (d < bestDistance) { bestDistance = d; best = band; }
        }
        if (best == null || Math.max(0, below - want) < bestDistance) {
            String key = "lane|below|" + c0 + "|" + c1;
            int i = claimed.computeIfAbsent(key + "|" + from, k -> taken.merge(key, 1, Integer::sum) - 1);
            return below + 3 + 3 * i;
        }
        int centre = (best[0] + best[1]) / 2, room = (best[1] - best[0]) / 2;
        String key = "lane|" + best[0] + "|" + best[1];
        int i = claimed.computeIfAbsent(key + "|" + from, k -> taken.merge(key, 1, Integer::sum) - 1);
        return centre + Math.max(-room, Math.min(room, TRACKS[i % TRACKS.length]));
    }

    /** Every place a line between two chapters enters a third chapter's box: what the routing must never do. */
    public static List<String> trespasses(Layout layout) {
        List<String> out = new ArrayList<>();
        for (Path p : layout.paths()) {
            String fc = layout.chapterOf().get(p.link().from()), tc = layout.chapterOf().get(p.link().to());
            if (fc == null || fc.equals(tc)) continue;
            for (var e : layout.boxes().entrySet()) {
                if (e.getKey().equals(fc) || e.getKey().equals(tc)) continue;
                Box b = e.getValue();
                for (Seg s : p.segs()) {
                    boolean in = s.horizontal()
                            ? s.y0() > b.y() && s.y0() < b.y() + b.h() && s.x1() > b.x() && s.x0() < b.x() + b.w()
                            : s.x0() > b.x() && s.x0() < b.x() + b.w() && s.y1() > b.y() && s.y0() < b.y() + b.h();
                    if (in) { out.add(p.link().from() + "->" + p.link().to() + " through " + e.getKey()); break; }
                }
            }
        }
        return out;
    }
}
