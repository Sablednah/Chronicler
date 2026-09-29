package com.sablednah.chronicler.network;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.Chronicler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The quest tracker HUD's content: every active quest and where each of its current objectives
 * stands, as text resolved server-side like everything else a player reads. The same answers the
 * action bar and {@code /quest} give, laid out for a panel a modded client can keep on screen.
 *
 * <p>Sent only when it changes, on the ordinary polled-objective sweep -- unlike waypoints, a HUD
 * that has not moved needs nothing. A vanilla client never gets it ({@code optional()} channel,
 * {@code Net.sendIfAble}); it has {@code /quest} and the action bar.</p>
 *
 * @param quests tracked first, then the rest in the journal's own order, up to the server's cap
 * @param more   "+N more" past the cap, or empty
 */
public record HudPayload(List<Quest> quests, Component more) implements CustomPacketPayload {

    /**
     * @param lines one per current objective, finished ones included (drawn dim), then a deadline
     *              line if the beat has one -- each already worded by {@code messages.yml}
     */
    public record Quest(String id, Component name, boolean tracked, List<Component> lines) {}

    public static final Type<HudPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Chronicler.MODID, "hud"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HudPayload> CODEC =
            StreamCodec.of(HudPayload::encode, HudPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, HudPayload p) {
        buf.writeVarInt(p.quests.size());
        for (Quest q : p.quests) {
            buf.writeUtf(q.id());
            ComponentSerialization.STREAM_CODEC.encode(buf, q.name());
            buf.writeBoolean(q.tracked());
            buf.writeVarInt(q.lines().size());
            for (Component line : q.lines()) ComponentSerialization.STREAM_CODEC.encode(buf, line);
        }
        ComponentSerialization.STREAM_CODEC.encode(buf, p.more);
    }

    private static HudPayload decode(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Quest> quests = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            String id = buf.readUtf();
            Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
            boolean tracked = buf.readBoolean();
            int lineCount = buf.readVarInt();
            List<Component> lines = new ArrayList<>(lineCount);
            for (int l = 0; l < lineCount; l++) lines.add(ComponentSerialization.STREAM_CODEC.decode(buf));
            quests.add(new Quest(id, name, tracked, lines));
        }
        return new HudPayload(quests, ComponentSerialization.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
