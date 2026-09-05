package com.sablednah.chronicler.neoforge;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.sablednah.chronicler.Chronicler;

import net.neoforged.fml.loading.FMLPaths;

/**
 * Every string the server ever shows a player, keyed and owner-editable.
 *
 * <p>Not vanilla translatable components: a vanilla client does not carry our
 * lang file and would see raw keys. The server resolves text itself from
 * {@code config/chronicler/messages.yml}, which owners edit for translation OR
 * terminology -- a zombie server sets {@code term.quest} to "Mission" and every
 * message follows.</p>
 *
 * <p><b>The merge is the load-bearing half.</b> Writing the file only when it
 * is absent means every key added after a server's first run is missing from
 * that server's file forever -- and nothing looks broken, because {@link #get}
 * falls back to the defaults. So {@link #load} appends keys the installation
 * has never been offered, tracked in {@code messages.known} beside it. Known
 * rather than "absent from the file", because the header invites trimming the
 * file to just your changes. (Standards measured this: 146 of 222 keys
 * uncustomisable on its own dev world before the merge existed.)</p>
 */
public final class Lang {

    /** Baked defaults: the complete catalogue. Registration order = file order. */
    private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();
    private static Map<String, String> active = new LinkedHashMap<>();

    static String def(String key, String template) {
        DEFAULTS.put(key, template);
        return key;
    }

    // --- term.* : the nouns a genre re-skins ---
    static {
        def("term.quest", "Quest");
        def("term.quests", "Quests");
        def("term.chapter", "Chapter");
        def("term.chapters", "Chapters");
        def("term.objective", "Objective");
        def("term.objectives", "Objectives");
        def("term.reward", "Reward");
        def("term.rewards", "Rewards");
        def("term.journal", "Journal");
    }

    // --- prefix and status ---
    static {
        def("prefix", "&6[{term.quests}]&r ");
        def("status.active", "&ein progress");
        def("status.complete", "&acomplete");
        def("status.available", "&favailable");
        def("status.locked", "&8locked");
        def("status.hidden", "&8[hidden]");
        def("status.repeatable", "&7(repeatable)");
    }

    // --- cmd.* : command output ---
    static {
        def("cmd.list.header", "{prefix}&f{term.chapters} and {term.quests}:");
        def("cmd.list.chapter", "&6&l{name}&r &7- {description}");
        def("cmd.list.quest", "  &f{name} &7[{id}] {status}");
        def("cmd.list.empty", "{prefix}&7No {term.quests} are loaded. Drop one in config/chronicler/quests/ and restart.");
        def("cmd.list.orphan_chapter", "&8(quests in chapter '{id}', which does not exist)");
        def("cmd.info.header", "{prefix}&6&l{name}&r &7[{id}]");
        def("cmd.info.chapter", "&7{term.chapter}: &f{chapter}");
        def("cmd.info.description", "&7{description}");
        def("cmd.info.requires", "&7Requires: &f{list}");
        def("cmd.info.objectives", "&e{term.objectives}:");
        def("cmd.info.objective", "  &7- &f{line}");
        def("cmd.info.rewards", "&a{term.rewards}:");
        def("cmd.info.reward", "  &7- &f{line}");
        def("cmd.info.none", "  &8(none)");
        def("cmd.log.header", "{prefix}&fYour {term.journal}: &e{active} &7in progress, &a{completed} &7complete.");
        def("cmd.log.empty", "{prefix}&7Your {term.journal} is empty. /quest list shows what is on offer.");
        def("cmd.unknown_quest", "No {term.quest} called '{id}'. /quest list names them.");
        def("cmd.ambiguous", "More than one {term.quest} is called that: {ids}. Use the full id.");
        def("cmd.reload_notice", "{prefix}&7messages.yml applied. {term.chapters} and {term.quests} are frozen registries: content changes apply on server RESTART.");
        def("cmd.status", "{prefix}&f{chapters} {term.chapters}, {quests} {term.quests}, {objectives} objective types, {rewards} reward types.");
        def("cmd.status.config", "&7Config: &f{path}");
        def("cmd.status.siblings", "&7Siblings: &f{list}");
    }

    // --- obj.* / rew.* : generated from the data so text and rule agree ---
    static {
        def("obj.kill", "Kill {count} x {target}");
        def("obj.collect", "Hand over {count} x {item}");
        def("obj.hold", "Carry {count} x {item}");
        def("obj.visit", "Reach {x}, {z}");
        def("obj.visit_label", "Reach {label}");
        def("rew.item", "{count} x {item}");
        def("rew.command", "Something happens.");
        def("rew.xp", "{amount} experience");
        def("rew.money", "{amount} coin");
    }

    /** Resolve a key to its (term- and prefix-substituted) template. Unknown key = the key, loudly. */
    public static String get(String key) {
        String template = active.getOrDefault(key, DEFAULTS.get(key));
        if (template == null) {
            Chronicler.LOGGER.warn("Missing message key '{}'", key);
            return key;
        }
        return substitute(template);
    }

    /** {@code fmt("cmd.info.chapter", "chapter", name)} -- key/value pairs. */
    public static String fmt(String key, Object... kv) {
        String out = get(key);
        for (int n = 0; n + 1 < kv.length; n += 2) {
            out = out.replace("{" + kv[n] + "}", String.valueOf(kv[n + 1]));
        }
        return out;
    }

    public static String term(String name) {
        return get("term." + name);
    }

