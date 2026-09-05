package com.sablednah.chronicler.yaml;

import java.io.Reader;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * SnakeYAML → Gson. YAML and JSON are two skins over the same schema: this is
 * the whole of the "YAML support" — everything downstream is ordinary codecs.
 */
public final class YamlToJson {

    /** Parse one YAML document into a Gson tree. Throws on malformed YAML. */
    public static JsonElement parse(Reader reader) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object root = yaml.load(reader);
        return convert(root);
    }

    private static JsonElement convert(Object node) {
        return switch (node) {
            case null -> JsonNull.INSTANCE;
            case Map<?, ?> map -> {
                JsonObject obj = new JsonObject();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    obj.add(String.valueOf(e.getKey()), convert(e.getValue()));
                }
                yield obj;
            }
            case List<?> list -> {
                JsonArray arr = new JsonArray();
                for (Object item : list) arr.add(convert(item));
                yield arr;
            }
            case Boolean b -> new JsonPrimitive(b);
            case Number n -> new JsonPrimitive(n);
            default -> new JsonPrimitive(String.valueOf(node));
        };
    }

    private YamlToJson() {}
}
