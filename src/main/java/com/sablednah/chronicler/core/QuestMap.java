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
