package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.RewardTypes;

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
        Optional<String> background,
        List<RewardSpec> onReplay,
        List<Variant> variants) {

    /** Before {@code variants}. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable, Optional<String> recap, List<Identifier> recapCovers, Optional<String> background,
            List<RewardSpec> onReplay) {
        this(name, description, order, requires, icon, main, scope, replayable, recap, recapCovers, background, onReplay, List.of());
    }

    /** The chapter in a variant's words (name, description, recap); the copy has no variants of its own. */
    public Chapter worded(Variant v) {
        return new Chapter(v.name().orElse(name), v.description().or(() -> description), order, requires, icon, main, scope, replayable,
                v.recap().or(() -> recap), recapCovers, background, onReplay, List.of());
    }

    /** Before {@code on_replay}. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable, Optional<String> recap, List<Identifier> recapCovers, Optional<String> background) {
        this(name, description, order, requires, icon, main, scope, replayable, recap, recapCovers, background, List.of(), List.of());
    }

    /** Before {@code recap}: kept, since a record's canonical constructor is public API. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable) {
        this(name, description, order, requires, icon, main, scope, replayable, Optional.empty(), List.of(), Optional.empty(), List.of(), List.of());
    }

    /** Before {@code background}. */
    public Chapter(String name, Optional<String> description, int order, List<Identifier> requires, Optional<Identifier> icon,
            boolean main, QuestScope scope, boolean replayable, Optional<String> recap, List<Identifier> recapCovers) {
        this(name, description, order, requires, icon, main, scope, replayable, recap, recapCovers, Optional.empty(), List.of(), List.of());
    }

    /**
     * {@code recap}: the act as one paragraph, shown in the story so far in place of the covered quests'
     * own lines once every one of them is done ({@code {player}} is the reader). {@code recap_covers}
     * names them; unsaid, every quest that counts toward the chapter's progress. The covered quests'
     * {@code recap_endings} and {@code recap_flags} lines still follow, since they carry the choices.
     */
    public static final Codec<Chapter> CODEC = RecordCodecBuilder.<Chapter>create(i -> i.group(
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
            Codec.STRING.optionalFieldOf("background").forGetter(Chapter::background),
            // Run for the replaying player once /quest replay has cleared the chapter: the same effects a
            // quest's rewards and on_enter take (give back the quest items its stages consumed, npc_restore
            // the person a choice removed, clear the world flags its endings set). ZARP's finale asked.
            RewardTypes.CODEC.listOf().optionalFieldOf("on_replay", List.of()).forGetter(Chapter::onReplay),
            // Other words once the world has moved on: name, description and recap only (see Variant).
            Variant.CODEC.listOf().optionalFieldOf("variants", List.of()).forGetter(Chapter::variants))
            .apply(i, Chapter::new)).validate(c -> {
                for (int n = 0; n < c.variants().size(); n++) {
                    var bad = c.variants().get(n).notForAChapter();
                    if (bad.isPresent()) {
                        String why = "variants[" + (n + 1) + "]." + bad.get() + ": a chapter variant rewords name, description and recap; "
                                + bad.get() + " belongs in a quest's variants";
                        return com.mojang.serialization.DataResult.error(() -> why);
                    }
                }
                return com.mojang.serialization.DataResult.success(c);
            });
}