    /** {@code minecraft:rotten_flesh} / {@code #minecraft:zombies} / {@code any} -> "Rotten Flesh". */
    public static String pretty(String id) {
        String s = id.startsWith("#") ? id.substring(1) : id;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        int slash = s.lastIndexOf('/');
        if (slash >= 0) s = s.substring(slash + 1);
        StringBuilder out = new StringBuilder();
        for (String word : s.split("_")) {
            if (word.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return out.toString();
    }

    private static String substitute(String template) {
        String out = template;
        if (out.contains("{prefix}")) {
            out = out.replace("{prefix}", active.getOrDefault("prefix", DEFAULTS.get("prefix")));
        }
        if (!out.contains("{term.")) return out;
        for (String key : DEFAULTS.keySet()) {
            if (!key.startsWith("term.")) continue;
            String marker = "{" + key + "}";
            if (out.contains(marker)) {
                out = out.replace(marker, active.getOrDefault(key, DEFAULTS.get(key)));
            }
        }
        return out;
    }

    /** Every key we ship, for the self-test and the status command. */
    public static int catalogueSize() {
        return DEFAULTS.size();
    }

    // --- file lifecycle ---

    private static Path dir() {
        return FMLPaths.CONFIGDIR.get().resolve(Chronicler.MODID);
    }

    private static Path file() {
        return dir().resolve("messages.yml");
    }

    private static Path knownFile() {
        return dir().resolve("messages.known");
    }

    /** Load overrides; write the catalogue on first run; append never-offered keys thereafter. */
    public static synchronized void load() {
        Path path = file();
        try {
            Files.createDirectories(path.getParent());
            if (!Files.exists(path)) {
                writeDefaults(path);
            } else {
                mergeNewKeys(path);
            }
            Map<String, String> loaded = new LinkedHashMap<>();
            Object parsed = new org.yaml.snakeyaml.Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
            if (parsed instanceof Map<?, ?> map) {
                map.forEach((k, v) -> {
                    if (k != null && v != null) loaded.put(String.valueOf(k), String.valueOf(v));
                });
            }
            active = loaded;
            Files.writeString(knownFile(), String.join("\n", DEFAULTS.keySet()) + "\n", StandardCharsets.UTF_8);
            Chronicler.LOGGER.info("Chronicler: {} message override(s) from messages.yml ({} keys in the catalogue)",
                    loaded.size(), DEFAULTS.size());
        } catch (Exception e) {
            Chronicler.LOGGER.error("Could not load messages.yml -- using defaults", e);
            active = new LinkedHashMap<>();
        }
    }

    private static Set<String> readKnown() {
        Path known = knownFile();
        if (!Files.exists(known)) return Set.of();
        try {
            return new LinkedHashSet<>(Files.readAllLines(known, StandardCharsets.UTF_8).stream()
                    .map(String::trim).filter(s -> !s.isEmpty()).toList());
        } catch (IOException e) {
            return Set.of();
        }
    }

    /**
     * Append keys this installation has never been offered. "Never offered"
     * is {@code messages.known}, not "absent from the file": an owner who
     * trimmed the file to their overrides must not get the whole catalogue
     * back on every restart. An installation with no record yet gets every
     * absent key offered once -- re-offering once beats never offering.
     */
    private static void mergeNewKeys(Path path) throws IOException {
        Set<String> known = readKnown();
        Set<String> present = new LinkedHashSet<>();
        Object parsed = new org.yaml.snakeyaml.Yaml().load(Files.readString(path, StandardCharsets.UTF_8));
        if (parsed instanceof Map<?, ?> map) {
            map.keySet().forEach(k -> present.add(String.valueOf(k)));
        }
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (Map.Entry<String, String> e : DEFAULTS.entrySet()) {
            if (known.contains(e.getKey()) || present.contains(e.getKey())) continue;
            if (added == 0) sb.append("\n# --- new keys offered by this version (delete any you do not want to customise) ---\n");
            sb.append(yamlLine(e.getKey(), e.getValue()));
            added++;
        }
        if (added > 0) {
            Files.writeString(path, Files.readString(path, StandardCharsets.UTF_8) + sb, StandardCharsets.UTF_8);
            Chronicler.LOGGER.info("Chronicler: offered {} new message key(s) in messages.yml", added);
        }
    }

    private static String yamlLine(String key, String value) {
        return key + ": \"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n";
    }

    private static void writeDefaults(Path path) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("# Chronicler messages & vocabulary -- every player-facing string.\n");
        sb.append("# Edit freely: translation, tone, or wholesale terminology.\n");
        sb.append("# A zombie server might set:  term.quest: \"Mission\"   term.chapter: \"Act\"\n");
        sb.append("# {curly} placeholders are filled at runtime; {term.x} pulls a term from\n");
        sb.append("# this file; '&' colour codes work everywhere. Deleted keys fall back to\n");
        sb.append("# these defaults, so trimming the file to just your changes is fine --\n");
        sb.append("# keys a later version adds are appended at the bottom once, then left alone.\n");
        sb.append("# Applied on restart and on /reload (text is not a frozen registry).\n\n");
        String section = "";
        for (Map.Entry<String, String> entry : DEFAULTS.entrySet()) {
            String prefix = entry.getKey().contains(".")
                    ? entry.getKey().substring(0, entry.getKey().indexOf('.')) : "";
            if (!prefix.equals(section)) {
                section = prefix;
                sb.append("\n# --- ").append(section.isEmpty() ? "general" : section).append(" ---\n");
            }
            sb.append(yamlLine(entry.getKey(), entry.getValue()));
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
        Chronicler.LOGGER.info("Chronicler: wrote default messages.yml with {} keys", DEFAULTS.size());
    }

    private Lang() {}
}
