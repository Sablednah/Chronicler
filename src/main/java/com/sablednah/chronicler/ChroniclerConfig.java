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
    public static final ModConfigSpec.IntValue GIVER_SECOND_CLICK_SECONDS;
    public static final ModConfigSpec.BooleanValue GIVER_MARKERS;
    public static final ModConfigSpec.BooleanValue GIVER_PROTECT;
    public static final ModConfigSpec.ConfigValue<String> GIVER_MARKER_TEXT;
    public static final ModConfigSpec.ConfigValue<String> GIVER_MARKER_ACTIVE;
    public static final ModConfigSpec.ConfigValue<String> GIVER_MARKER_COMPLETE;
    public static final ModConfigSpec.ConfigValue<String> GIVER_MARKER_LOCKED;

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
        GIVER_SECOND_CLICK_SECONDS = BUILDER
                .comment("A right-click on a giver makes the offer; a second click within this many",
                        "seconds accepts it (so does the Accept button). 0 accepts on the first click.")
                .defineInRange("secondClickSeconds", 20, 0, 600);
        GIVER_PROTECT = BUILDER
                .comment("Giver blocks refuse to break and are skipped by explosions.",
                        "An admin sneaking while breaking one still can.")
                .define("protect", true);
        GIVER_MARKERS = BUILDER
                .comment("Float a mark over every giver (a block, an op-placed block, an NPC).",
                        "Sent to each player privately, so it shows THEIR state; vanilla clients see it.")
                .define("markers", true);
        GIVER_MARKER_TEXT = BUILDER
                .comment("The mark while the player could take the quest. '&' colour codes work;",
                        "{quest} is the quest's name. Empty hides it.")
                .define("markerText", "&e&l!");
        GIVER_MARKER_ACTIVE = BUILDER
                .comment("The mark while the player is on the quest.")
                .define("markerActive", "&7&l?");
        GIVER_MARKER_COMPLETE = BUILDER
                .comment("The mark once the player has finished it (repeatables show markerText again).")
                .define("markerComplete", "&a&l\u2714");
        GIVER_MARKER_LOCKED = BUILDER
                .comment("The mark while the quest is locked for the player (prerequisites, conditions,",
                        "cooldown). Empty, the default, shows nothing.")
                .define("markerLocked", "");
        BUILDER.pop();

        BUILDER.comment("Shipped content").push("content");
        ZARP = BUILDER
                .comment("The Zombie Apocalypse Roleplay questline, built in as a datapack.",
                        "auto = on when ZombieMod is installed; on / off force it. Applies on restart.")
                .defineEnum("zarp", ContentMode.AUTO);
        PROLOGUE = BUILDER
                .comment("The built-in fantasy prologue (First Steps, Night Watch, The Beacon...).",
                        "auto = on unless ZARP is on; on / off force it. Applies on restart.")
                .defineEnum("prologue", ContentMode.AUTO);
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

    public static final ModConfigSpec.EnumValue<ContentMode> ZARP;
    public static final ModConfigSpec.EnumValue<ContentMode> PROLOGUE;

    public enum ContentMode { AUTO, ON, OFF }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ChroniclerConfig() {}
}
