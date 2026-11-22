package com.sablednah.chronicler.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Where each quest of a chapter sits on the quest map: one column per step of
 * prerequisites, one row per branch. Loader-light and deterministic, so the
 * self-test checks the same layout the client draws.
 *
 * <p>Prerequisites from other chapters are the rule in a long questline, not
 * the exception -- every ZARP side chapter hangs off The Camp -- so they get
 * the left-hand column to themselves, drawn as stand-ins. A cycle in the data
 * (nothing at load forbids one) is broken where it is met rather than recursed
 * into for ever.</p>
 */
public final class QuestMap {

    /** A quest on the map; {@code requires} may name quests outside it. */
    public record Node(String id, List<String> requires, int order) {}

    /** A cell. {@code external}: a prerequisite from elsewhere, not a quest of this chapter. */
    public record Placed(String id, int col, int row, boolean external) {}

    public static List<Placed> layout(List<Node> nodes) {
        Map<String, Node> byId = new LinkedHashMap<>();
        for (Node n : nodes) byId.putIfAbsent(n.id(), n);

        List<String> externals = new ArrayList<>();
        for (Node n : byId.values()) {
            for (String r : n.requires()) {
                if (!byId.containsKey(r) && !externals.contains(r)) externals.add(r);
            }
        }
        int base = externals.isEmpty() ? 0 : 1;

        Map<String, Integer> depth = new HashMap<>();
        for (String id : byId.keySet()) depthOf(id, byId, base, depth, new HashSet<>());

        List<Placed> out = new ArrayList<>();
        Map<String, Integer> rowOf = new HashMap<>();
        for (int i = 0; i < externals.size(); i++) {
            out.add(new Placed(externals.get(i), 0, i, true));
            rowOf.put(externals.get(i), i);
        }

        int last = depth.values().stream().max(Integer::compare).orElse(base);
        for (int col = base; col <= last; col++) {
            final int c = col;
            List<Node> here = new ArrayList<>(byId.values().stream().filter(n -> depth.get(n.id()) == c).toList());
            // Each quest wants the average row of its parents, so a chain runs straight across;
            // quests with no parent on the map queue after the rest, in file order.
            Map<String, Double> wants = new HashMap<>();
            for (Node n : here) {
                double sum = 0;
                int k = 0;
                for (String r : n.requires()) {
                    Integer y = rowOf.get(r);
                    if (y != null) { sum += y; k++; }
                }
                wants.put(n.id(), k == 0 ? Double.MAX_VALUE : sum / k);
            }
            here.sort(Comparator.comparingDouble((Node n) -> wants.get(n.id()))
                    .thenComparingInt(Node::order).thenComparing(Node::id));
            int next = 0;
            for (Node n : here) {
                double w = wants.get(n.id());
                int row = w == Double.MAX_VALUE ? next : Math.max(next, (int) Math.round(w));
                out.add(new Placed(n.id(), col, row, false));
                rowOf.put(n.id(), row);
                next = row + 1;
            }
        }
        return out;
    }

    /** A quest for {@link #web}: {@code open} is anything not locked (on offer, under way, done). */
    public record Entry(String id, List<String> requires, boolean open) {}

    /** A chapter for {@link #web}, its quests in file order. */
    public record Chapter(String id, List<Entry> quests) {}

    /** A line on the overview: {@code hidden} when it leads into a stand-in for what is not open yet (drawn dotted). */
    public record Link(String from, String to, boolean hidden) {}

    /**
     * One chapter's box: its column among the boxes (a chapter sits right of every chapter it follows), the
     * chapters it follows ({@code after}, for lining a box up with them), and its own map inside.
     */
    public record Area(String chapter, int col, List<String> after, List<Placed> placed) {}

    /** The whole overview: a box per chapter and every line, within a box or between two. */
    public record Web(List<Area> areas, List<Link> links) {}

    /** The id prefix of a stand-in for what is not open yet. */
    public static final String HIDDEN = "?";

    public static boolean hidden(String id) {
        return id.startsWith(HIDDEN);
    }

