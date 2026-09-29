package com.sablednah.chronicler.client.compat;

import com.sablednah.chronicler.client.ClientHud;
import com.sablednah.chronicler.neoforge.compat.StandardsButtons;
import com.sablednah.standards.api.actions.Actions;

/**
 * The client end of the quest-tracker button ({@link StandardsButtons}): toggle the HUD here, and
 * light the button while it is up. The ONLY client class that imports Standards; wired through
 * {@code optionalIntegration}, so a Standards without the bar's client seam costs the button's
 * toggle and nothing else -- it then sends its command, {@code /quest}, and the answer comes in chat.
 */
public final class StandardsHudButton {

    public static void register() {
        Actions.registerHandler(StandardsButtons.TRACKER, ClientHud::toggle);
        Actions.registerClientState(StandardsButtons.TRACKER, ClientHud::shown);
    }

    private StandardsHudButton() {}
}
