package com.sablednah.chronicler.client;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.network.HudPayload;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.resources.Identifier;
import net.neoforged.neoforge.client.event.RegisterGuiLayersEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;

/**
 * The quest tracker HUD: active quests and where each objective stands, down the left of the
 * screen. Draws what the server last sent ({@link HudPayload}) and decides nothing -- every word was
 * worded server-side, the same answers {@code /quest} and the action bar give a vanilla player.
 *
 * <p>Toggled by Shift+` or Standards' action-bar button, and remembered in
 * {@code chronicler-client.toml}. Steps aside for F1 and the debug screen, and draws nothing with no
 * quest active, so an empty HUD never sits on screen.</p>
 */
public final class ClientHud {

    private static final Identifier LAYER = Identifier.fromNamespaceAndPath(Chronicler.MODID, "quest_tracker");

    private static final int PAD = 3, LINE_H = 10, INDENT = 6, GAP = 3, LEFT = 4;
    private static final int BACKGROUND = 0x66000000, TRACKED_BAR = 0xFFE0B040;
    private static final String ELLIPSIS = "…";

    private static volatile HudPayload last;

    /** From the network thread's enqueued work, so on the client thread. */
    public static void accept(HudPayload payload) {
        last = payload;
    }

    static void clear() {
        last = null;
    }

    public static boolean shown() {
        return ChroniclerClientConfig.HUD_SHOWN.get();
    }

    /** The key and the action-bar button both land here; the choice is remembered. */
    public static void toggle() {
        ChroniclerClientConfig.HUD_SHOWN.set(!shown());
        ChroniclerClientConfig.HUD_SHOWN.save();
    }

    static void register(RegisterGuiLayersEvent event) {
        // Under the chat, so an open chat window is never covered by it.
        event.registerBelow(VanillaGuiLayers.CHAT, LAYER, ClientHud::render);
    }

    private static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        HudPayload p = last;
        if (p == null || p.quests().isEmpty() || !shown()) return;
        if (mc.options.hideGui || mc.getDebugOverlay().showDebugScreen()) return;
        Font font = mc.font;
        int maxW = ChroniclerClientConfig.HUD_WIDTH.get();

        // Measure first, so the background fits what is drawn and nothing more.
        int w = 0, h = 0;
        for (HudPayload.Quest q : p.quests()) {
            w = Math.max(w, Math.min(maxW, font.width(q.name())));
            for (Component line : q.lines()) w = Math.max(w, Math.min(maxW - INDENT, font.width(line)) + INDENT);
            h += LINE_H * (1 + q.lines().size()) + GAP;
        }
        boolean more = !p.more().getString().isEmpty();
        if (more) { w = Math.max(w, Math.min(maxW, font.width(p.more()))); h += LINE_H; }
        h -= GAP;

        int x = LEFT, y = (int) (g.guiHeight() * ChroniclerClientConfig.HUD_TOP.get());
        // Never off the bottom: a long list on a short window slides up rather than being cut.
        y = Math.max(2, Math.min(y, g.guiHeight() - h - 2 * PAD - 2));
        g.fill(x, y, x + w + 2 * PAD, y + h + 2 * PAD, BACKGROUND);

        int ty = y + PAD;
        for (HudPayload.Quest q : p.quests()) {
            if (q.tracked()) g.fill(x, ty - 1, x + 1, ty + LINE_H - 2, TRACKED_BAR);
            line(g, font, q.name(), x + PAD, ty, maxW);
            ty += LINE_H;
            for (Component l : q.lines()) {
                line(g, font, l, x + PAD + INDENT, ty, maxW - INDENT);
                ty += LINE_H;
            }
            ty += GAP;
        }
        if (more) line(g, font, p.more(), x + PAD, ty - GAP, maxW);
    }

    /** One line, cut short with an ellipsis rather than running off into the world. */
    private static void line(GuiGraphics g, Font font, Component text, int x, int y, int maxW) {
        if (font.width(text) <= maxW) {
            g.drawString(font, text, x, y, 0xFFFFFFFF);
            return;
        }
        FormattedText cut = font.substrByWidth(text, maxW - font.width(ELLIPSIS));
        g.drawString(font, Language.getInstance().getVisualOrder(FormattedText.composite(cut, FormattedText.of(ELLIPSIS))),
                x, y, 0xFFFFFFFF);
    }

    private ClientHud() {}
}
