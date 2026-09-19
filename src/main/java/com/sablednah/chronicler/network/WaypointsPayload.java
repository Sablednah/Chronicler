package com.sablednah.chronicler.network;

import java.util.ArrayList;
import java.util.List;

import com.sablednah.chronicler.Chronicler;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Where things are, for a client that can draw them on a map -- JourneyMap, if
 * installed; nothing else reads this today. A vanilla-first mod does not
 * require a map mod, so this rides the same {@code optional()} channel every
 * other payload does: a client without Chronicler never gets it, and a client
 * without JourneyMap gets it and does nothing with it. Sent once a second
 * alongside the ordinary polled-objective sweep, whether or not the journal
 * panel is open -- a map marker is not a journal page.
 *
 * @param marks every quest target, giver and led NPC this player currently knows about
 */
public record WaypointsPayload(List<Mark> marks) implements CustomPacketPayload {

    /** A quest's "go here"; {@link #GIVER} a quest waiting to be offered; {@link #NPC} someone being escorted. */
    public static final byte TARGET = 0, GIVER = 1, NPC = 2;

    /**
     * @param id        stable across resends for the same thing, so a client can update rather than
     *                  flicker a marker away and back -- {@code "<quest>:target"}, {@code "<quest>:giver"}, {@code "<npc-uuid>"}
     * @param label     plain text, resolved server-side same as everything else a player reads
     * @param dimension the level this is in; a mark for another dimension than the player's own is still sent (they may travel)
     */
    public record Mark(String id, String label, int x, int y, int z, Identifier dimension, byte kind) {}

    public static final Type<WaypointsPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Chronicler.MODID, "waypoints"));

    public static final StreamCodec<ByteBuf, WaypointsPayload> CODEC = StreamCodec.of(WaypointsPayload::encode, WaypointsPayload::decode);

    private static void encode(ByteBuf buf, WaypointsPayload p) {
        ByteBufCodecs.VAR_INT.encode(buf, p.marks.size());
        for (Mark m : p.marks) {
            ByteBufCodecs.stringUtf8(256).encode(buf, m.id());
            ByteBufCodecs.stringUtf8(256).encode(buf, m.label());
            ByteBufCodecs.VAR_INT.encode(buf, m.x());
            ByteBufCodecs.VAR_INT.encode(buf, m.y());
            ByteBufCodecs.VAR_INT.encode(buf, m.z());
            ByteBufCodecs.stringUtf8(256).encode(buf, m.dimension().toString());
            buf.writeByte(m.kind());
        }
    }

    private static WaypointsPayload decode(ByteBuf buf) {
        int count = ByteBufCodecs.VAR_INT.decode(buf);
        List<Mark> marks = new ArrayList<>(count);
        for (int n = 0; n < count; n++) {
            String id = ByteBufCodecs.stringUtf8(256).decode(buf);
            String label = ByteBufCodecs.stringUtf8(256).decode(buf);
            int x = ByteBufCodecs.VAR_INT.decode(buf);
            int y = ByteBufCodecs.VAR_INT.decode(buf);
            int z = ByteBufCodecs.VAR_INT.decode(buf);
            Identifier dimension = Identifier.parse(ByteBufCodecs.stringUtf8(256).decode(buf));
            byte kind = buf.readByte();
            marks.add(new Mark(id, label, x, y, z, dimension, kind));
        }
        return new WaypointsPayload(marks);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
