package com.sablednah.chronicler.client;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * {@code chronicler-client.toml}: this player's own screen, which the server has no say in. Only the
 * quest tracker HUD lives here; what it lists (and how many) is the server's, in the common config.
 */
public final class ChroniclerClientConfig {

    public static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue HUD_SHOWN;
    public static final ModConfigSpec.DoubleValue HUD_TOP;
    public static final ModConfigSpec.IntValue HUD_WIDTH;

    static {
        BUILDER.comment("The quest tracker HUD on the left of the screen").push("hud");
        HUD_SHOWN = BUILDER
                .comment("Shown or not. Shift+` (Controls: Toggle Quest Tracker) and the action-bar",
                        "button flip this and it is remembered.")
                .define("shown", true);
        HUD_TOP = BUILDER
                .comment("Where its top sits, as a fraction of the screen's height. 0.3 clears a",
                        "top-left minimap and stays above the chat.")
                .defineInRange("top", 0.3D, 0D, 0.9D);
        HUD_WIDTH = BUILDER
                .comment("Widest it grows, in GUI pixels; longer lines are cut short with an ellipsis.")
                .defineInRange("width", 170, 80, 400);
        BUILDER.pop();
    }

    public static final ModConfigSpec SPEC = BUILDER.build();

    private ChroniclerClientConfig() {}
}
