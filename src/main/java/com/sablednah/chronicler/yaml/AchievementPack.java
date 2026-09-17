package com.sablednah.chronicler.yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.ChroniclerIds;
import com.sablednah.chronicler.neoforge.Achievements;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackSelectionConfig;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Generates one vanilla advancement per chapter and per ending, served the same way the YAML
 * front door serves quests: a synthetic {@link PackResources}, opened when the pack list is
 * built, so vanilla's own advancement loader picks the result up like any other datapack --
 * no reload-listener surgery, no touching {@code ServerAdvancementManager} at all.
 *
 * <p><b>Why not read the frozen {@code Chapter}/{@code Quest} registries instead?</b> Those are
 * not populated until the SAME datapack pass this pack is a page of; asking for them here would
 * be asking a book to read itself before it is bound. So this scans the two things Chronicler
 * itself controls ahead of that pass: {@code config/chronicler/*.yml} (parsed the same way
 * {@link YamlConfigPack} does) and the two built-in packs bundled in the jar, listed for real off
 * the classpath -- never a hardcoded file list, which would silently go stale the day a quest is
 * added and nobody remembers to update it. <b>The gap this leaves</b>: a chapter or ending that
 * exists only inside a third-party datapack is not seen here and gets no advancement of its own --
 * that pack's author can ship a real advancement JSON alongside their content (this generates
 * nothing that stops them), or a quest anywhere can grant one explicitly with the {@code
 * advancement} reward type.</p>
 */
public final class AchievementPack implements PackResources {

    public static final String PACK_ID = "chronicler_achievements";
    private static final PackLocationInfo LOCATION = new PackLocationInfo(
            PACK_ID, Component.literal("Chronicler achievements"), PackSource.BUILT_IN, java.util.Optional.empty());

    private final Map<Identifier, byte[]> resources = new HashMap<>();

    private record ChapterInfo(Identifier id, String name, String description, String icon, List<Identifier> requires, int order) {}

    private AchievementPack(boolean zarp, boolean prologue) {
        Map<Identifier, ChapterInfo> chapters = new LinkedHashMap<>();
        Map<Identifier, Set<String>> endings = new LinkedHashMap<>();

        scanConfig(chapters, endings);
        if (prologue) scanBuiltIn("/datapacks/prologue", chapters, endings);
        if (zarp) scanBuiltIn("/datapacks/zarp", chapters, endings);

        emitRoot();
        // Chained by order WITHIN each pack's own namespace only -- ZARP and the prologue are two
        // unrelated questlines (mutually exclusive in real play; the self-test forces both on),
        // and a chapter never gets chained to some other pack's chapter as a cosmetic accident.
        Map<String, List<ChapterInfo>> byNamespace = new LinkedHashMap<>();
        for (ChapterInfo c : chapters.values()) byNamespace.computeIfAbsent(c.id().getNamespace(), k -> new ArrayList<>()).add(c);
        for (List<ChapterInfo> group : byNamespace.values()) {
            group.sort(java.util.Comparator.comparingInt(ChapterInfo::order).thenComparing(c -> c.id().toString()));
            for (int i = 0; i < group.size(); i++) emitChapter(group.get(i), chapters, i > 0 ? group.get(i - 1).id() : null);
        }
        endings.forEach((chapter, names) -> {
            ChapterInfo c = chapters.get(chapter);
            for (String ending : names) emitEnding(chapter, c, ending);
        });

        if (!resources.isEmpty()) {
            Chronicler.LOGGER.info("Chronicler achievements: {} chapter(s), {} ending(s) generated",
                    chapters.size(), endings.values().stream().mapToInt(Set::size).sum());
        }
    }

    // --- discovery: config YAML ---

    private void scanConfig(Map<Identifier, ChapterInfo> chapters, Map<Identifier, Set<String>> endings) {
        Path root = FMLPaths.CONFIGDIR.get().resolve(Chronicler.MODID);
        readDir(root.resolve("chapters"), ".yml", ".yaml", (name, json) -> addChapter(chapters, ChroniclerIds.of(name), json));
        readDir(root.resolve("quests"), ".yml", ".yaml", (name, json) -> addQuestEndings(endings, json));
    }

    private interface EntryHandler { void accept(String baseName, JsonElement json); }

    private void readDir(Path dir, String ext1, String ext2, EntryHandler handler) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> {
                String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                return n.endsWith(ext1) || n.endsWith(ext2);
            }).forEach(file -> {
                try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String base = file.getFileName().toString();
                    base = base.substring(0, base.lastIndexOf('.')).toLowerCase(Locale.ROOT).replace(' ', '_');
                    handler.accept(base, YamlToJson.parse(reader));
                } catch (Exception e) {
                    // YamlConfigPack already logs and skips a bad file loudly; nothing more to say here.
                }
            });
        } catch (IOException e) {
            Chronicler.LOGGER.error("Chronicler achievements: could not scan {}", dir, e);
        }
    }

    // --- discovery: a built-in pack bundled in the jar, listed for real ---

    private void scanBuiltIn(String jarRootPath, Map<Identifier, ChapterInfo> chapters, Map<Identifier, Set<String>> endings) {
        // A bare directory path does not reliably resolve through FML's classloader (it indexes
        // files, not directories, and Class#getResource on the directory itself just comes back
        // null there even though the same path opens fine as a real datapack). pack.mcmeta is a
        // real file every one of these packs ships, so resolve THAT and take its parent instead.
        URL url = AchievementPack.class.getResource(jarRootPath + "/pack.mcmeta");
        if (url == null) {
            Chronicler.LOGGER.warn("Chronicler achievements: could not find {}/pack.mcmeta on the classpath -- no achievements generated for it", jarRootPath);
            return;
        }
        try {
            Path root;
            FileSystem opened = null;
            if ("jar".equals(url.getProtocol())) {
                String[] split = url.toURI().toString().split("!", 2);
                opened = fileSystemFor(URI.create(split[0]));
                root = opened.getPath(split.length > 1 ? split[1] : "/").getParent();
            } else {
                root = Path.of(url.toURI()).getParent();
            }
            try {
                walkPack(root, chapters, endings);
            } finally {
                if (opened != null) opened.close();
            }
        } catch (Exception e) {
            Chronicler.LOGGER.error("Chronicler achievements: could not list the built-in pack at {}", jarRootPath, e);
        }
    }

    private static FileSystem fileSystemFor(URI jarUri) throws IOException {
        try {
            return FileSystems.getFileSystem(jarUri);
        } catch (java.nio.file.FileSystemNotFoundException e) {
            return FileSystems.newFileSystem(jarUri, Map.of());
        }
    }

    /** {@code <root>/data/<namespace>/chronicler/{chapter,quest}/<name>.json}, for every namespace the pack uses. */
    private void walkPack(Path root, Map<Identifier, ChapterInfo> chapters, Map<Identifier, Set<String>> endings) throws IOException {
        Path data = root.resolve("data");
        if (!Files.isDirectory(data)) return;
        try (Stream<Path> namespaces = Files.list(data)) {
            for (Path ns : namespaces.toList()) {
                if (!Files.isDirectory(ns)) continue;
                String namespace = ns.getFileName().toString();
                readJsonDir(ns.resolve("chronicler/chapter"), (name, json) -> addChapter(chapters, Identifier.fromNamespaceAndPath(namespace, name), json));
                readJsonDir(ns.resolve("chronicler/quest"), (name, json) -> addQuestEndings(endings, json));
            }
        }
    }

    private void readJsonDir(Path dir, EntryHandler handler) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json")).forEach(file -> {
                try (InputStream in = Files.newInputStream(file)) {
                    String base = file.getFileName().toString();
                    base = base.substring(0, base.length() - ".json".length());
                    handler.accept(base, com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8)));
                } catch (Exception e) {
                    Chronicler.LOGGER.warn("Chronicler achievements: could not read {}", file, e);
                }
            });
        } catch (IOException e) {
            Chronicler.LOGGER.error("Chronicler achievements: could not list {}", dir, e);
        }
    }

    // --- pulling chapter and ending info out of a parsed file ---

    private void addChapter(Map<Identifier, ChapterInfo> chapters, Identifier id, JsonElement json) {
        if (!(json instanceof JsonObject o)) return;
        List<Identifier> requires = new ArrayList<>();
        if (o.get("requires") instanceof JsonArray arr) {
            for (JsonElement e : arr) ChroniclerIds.parse(e.getAsString()).result().ifPresent(requires::add);
        }
        int order = o.has("order") && o.get("order").isJsonPrimitive() ? o.get("order").getAsInt() : 0;
        chapters.put(id, new ChapterInfo(id,
                str(o, "name", id.getPath()), str(o, "description", ""), str(o, "icon", ""), requires, order));
    }

    /** Every distinct {@code "ending": "..."} anywhere in a quest file, filed under the chapter it declares. */
    private void addQuestEndings(Map<Identifier, Set<String>> endings, JsonElement json) {
        if (!(json instanceof JsonObject o) || !o.has("chapter")) return;
        var chapter = ChroniclerIds.parse(o.get("chapter").getAsString()).result();
        if (chapter.isEmpty()) return;
        Set<String> found = new LinkedHashSet<>();
        collectEndings(json, found);
        if (!found.isEmpty()) endings.computeIfAbsent(chapter.get(), k -> new LinkedHashSet<>()).addAll(found);
    }

    private static void collectEndings(JsonElement json, Set<String> out) {
        Deque<JsonElement> stack = new ArrayDeque<>();
        stack.push(json);
        while (!stack.isEmpty()) {
            JsonElement e = stack.pop();
            if (e instanceof JsonObject o) {
                for (var entry : o.entrySet()) {
                    if (entry.getKey().equals("ending") && entry.getValue().isJsonPrimitive()) {
                        out.add(entry.getValue().getAsString());
                    } else {
                        stack.push(entry.getValue());
                    }
                }
            } else if (e instanceof JsonArray a) {
                for (JsonElement el : a) stack.push(el);
            }
        }
    }

    private static String str(JsonObject o, String key, String fallback) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : fallback;
    }

    // --- emitting advancement JSON ---

    /** {@code chronicler:root} -- the tab. Granted the moment a player's journal begins. */
    private void emitRoot() {
        JsonObject display = display("&f" + Chronicler.MODID.substring(0, 1).toUpperCase(Locale.ROOT) + Chronicler.MODID.substring(1),
                "Somewhere, a story is starting.", "minecraft:written_book", "task", false);
        display.addProperty("background", "minecraft:textures/gui/advancements/backgrounds/stone.png");
        put(Achievements.ROOT, advancement(null, display));
    }

    /**
     * A chapter's advancement hangs off whichever earlier chapter its own {@code requires} names
     * (real authored intent, when there is one); ZARP and the prologue never set it, so failing
     * that, the previous chapter by {@code order} makes a linear spine through the shipped story
     * rather than a flat fan of chapters all hanging off root -- still just a default, and a
     * {@code requires} line in a chapter file overrides it as soon as one is added.
     */
    private void emitChapter(ChapterInfo c, Map<Identifier, ChapterInfo> chapters, Identifier previousByOrder) {
        Identifier parent = null;
        for (Identifier req : c.requires()) {
            if (chapters.containsKey(req)) { parent = Achievements.chapterId(req); break; }
        }
        if (parent == null) parent = previousByOrder != null ? Achievements.chapterId(previousByOrder) : Achievements.ROOT;
        String icon = c.icon().isEmpty() ? "minecraft:written_book" : c.icon();
        JsonObject display = display(c.name(), c.description().isEmpty() ? c.name() : c.description(), icon, "goal", false);
        put(Achievements.chapterId(c.id()), advancement(parent, display));
    }

    private void emitEnding(Identifier chapterId, ChapterInfo c, String ending) {
        String icon = c == null || c.icon().isEmpty() ? "minecraft:written_book" : c.icon();
        String chapterName = c == null ? chapterId.toString() : c.name();
        JsonObject display = display("Ending: " + pretty(ending), "Reached an ending of " + chapterName + ".", icon, "challenge", true);
        put(Achievements.endingId(chapterId, ending), advancement(Achievements.chapterId(chapterId), display));
    }

    private static String pretty(String s) {
        String[] words = s.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        StringBuilder out = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (!out.isEmpty()) out.append(' ');
            out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return out.toString();
    }

    private static JsonObject display(String title, String description, String icon, String frame, boolean hidden) {
        JsonObject display = new JsonObject();
        JsonObject iconObj = new JsonObject();
        iconObj.addProperty("id", icon);
        display.add("icon", iconObj);
        display.addProperty("title", title.replaceAll("&.", ""));
        display.addProperty("description", description.replaceAll("&.", ""));
        display.addProperty("frame", frame);
        display.addProperty("show_toast", true);
        display.addProperty("announce_to_chat", true);
        display.addProperty("hidden", hidden);
        return display;
    }

    /** Manually granted only: {@code minecraft:impossible} is the vanilla-documented pattern for exactly that. */
    private static JsonObject advancement(Identifier parent, JsonObject display) {
        JsonObject root = new JsonObject();
        if (parent != null) root.addProperty("parent", parent.toString());
        root.add("display", display);
        JsonObject criteria = new JsonObject();
        JsonObject impossible = new JsonObject();
        impossible.addProperty("trigger", "minecraft:impossible");
        criteria.add("impossible", impossible);
        root.add("criteria", criteria);
        return root;
    }

    /**
     * Every id from {@link Achievements} already carries {@code chronicler} as its OWN namespace
     * (a chapter's real namespace, zarp included, is folded into the path string instead -- see
     * {@link Achievements#chapterId}) because this pack, like {@code YamlConfigPack}, can only
     * ever serve resources under the one namespace {@link #getNamespaces} reports. So the file
     * this writes to is {@code data/chronicler/advancement/<path>.json} -- vanilla's loader turns
     * that back into {@code chronicler:<path>}, which must be exactly {@code advancementId} again,
     * not the namespace repeated a second time into the path.
     */
    private void put(Identifier advancementId, JsonObject json) {
        Identifier resource = Identifier.fromNamespaceAndPath(Chronicler.MODID, "advancement/" + advancementId.getPath() + ".json");
        resources.put(resource, json.toString().getBytes(StandardCharsets.UTF_8));
    }

    // --- PackResources ---

    @Override public IoSupplier<InputStream> getRootResource(String... path) { return null; }

    @Override
    public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
        if (type != PackType.SERVER_DATA) return null;
        byte[] bytes = resources.get(id);
        return bytes == null ? null : () -> new ByteArrayInputStream(bytes);
    }

    @Override
    public void listResources(PackType type, String namespace, String pathPrefix, ResourceOutput output) {
        if (type != PackType.SERVER_DATA || !Chronicler.MODID.equals(namespace)) return;
        for (var entry : resources.entrySet()) {
            if (entry.getKey().getPath().startsWith(pathPrefix)) {
                output.accept(entry.getKey(), () -> new ByteArrayInputStream(entry.getValue()));
            }
        }
    }

    @Override public Set<String> getNamespaces(PackType type) { return type == PackType.SERVER_DATA ? Set.of(Chronicler.MODID) : Set.of(); }

    @Override public <T> T getMetadataSection(MetadataSectionType<T> section) throws IOException { return null; }

    @Override public PackLocationInfo location() { return LOCATION; }

    @Override public void close() {}

    // --- Pack plumbing ---

    public static Pack makePack(boolean zarp, boolean prologue) {
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override public PackResources openPrimary(PackLocationInfo location) { return new AchievementPack(zarp, prologue); }
            @Override public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) { return new AchievementPack(zarp, prologue); }
        };
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("Generated achievements: one per chapter, one per ending"),
                PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, false);
        return new Pack(LOCATION, supplier, metadata, selection);
    }
}
