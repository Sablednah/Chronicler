package com.sablednah.chronicler.neoforge;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.level.ServerPlayer;

/**
 * Player-facing output. Every string passes through here, so the two
 * version-fragile calls (chat vs overlay, which 26.1 split into two methods)
 * and the colour handling live in exactly one place.
 */
public final class Feedback {

    public static void actionBar(ServerPlayer player, String text) {
        player.displayClientMessage(colored(text), true);
    }

    public static void chat(ServerPlayer player, String text) {
        player.displayClientMessage(colored(text), false);
    }

    public static void chat(ServerPlayer player, Component message) {
        player.displayClientMessage(message, false);
    }

    /**
     * An occasion: chat line, title card, and the toast chime. All vanilla
     * packets, so an unmodded client gets the whole show. The sound goes down
     * this one connection rather than into the world -- a finished quest is
     * not the business of everyone standing nearby.
     */
    public static void fanfare(ServerPlayer player, String chatLine, String title, String subtitle) {
        chat(player, chatLine);
        if (!com.sablednah.chronicler.ChroniclerConfig.FANFARE.get() || player.connection == null) return;
        player.connection.send(new net.minecraft.network.protocol.game
                .ClientboundSetTitlesAnimationPacket(5, 40, 10));
        player.connection.send(new net.minecraft.network.protocol.game
                .ClientboundSetTitleTextPacket(colored(title)));
        player.connection.send(new net.minecraft.network.protocol.game
                .ClientboundSetSubtitleTextPacket(colored(subtitle)));
        player.connection.send(new net.minecraft.network.protocol.game.ClientboundSoundPacket(
                net.minecraft.core.registries.BuiltInRegistries.SOUND_EVENT.wrapAsHolder(
                        net.minecraft.sounds.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE),
                net.minecraft.sounds.SoundSource.PLAYERS,
                player.getX(), player.getY(), player.getZ(), 1.0F, 1.0F, 0L));
    }

    /**
     * Turn {@code &} colour codes into a styled component.
     *
     * <p><b>Real styles, never section signs in the text.</b> A literal with
     * {@code §} in it renders in game and is wrong everywhere else, because
     * {@code getString()} hands the codes back verbatim -- console, log and
     * RCON get {@code §7Quest: §f...} instead of a sentence. Found in three of
     * the four sibling ports, always by someone reading output from outside.</p>
     *
     * <p><b>Only where a real code follows.</b> A blind {@code replace('&','§')}
     * mangles "Tom &amp; Jerry" the first time player text passes through.</p>
     */
    public static Component colored(String text) {
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            ChatFormatting code = (c == '&' || c == '§') && i + 1 < text.length()
                    ? ChatFormatting.getByCode(text.charAt(i + 1))
                    : null;
            if (code == null) {
                run.append(c);
                continue;
            }
            if (!run.isEmpty()) {
                out.append(Component.literal(run.toString()).withStyle(style));
                run.setLength(0);
            }
            style = advance(style, code);
            i++;
        }
        if (!run.isEmpty()) {
            out.append(Component.literal(run.toString()).withStyle(style));
        }
        return out;
    }

    /**
     * Legacy rule: a colour clears formatting before it, {@code &r} clears
     * everything, bold/italic/underline/strike/obfuscated accumulate.
     * Classified by the code letter rather than {@code isFormat()}, which 26.2
     * removed -- {@code klmno} are the formats on every version.
     */
    private static Style advance(Style style, ChatFormatting code) {
        if (code == ChatFormatting.RESET) {
            return Style.EMPTY;
        }
        return "klmno".indexOf(code.getChar()) >= 0 ? style.applyFormat(code) : Style.EMPTY.withColor(code);
    }

    private Feedback() {}
}
