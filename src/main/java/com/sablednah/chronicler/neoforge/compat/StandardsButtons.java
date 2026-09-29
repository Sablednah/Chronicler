package com.sablednah.chronicler.neoforge.compat;

import com.sablednah.standards.api.actions.Action;
import com.sablednah.standards.api.actions.Actions;

import net.minecraft.resources.Identifier;

/**
 * Two buttons on Standards' action bar (its {@code api/actions}, 1.8.0+): the quest tracker HUD, and
 * the full journal. The ONLY class that registers them, wired through {@code optionalIntegration},
 * so a Standards too old to have the bar costs the buttons and nothing else.
 *
 * <p>Each runs a command, as the seam requires, so a vanilla client (which Standards shows the bar
 * to as clickable chat) loses nothing: {@code /quest} says what the HUD would have shown, and
 * {@code /quest journal} hands over the book. A modded client runs the tracker button's handler
 * instead ({@code client/compat/StandardsHudButton}) and toggles the HUD, lit while it is shown.</p>
 */
public final class StandardsButtons {

    public static final String TRACKER = "chronicler:quest_tracker";
    public static final String JOURNAL = "chronicler:quest_journal";

    /** Nearer the anchor than StoryTeller's tools are not; a quest button is reached for less often. */
    private static final int JOURNAL_PRIORITY = 31, TRACKER_PRIORITY = 30;

    public static void register() {
        // The tooltip is the key's last word, prettified by Standards' client ("Quest journal").
        Actions.register(new Action(JOURNAL, JOURNAL_PRIORITY, Identifier.withDefaultNamespace("written_book"),
                "action.chronicler.quest_journal", "quest journal", player -> true));
        Actions.register(new Action(TRACKER, TRACKER_PRIORITY, Identifier.withDefaultNamespace("compass"),
                "action.chronicler.quest_tracker", "quest", player -> true));
    }

    private StandardsButtons() {}
}
