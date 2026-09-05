package com.sablednah.chronicler.yaml;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import com.google.gson.JsonElement;
import com.sablednah.chronicler.Chronicler;

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
 * The YAML front door: serves {@code config/chronicler/**\/*.yml} as a
 * datapack. Admins edit YAML; the registry loader sees JSON. Same mechanism
 * as LegendQuest's, same two rules:
 *
 * <p><b>{@code /reload} re-opens this pack but does not apply the edit.</b>
 * Chapters and quests are datapack registries, frozen at world load; the
 * conversion re-runs and logs a count that reads like success, and the server
 * keeps serving the old definition. Content lands on RESTART.</p>
 *
 * <p>A file that is not valid YAML is skipped with a loud log line rather
 * than reaching the registry loader, whose own failure stops the world
 * loading. A file that parses but breaks the schema still hard-fails, by
 * design -- a quest with a misspelled objective type is a world-killer, and
 * the log names file and field.</p>
 */
public final class YamlConfigPack implements PackResources {

    public static final String PACK_ID = "chronicler_yaml";

    /** config subfolder -> registry path segment. */
    private static final Map<String, String> FOLDERS = Map.of(
            "chapters", "chapter",
            "quests", "quest");

    private static final PackLocationInfo LOCATION = new PackLocationInfo(
            PACK_ID,
            Component.literal("Chronicler YAML configs"),
            PackSource.BUILT_IN,
            Optional.empty());

    private final Map<Identifier, byte[]> resources = new HashMap<>();

    private YamlConfigPack() {
        Path root = FMLPaths.CONFIGDIR.get().resolve(Chronicler.MODID);
        ensureScaffold(root);
        for (var entry : FOLDERS.entrySet()) {
            Path dir = root.resolve(entry.getKey());
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> files = Files.list(dir)) {
                files.filter(f -> {
                    String n = f.getFileName().toString().toLowerCase(Locale.ROOT);
                    return n.endsWith(".yml") || n.endsWith(".yaml");
                }).forEach(file -> convert(entry.getValue(), file));
            } catch (IOException e) {
                Chronicler.LOGGER.error("Could not scan {}", dir, e);
            }
        }
        if (!resources.isEmpty()) {
            Chronicler.LOGGER.info("Chronicler YAML front door: serving {} definition(s) from {}",
                    resources.size(), root);
        }
    }

    private void convert(String registrySegment, Path file) {
        String base = file.getFileName().toString();
        base = base.substring(0, base.lastIndexOf('.')).toLowerCase(Locale.ROOT).replace(' ', '_');
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement json = YamlToJson.parse(reader);
            Identifier id = Identifier.fromNamespaceAndPath(Chronicler.MODID,
                    Chronicler.MODID + "/" + registrySegment + "/" + base + ".json");
            resources.put(id, json.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Chronicler.LOGGER.error("[YAML] {} is not valid YAML and was SKIPPED: {}", file, e.getMessage());
        }
    }

    private static void ensureScaffold(Path root) {
        try {
            for (String folder : FOLDERS.keySet()) {
                Files.createDirectories(root.resolve(folder));
            }
            Path readme = root.resolve("README.txt");
            if (!Files.exists(readme)) {
                Files.writeString(readme, """
                        Chronicler -- YAML content folder
                        =================================
                        Drop chapter and quest definitions here as YAML:
                          chapters/<name>.yml  -> registry id chronicler:<name>
                          quests/<name>.yml    -> registry id chronicler:<name>

                        The same schema also works as JSON in any datapack at
                        data/<pack>/chronicler/{chapter,quest}/<name>.json.
                        A YAML file here with the same name as a built-in entry
                        overrides it.

                        RESTART THE SERVER after editing. Chapters and quests are
                        frozen registries -- like vanilla's own enchantments, they
                        load once when the world starts, so /reload will NOT pick
                        up a change made here.
                        (messages.yml is different: text is not a registry, and
                        /reload does apply it.)
                        """);
            }
        } catch (IOException e) {
            Chronicler.LOGGER.error("Could not create config scaffold under {}", root, e);
        }
    }

    // --- PackResources ---

    @Override
    public IoSupplier<InputStream> getRootResource(String... path) {
        return null;
    }

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

    @Override
    public Set<String> getNamespaces(PackType type) {
        return type == PackType.SERVER_DATA ? Set.of(Chronicler.MODID) : Set.of();
    }

    @Override
    public <T> T getMetadataSection(MetadataSectionType<T> section) throws IOException {
        return null;
    }

    @Override
    public PackLocationInfo location() {
        return LOCATION;
    }

    @Override
    public void close() {}

    // --- Pack plumbing ---

    /** The Pack served to the repository; opens a fresh conversion each (re)load. */
    public static Pack makePack() {
        Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
            @Override
            public PackResources openPrimary(PackLocationInfo location) {
                return new YamlConfigPack();
            }

            @Override
            public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                return new YamlConfigPack();
            }
        };
        Pack.Metadata metadata = new Pack.Metadata(
                Component.literal("Chapters and quests from config/chronicler/*.yml"),
                PackCompatibility.COMPATIBLE,
                FeatureFlagSet.of(),
                List.of(),
                true);
        // required=true keeps it enabled; TOP so YAML overrides built-in defaults.
        PackSelectionConfig selection = new PackSelectionConfig(true, Pack.Position.TOP, false);
        return new Pack(LOCATION, supplier, metadata, selection);
    }
}
