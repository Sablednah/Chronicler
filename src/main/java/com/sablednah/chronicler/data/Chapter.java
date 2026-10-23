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
        QuestScope scope,
        boolean replayable,
        Optional<String> recap,
        List<Identifier> recapCovers,
        Optional<String> background) {

    /** Before {@code recap}: kept, since a record's canonical constructor is public API. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable) {
        this(name, description, order, requires, icon, main, scope, replayable, Optional.empty(), List.of(), Optional.empty());
    }

    /** Before {@code background}. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable, Optional<String> recap, List<Identifier> recapCovers) {
        this(name, description, order, requires, icon, main, scope, replayable, recap, recapCovers, Optional.empty());
    }

    /**
     * {@code recap}: the act as one paragraph, shown in the story so far in place of the covered quests'
     * own lines once every one of them is done ({@code {player}} is the reader). {@code recap_covers}
     * names them; unsaid, every quest that counts toward the chapter's progress. The covered quests'
     * {@code recap_endings} and {@code recap_flags} lines still follow, since they carry the choices.
     */
    public static final Codec<Chapter> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("name").forGetter(Chapter::name),
            Codec.STRING.optionalFieldOf("description").forGetter(Chapter::description),
            Codec.INT.optionalFieldOf("order", 0).forGetter(Chapter::order),
            ChroniclerIds.CODEC.listOf().optionalFieldOf("requires", List.of()).forGetter(Chapter::requires),
            Identifier.CODEC.optionalFieldOf("icon").forGetter(Chapter::icon),
            Codec.BOOL.optionalFieldOf("main", false).forGetter(Chapter::main),
            QuestScope.CODEC.optionalFieldOf("scope", QuestScope.SOLO).forGetter(Chapter::scope),
            Codec.BOOL.optionalFieldOf("replayable", false).forGetter(Chapter::replayable),
            Codec.STRING.optionalFieldOf("recap").forGetter(Chapter::recap),
            ChroniclerIds.CODEC.listOf().optionalFieldOf("recap_covers", List.of()).forGetter(Chapter::recapCovers),
            // The achievements tab's background, a client asset id ("threadwork:gui/advancements/zarp"); the first
            // chapter to name one wins, since the mod has one tab. Read again from the raw JSON by AchievementPack.
            Codec.STRING.optionalFieldOf("background").forGetter(Chapter::background))
            .apply(i, Chapter::new));
}
