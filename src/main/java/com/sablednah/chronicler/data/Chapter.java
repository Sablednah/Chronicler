package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.core.QuestScope;

import net.minecraft.resources.Identifier;

/**
 * A chapter: the unit of story. Quests belong to exactly one; chapters
 * order themselves by {@code order} and may gate on other chapters.
 *
 * @param main  part of the main questline (listed first; the tracker's default)
 * @param scope the default scope for quests in this chapter; a quest may override
 */
public record Chapter(
        String name,
        Optional<String> description,
        int order,
        List<Identifier> requires,
        Optional<Identifier> icon,
        boolean main,
        QuestScope scope) {

    public static final Codec<Chapter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Chapter::name),
            Codec.STRING.optionalFieldOf("description").forGetter(Chapter::description),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Chapter::order),
            ChroniclerIds.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(Chapter::requires),
            Identifier.CODEC.optionalFieldOf("icon").forGetter(Chapter::icon),
            Codec.BOOL.optionalFieldOf("main", false).forGetter(Chapter::main),
            QuestScope.CODEC.optionalFieldOf("scope", QuestScope.SOLO).forGetter(Chapter::scope))
            .apply(i, Chapter::new));
}
