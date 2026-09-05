package com.sablednah.chronicler.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.sablednah.chronicler.Chronicler;

import net.minecraft.resources.Identifier;

/**
 * An {@link Identifier} codec that treats a bare name as ours.
 *
 * <p>{@code "chapter": "prologue"} should mean {@code chronicler:prologue},
 * not {@code minecraft:prologue} -- which is what vanilla's codec would
 * silently produce, and which would then fail to resolve with an error that
 * names the wrong namespace. Anything with a colon is taken as written.</p>
 */
public final class ChroniclerIds {

    public static final Codec<Identifier> CODEC = Codec.STRING.comapFlatMap(
            ChroniclerIds::parse, Identifier::toString).stable();

    public static DataResult<Identifier> parse(String text) {
        if (text.indexOf(':') < 0) {
            Identifier id = Identifier.tryParse(Chronicler.MODID + ":" + text);
            return id == null
                    ? DataResult.error(() -> "Not a valid id: '" + text + "'")
                    : DataResult.success(id);
        }
        return Identifier.read(text);
    }

    /** Our own namespace, for content we ship. */
    public static Identifier of(String path) {
        return Identifier.fromNamespaceAndPath(Chronicler.MODID, path);
    }

    private ChroniclerIds() {}
}
