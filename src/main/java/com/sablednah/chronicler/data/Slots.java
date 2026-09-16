package com.sablednah.chronicler.data;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Filling a mini quest's holes. Loader-light: JSON in, JSON out, so the self-test and the
 * registry loader run exactly this.
 *
 * <p>A string that is <em>only</em> a hole becomes a value of the right kind -- {@code "{dest.x}"}
 * a number, {@code "{dest.pos}"} an {@code [x, y, z]} list -- so {@code "x": "{dest.x}"} decodes
 * where an integer is wanted. A hole inside longer text is replaced as text. A hole naming no
 * slot is left alone: {@code {player}} in a command reward is the command's, not ours.</p>
 */
public final class Slots {

    private static final Pattern HOLE = Pattern.compile("\\{([a-z0-9_]+)(?:\\.([a-z]+))?\\}");
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");
    public static final String NIL_UUID = new UUID(0L, 0L).toString();

    /** Fill every hole in a quest file, leaving its {@code mini} block as written. */
    public static JsonElement fill(JsonElement in, Map<String, String> values, Mini mini) {
        if (in instanceof JsonObject o) {
            JsonObject out = new JsonObject();
            for (var e : o.entrySet()) {
                out.add(e.getKey(), e.getKey().equals("mini") ? e.getValue().deepCopy() : fill(e.getValue(), values, mini));
            }
            return out;
        }
        if (in instanceof JsonArray a) {
            JsonArray out = new JsonArray();
            for (JsonElement el : a) out.add(fill(el, values, mini));
            return out;
        }
        if (in instanceof JsonPrimitive p && p.isString()) {
            String s = p.getAsString();
            Matcher whole = HOLE.matcher(s);
            if (whole.matches() && mini.slots().containsKey(whole.group(1))) {
                String key = whole.group(2) == null ? whole.group(1) : whole.group(1) + "." + whole.group(2);
                String v = values.get(key);
                if (v == null) return p;
                return typed(mini.slots().get(whole.group(1)), whole.group(2), v);
            }
            return new JsonPrimitive(text(s, values, mini));
        }
        return in;
    }

    /** Replace the holes in a line of text; holes naming no slot, or no value yet, stay as written. */
    public static String text(String s, Map<String, String> values, Mini mini) {
        if (s.indexOf('{') < 0) return s;
        Matcher m = HOLE.matcher(s);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String key = m.group(2) == null ? m.group(1) : m.group(1) + "." + m.group(2);
            String v = mini.slots().containsKey(m.group(1)) ? values.get(key) : null;
            m.appendReplacement(out, Matcher.quoteReplacement(v == null ? m.group() : v));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static JsonElement typed(Mini.Slot slot, String sub, String v) {
        if ("pos".equals(sub)) {
            String[] xyz = v.trim().split("\\s+");
            JsonArray arr = new JsonArray();
            try {
                for (String n : xyz) arr.add(Integer.parseInt(n));
                return arr;
            } catch (NumberFormatException e) {
                return new JsonPrimitive(v);
            }
        }
        boolean numeric = (sub != null && (sub.equals("x") || sub.equals("y") || sub.equals("z")))
                || (sub == null && slot.type().equals("number"));
        if (numeric && NUMBER.matcher(v).matches()) {
            return v.contains(".") ? new JsonPrimitive(Double.parseDouble(v)) : new JsonPrimitive(Long.parseLong(v));
        }
        return new JsonPrimitive(v);
    }

    /** The values a place slot gives. */
    public static void putPlace(Map<String, String> into, String slot, String label, String dimension, int x, int y, int z) {
        into.put(slot, label);
        into.put(slot + ".x", Integer.toString(x));
        into.put(slot + ".y", Integer.toString(y));
        into.put(slot + ".z", Integer.toString(z));
        into.put(slot + ".pos", x + " " + y + " " + z);
        into.put(slot + ".dim", dimension);
    }

    /**
     * Stand-in values for checking a template at load: the first of each pool, the least of each
     * range, a place at 0 64 0. A file that does not decode with these is refused at load, loudly,
     * like any other bad quest file -- not the first time a player starts it.
     */
    public static Map<String, String> samples(Mini mini) {
        Map<String, String> v = new LinkedHashMap<>();
        for (var e : mini.slots().entrySet()) {
            String name = e.getKey();
            Mini.Slot s = e.getValue();
            switch (s.type()) {
                case "pick" -> { v.put(name, s.pool().getFirst()); v.put(name + ".name", com.sablednah.chronicler.neoforge.Lang.pretty(s.pool().getFirst())); }
                case "number" -> v.put(name, Integer.toString(s.min()));
                case "npc" -> {
                    putPlace(v, name, text(s.person().name().orElse(name), v, mini), "minecraft:overworld", 0, 64, 0);
                    v.put(name + ".id", NIL_UUID);
                }
                default -> putPlace(v, name, text(s.label().orElse(name), v, mini), "minecraft:overworld", 0, 64, 0);
            }
        }
        return v;
    }

    private Slots() {}
}