    /**
     * Every chapter at once, joined up. A box holds a chapter's open quests, laid out on their links within the
     * chapter; a line runs to each from what it needs, in this chapter or another. Where open quests lead on into
     * locked ones, a stand-in takes their place -- one per chapter per distinct set of open quests they wait on,
     * however many there are -- and a locked quest that waits on nothing drawn at all (a flag, a quest out of sight)
     * gets the chapter's lone stand-in. A locked quest behind another locked quest is not drawn, so the overview says
     * "more here, after this", never what or how much. A chapter with nothing to draw is one stand-in.
     * <p>The boxes run left to right by what follows what: a chapter's column is one past the furthest chapter any of
     * its quests needs (locked ones included -- that places a box, it shows nothing about it).
     */
    public static Web web(List<Chapter> chapters) {
        Map<String, String> chapterOf = new HashMap<>();
        Set<String> open = new HashSet<>();
        for (Chapter c : chapters) {
            for (Entry q : c.quests()) {
                chapterOf.putIfAbsent(q.id(), c.id());
                if (q.open()) open.add(q.id());
            }
        }
        // What follows what, from every requirement, so a locked act still sits after the act that opens it.
        Map<String, Set<String>> follows = new LinkedHashMap<>();
        for (Chapter c : chapters) {
            Set<String> after = new java.util.LinkedHashSet<>();
            for (Entry q : c.quests()) {
                for (String r : q.requires()) {
                    String rc = chapterOf.get(r);
                    if (rc != null && !rc.equals(c.id())) after.add(rc);
                }
            }
            follows.put(c.id(), after);
        }
        Map<String, Integer> col = new HashMap<>();
        for (Chapter c : chapters) chapterDepth(c.id(), follows, col, new HashSet<>());

        List<Area> areas = new ArrayList<>();
        List<Link> links = new ArrayList<>();
        for (Chapter c : chapters) {
            List<Node> nodes = new ArrayList<>();
            Map<String, List<String>> standIns = new LinkedHashMap<>();
            int order = 0;
            for (Entry q : c.quests()) {
                if (q.open()) {
                    List<String> needs = q.requires().stream().filter(open::contains).toList();
                    nodes.add(new Node(q.id(), needs.stream().filter(r -> c.id().equals(chapterOf.get(r))).toList(), order++));
                    for (String r : needs) links.add(new Link(r, q.id(), false));
                    continue;
                }
                List<String> waits = q.requires().stream().filter(open::contains).sorted().toList();
                boolean root = q.requires().stream().noneMatch(chapterOf::containsKey);
                if (waits.isEmpty() && !root) continue; // behind something itself hidden
                standIns.putIfAbsent(HIDDEN + c.id() + "|" + String.join(",", waits), waits);
            }
            if (nodes.isEmpty() && standIns.isEmpty()) standIns.put(HIDDEN + c.id() + "|", List.of());
            for (var h : standIns.entrySet()) {
                nodes.add(new Node(h.getKey(), h.getValue().stream().filter(r -> c.id().equals(chapterOf.get(r))).toList(), order++));
                for (String w : h.getValue()) links.add(new Link(w, h.getKey(), true));
            }
            areas.add(new Area(c.id(), col.getOrDefault(c.id(), 0), List.copyOf(follows.get(c.id())), layout(nodes)));
        }
        return new Web(areas, links);
    }

    private static int chapterDepth(String id, Map<String, Set<String>> follows, Map<String, Integer> memo, Set<String> visiting) {
        Integer known = memo.get(id);
        if (known != null) return known;
        if (!visiting.add(id)) return 0; // a cycle between chapters: break it here
        int d = 0;
        for (String before : follows.getOrDefault(id, Set.of())) d = Math.max(d, chapterDepth(before, follows, memo, visiting) + 1);
        visiting.remove(id);
        memo.put(id, d);
        return d;
    }

    private static int depthOf(String id, Map<String, Node> byId, int base, Map<String, Integer> memo, Set<String> visiting) {
        Integer known = memo.get(id);
        if (known != null) return known;
        if (!visiting.add(id)) return base; // a cycle: break it here
        int d = base;
        for (String r : byId.get(id).requires()) {
            if (byId.containsKey(r)) d = Math.max(d, depthOf(r, byId, base, memo, visiting) + 1);
        }
        visiting.remove(id);
        memo.put(id, d);
        return d;
    }

    private QuestMap() {}
}
