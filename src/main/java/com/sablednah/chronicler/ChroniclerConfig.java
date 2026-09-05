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
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ChroniclerConfig() {}
}
