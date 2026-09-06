package com.sablednah.chronicler;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Server-owner dials. Content (chapters/quests) is data, not config; this
 * covers only the global switches. Values are read live via {@code .get()}.
 *
 * <p>Sensible defaults, highly configurable: every number here is somebody
 * else's wrong number, so it is exposed -- but the default is still a
 * choice, not a shrug.</p>
 */
public final class ChroniclerConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue FANFARE;
    public static final ModConfigSpec.BooleanValue TRACKER_ACTION_BAR;
    public static final ModConfigSpec.IntValue TRACKER_INTERVAL_TICKS;
    public static final ModConfigSpec.BooleanValue SHOW_HIDDEN_TO_OPS;
    public static final ModConfigSpec.ConfigValue<java.util.List<? extends String>> PARTY_GROUP_KINDS;
    public static final ModConfigSpec.BooleanValue JOURNAL_GIVE_NEW;
    public static final ModConfigSpec.IntValue GIVER_COOLDOWN_SECONDS;
    public static final ModConfigSpec.DoubleValue GIVER_RADIUS;

    static {
        BUILDER.comment("Announcements").push("announce");
        FANFARE = BUILDER
                .comment("Mark a quest completion with a title card and the toast chime, not",
                        "just a chat line. Off leaves the chat line alone.")
                .define("fanfare", true);
        BUILDER.pop();

        BUILDER.comment("The vanilla-client quest tracker").push("tracker");
        TRACKER_ACTION_BAR = BUILDER
                .comment("Show the tracked objective's progress on the action bar when it",
                        "changes. Vanilla clients have no HUD of ours, so this is theirs.")
                .define("actionBar", true);
        TRACKER_INTERVAL_TICKS = BUILDER
                .comment("How often (ticks) the server re-evaluates location objectives.",
                        "20 = once a second. Lower is snappier and costs more.")
                .defineInRange("intervalTicks", 20, 1, 1200);
        BUILDER.pop();

        BUILDER.comment("Visibility").push("visibility");
        SHOW_HIDDEN_TO_OPS = BUILDER
                .comment("Quests marked hidden are listed to ops (level 2+) with a [hidden] tag.",
                        "Off hides them from everyone until unlocked.")
                .define("showHiddenToOps", true);
        BUILDER.pop();

        BUILDER.comment("The journal book").push("journal");
        JOURNAL_GIVE_NEW = BUILDER
                .comment("Hand every player a journal the first time they join. Once per player,",
                        "not per world visit; /quest journal give replaces a lost one either way.")
                .define("giveToNewPlayers", true);
        BUILDER.pop();

        BUILDER.comment("Givers -- where quests are offered").push("givers");
        GIVER_COOLDOWN_SECONDS = BUILDER
                .comment("Seconds before the same giver offers the same quest to the same player",
                        "again. Standing next to a lectern should not fill the chat.")
                .defineInRange("offerCooldownSeconds", 300, 5, 86400);
        GIVER_RADIUS = BUILDER
                .comment("How close (blocks) to an op-placed giver a player must be to hear the",
                        "offer. Data-declared givers carry their own radius.")
                .defineInRange("radius", 4.0D, 1.0D, 64.0D);
        BUILDER.pop();

        BUILDER.comment("Party quests").push("party");
        PARTY_GROUP_KINDS = BUILDER
                .comment("Which Standards group kind is 'the party', first registered kind wins.",
                        "Only consulted when SableCraft Standards is installed; without it every",
                        "quest is effectively solo. LegendQuest parties are the intended provider.")
                .defineListAllowEmpty("groupKinds",
                        java.util.List.of("legendquest:party", "standards:group"),
                        () -> "", o -> o instanceof String);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ChroniclerConfig() {}
}
