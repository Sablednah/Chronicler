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
        def("term.karma", "karma");
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
        def("status.tracked", "&b(tracked)");
        def("status.cooldown", "&8again in {time}");
        def("msg.refuse.cooldown", "{prefix}&7Done recently. Again in &f{time}&7.");
        def("status.party", "&d(party)");
    }

    // --- msg.* : the engine talking ---
    static {
        def("msg.accept", "{prefix}&aAccepted: &f{name}");
        def("msg.accept.party", "{prefix}&a{who} accepted for the party: &f{name}");
        def("msg.accept.objective", "  &7- &f{line}");
        def("msg.refuse.unknown", "{prefix}&cNo such {term.quest}.");
        def("msg.refuse.active", "{prefix}&7You are already on that one. /quest log shows how far.");
        def("msg.refuse.complete", "{prefix}&7Done already, and it does not repeat.");
        def("msg.refuse.locked", "{prefix}&7Not yet. Finish what comes before it -- /quest info tells you what.");
        def("msg.abandon", "{prefix}&7Abandoned: &f{name}&7. It will be there if you change your mind.");
        def("msg.not_active", "{prefix}&7You are not on that {term.quest}.");
        def("msg.track", "{prefix}&7Now tracking &f{name}&7.");
        def("msg.progress", "&e{quest}&7: {objective} &f({done}/{target})");
        def("msg.objective_done", "{prefix}&a\u2714 &f{line}");
        def("msg.complete", "{prefix}&6&l{term.quest} complete: &r&f{name}");
        def("msg.complete.title", "&6&l{name}");
        def("msg.complete.subtitle", "&e{term.quest} complete");
        def("msg.reward.given", "  &a+ &f{line}");
        def("msg.reward.unknown_item", "  &c(a reward names an item this server does not have: {item} -- tell an admin)");
        def("msg.reward.reputation_none", "  &7({line}, but nothing on this server keeps reputation)");
        def("msg.reward.reputation_band", " &7-- {standing} now think of you as &f{band}");
        def("msg.reward.money_none", "  &7(+{amount} coin, but this server has no economy to pay it into)");
        def("msg.new_available", "{prefix}&eNew {term.quest} available: &f{name}");
        def("msg.party.solo_notice", "{prefix}&7This is a party {term.quest}, but nothing on this server tracks parties -- so it is yours alone.");
        def("msg.reset", "{prefix}&7Wiped {player}'s {term.journal}.");
        def("button.accept", "&a[Accept]");
        def("button.accept.tip", "Take this {term.quest}");
        def("button.info", "&7[Info]");
        def("button.info.tip", "What it asks and what it pays");
        def("button.track", "&b[Track]");
        def("button.track.tip", "Follow this one on the action bar");
        def("button.abandon", "&c[Abandon]");
        def("button.abandon.tip", "Drop it. Progress is lost.");
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
        def("cmd.log.quest", "  &f{name} &7{progress}");
        def("cmd.log.objective", "    &7- {line} &f({done}/{target})");
        def("cmd.info.progress", "  &7- &f{line} &7({done}/{target})");
        def("cmd.info.scope_party", "&7Scope: &dparty &7-- progress is shared, targets scale with party size.");
        def("cmd.log.empty", "{prefix}&7Your {term.journal} is empty. /quest list shows what is on offer.");
        def("cmd.unknown_quest", "No {term.quest} called '{id}'. /quest list names them.");
        def("cmd.ambiguous", "More than one {term.quest} is called that: {ids}. Use the full id.");
        def("cmd.reload_notice", "{prefix}&7messages.yml applied. {term.chapters} and {term.quests} are frozen registries: content changes apply on server RESTART.");
        def("cmd.status", "{prefix}&f{chapters} {term.chapters}, {quests} {term.quests}, {objectives} objective types, {rewards} reward types.");
        def("cmd.status.config", "&7Config: &f{path}");
        def("cmd.status.siblings", "&7Siblings: &f{list}");
        def("cmd.status.party", "&7Party membership: &f{provider}");
        def("cmd.status.character", "&7Character sheets: &f{provider}");
    }

    // --- journal.* : the written book. Parchment, so dark colours; every page an unstyled root ---
    static {
        def("journal.title", "{term.journal}");
        def("journal.author", "Chronicler");
        def("journal.heading", "&0&l{term.journal}");
        def("journal.counts", "&8{active} in progress, {completed} done");
        def("journal.none_active", "&8Nothing under way. Turn to what is on offer.");
        def("journal.contents.quest", "&1\u00bb {name}");
        def("journal.contents.tracked", "&1\u00bb {name} &3\u2726");
        def("journal.contents.available", "&2On offer ({count}) \u00bb");
        def("journal.contents.done", "&8Done ({count}) \u00bb");
        def("journal.tip.turn", "Turn to that page");
        def("journal.tip.contents", "Back to the contents");
        def("journal.quest.name", "&0&l{name}");
        def("journal.quest.chapter", "&8{chapter}");
        def("journal.quest.party", "&5Shared with your party.");
        def("journal.quest.description", "&0{description}");
        def("journal.quest.gone", "&8This {term.quest} ({id}) no longer exists on this server.");
        def("journal.objective.done", "&2\u2714 {line}");
        def("journal.objective.open", "&0\u2610 {line} &8({done}/{target})");
        def("journal.link.track", "&3[Track]");
        def("journal.link.abandon", "&4[Abandon]");
        def("journal.link.accept", "&2[Accept]");
        def("journal.link.info", "&8[Info]");
        def("journal.link.back", "&8\u00ab Contents");
        def("journal.available.heading", "&0&lOn offer");
        def("journal.available.none", "&8Nothing right now. Finish what you have, or go and look.");
        def("journal.available.quest", "&0{name}");
        def("journal.done.heading", "&0&lDone");
        def("journal.done.none", "&8Nothing yet. It is a long book.");
        def("journal.done.quest", "&2\u2714 &0{name}");
        def("journal.done.quest_times", "&2\u2714 &0{name} &8x{times}");
        def("msg.journal.given", "{prefix}&7Here is your {term.journal}. Right-click it to read; &f/quest journal&7 opens it from anywhere.");
        def("msg.journal.new_player", "{prefix}&7A {term.journal} has been slipped into your pack. Right-click it, or &f/quest journal&7.");
    }

    // --- place.* / giver.* / msg.offer.* : where things are ---
    static {
        def("place.lot", "a {lot}");
        def("place.structure", "a {structure}");
        def("place.biome", "the {biome}");
        def("place.dimension", "the {dimension}");
        def("place.join", " in ");
        def("place.anywhere", "anywhere");
        def("giver.position", "the spot at {x}, {y}, {z}");
        def("giver.position_label", "{label}");
        def("giver.place", "{place}");
        def("giver.place_label", "{label}");
        def("msg.offer", "{prefix}&e{name} &7-- from {where}.");
        def("msg.offer.bar", "&e{term.quest} on offer: &f{name}");
        def("msg.giver.active", "{prefix}&7You are already on &f{name}&7. Tracking it.");
        def("msg.giver.done", "{prefix}&7Nothing more here; that one is done.");
        def("msg.giver.locked", "{prefix}&7Not yet. Something has to happen first -- /quest info tells you what.");
        def("msg.giver.gone", "{prefix}&7This used to offer '{id}', which no longer exists. Tell an admin.");
        def("msg.giver.set", "{prefix}&7That block now offers &f{name}&7.");
        def("msg.giver.offer.header", "{prefix}&e&l{name}");
        def("msg.giver.offer.description", "&7{description}");
        def("msg.giver.offer.objective", "  &7- &f{line}");
        def("msg.giver.offer.reward", "  &a+ &f{line}");
        def("msg.giver.offer.prompt", "&7Right-click again to accept, or:");
        def("msg.giver.set_npc", "{prefix}&7They now offer &f{name}&7.");
        def("msg.giver.protected", "&7That block gives a {term.quest}. It stays. (Admins: sneak to break it.)");
        def("msg.giver.npc_idle", "{prefix}&7They have nothing for you right now.");
        def("giver.npc", "{name}");
        def("msg.giver.removed", "{prefix}&7That block offers nothing now.");
        def("msg.giver.none_here", "{prefix}&7That block was not a giver.");
        def("msg.giver.look", "{prefix}&7Look at a block within reach first.");
        def("msg.giver.list.header", "{prefix}&f{count} op-placed giver(s):");
        def("msg.giver.list.entry", "  &7{key} &f-> {quest}");
        def("cmd.info.giver", "&7From: &f{where}");
        def("journal.available.where", "&8{where}");
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
        def("rew.money_paid", "{amount}");
        def("rew.karma_up", "+{amount} {term.karma}");
        def("rew.karma_down", "-{amount} {term.karma}");
        def("rew.class_xp", "{amount} class experience");
        def("rew.levels", "{count} level(s)");
        def("rew.skill_points", "{count} skill point(s)");
        def("rew.flag_set", "Something changes.");
        def("rew.flag_clear", "Something changes back.");
        def("rew.flag_world_set", "The world remembers: {flag}");
        def("rew.flag_world_clear", "The world forgets: {flag}");
        def("obj.flag", "Wait for: {flag}");
        def("obj.ritual", "Perform the rite at the {block}");
        def("giver.near_spawn", "near where you first woke");
        def("obj.ritual_item", "Use {item} on the {block}");
        def("obj.wait", "Wait {time}");
        def("rew.npc_say", "Someone has something to say.");
        def("rew.npc_remove", "Someone will not be there any more.");
        def("rew.ending", "An ending.");
        def("cond.race", "be {race}");
        def("cond.class", "be a {class}");
        def("cond.or", " or ");
        def("place.or", " or ");
        def("msg.ritual.missing", "&cThe rite wants &f{block}&c at {x}, {y}, {z} from here.");
        def("msg.ritual.need_item", "&cThe rite wants &f{item}&c in your hand.");
        def("msg.ritual.done", "{prefix}&d{what}&7: done. Something answers.");
        def("msg.ritual.title", "&d&lThe rite is done");
        def("msg.ritual.subtitle", "&7Something answers.");
        def("msg.ending.reached", "{prefix}&d&lEnding reached: &f{ending}&r &7-- {chapter}, {found} of {total} found.");
        def("msg.ending.again", "{prefix}&7Ending &f{ending}&7 again -- {chapter}, {found} of {total} found.");
        def("msg.ending.title", "&d&l{ending}");
        def("msg.ending.subtitle", "&7Ending {found} of {total}");
        def("msg.ending.more", "&7{left} more ending(s) to find. Play it again?");
        def("button.replay", "&d[Play again]");
        def("button.replay.tip", "Start this {term.chapter} over. Endings found stay found.");
        def("cmd.replay.unknown", "{prefix}&cNo such {term.chapter}.");
        def("cmd.replay.not_replayable", "{prefix}&c{name} cannot be replayed.");
        def("cmd.replay.untouched", "{prefix}&7You have not started {name} yet -- nothing to replay.");
        def("cmd.replay.done", "{prefix}&d{name}&7 begins again. Endings found: {found} of {total}.");
        def("cmd.replay.title", "&d&l{name}");
        def("cmd.replay.subtitle", "&7Once more, from the top.");
        def("cmd.list.progress", "&7Progress: &f{percent}%&7 ({done} of {total})");
        def("cmd.list.chapter_progress", " &7{done}/{total}");
        def("cmd.list.endings", " &d{found}/{total} endings");
        def("journal.progress", "&8Progress: {percent}%");
        def("cmd.item.given", "{prefix}&7Gave {count} x {item} to {player}.");
        def("cmd.item.unknown", "{prefix}&cNo quest item called {id}. /chronicler item list shows them.");
        def("cmd.item.list", "{prefix}&7Quest items: {items}");
        def("cmd.item.none", "{prefix}&7No quest items are loaded.");
        def("msg.reward.no_character", "  &7({line}, but nothing on this server keeps character sheets)");
        def("msg.reward.no_class", "  &7({line}, but you have no class yet -- pick one and it will count next time)");
        def("msg.refuse.conditions", "{prefix}&7Not yet:");
        def("msg.locked.story", "&f{name} is not open to you yet.");
        def("msg.locked.why", "&8({why})");
        def("cond.requires", "finish {quests}");
        def("cond.join", "; ");
        def("cond.list_join", ", ");
        def("msg.refuse.condition_line", "  &7- &f{line}");
        def("cond.no_character", "needs a character system this server does not have");
        def("cond.no_reputation", "needs a reputation system this server does not have");
        def("cond.karma_min", "{term.karma} of at least {value}");
        def("cond.karma_max", "{term.karma} of at most {value}");
        def("cond.level_min", "level {value} or above");
        def("cond.level_max", "level {value} or below");
        def("cond.flag_on", "the world must know: {flag}");
        def("cond.flag_off", "the world must not know: {flag}");
        def("cond.player_flag_on", "you must have: {flag}");
        def("cond.player_flag_off", "you must not have: {flag}");
        def("cond.reputation", "standing of at least {value} with {standing}");
        def("cmd.info.needs", "&7Needs: &f{line}");
        def("msg.flag.set", "{prefix}&7World flag &f{flag}&7 is now &f{value}&7.");
        def("msg.flag.list.header", "{prefix}&f{count} world flag(s) set:");
        def("msg.flag.list.entry", "  &7- &f{flag}");
        def("msg.flag.list.none", "{prefix}&7No world flags are set.");
        def("msg.choice.header", "{prefix}&eWhat do you do?");
        def("msg.choice.option", "  &f{n}. ");
        def("msg.choice.button", "&a[{label}]");
        def("msg.choice.tip", "Choose this");
        def("msg.choice.made", "{prefix}&7You chose: &f{label}");
        def("msg.choice.text", "{prefix}&e{text}");
        def("msg.choice.none", "{prefix}&7There is nothing to choose right now.");
        def("msg.choice.bad", "{prefix}&7That is not one of the options.");
        def("msg.deadline.set", "{prefix}&cYou have {time} for this.");
        def("msg.deadline.bar", "&e{quest}&7: {objective} &f({done}/{target}) &c{time}");
        def("msg.deadline.failed", "{prefix}&cToo late: &f{name}");
        def("msg.deadline.abandoned", "{prefix}&7It is gone from your {term.journal}. It may come round again.");
        def("journal.choice", "&2\u00bb {label}");
        def("journal.quest.decision", "&0Decide:");
        def("rew.title", "A moment.");
        def("rew.message", "A word.");
        def("rew.spawn", "Company.");
        def("msg.stage.enter", "{prefix}&e{text}");
        def("msg.stage.done", "{prefix}&7Stage {stage} of {stages} done.");
        def("cmd.info.stages", "&7{count} stages. The first:");
        def("cmd.info.stage_now", "&7Stage {stage} of {stages}:");
        def("journal.quest.stage", "&8Stage {stage} of {stages}");
        def("journal.quest.text", "&0{text}");
        def("rew.reputation_up", "+{amount} standing with {standing}");
        def("rew.reputation_down", "-{amount} standing with {standing}");
        def("obj.reputation", "Be held in at least {amount} regard by {standing}");
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
