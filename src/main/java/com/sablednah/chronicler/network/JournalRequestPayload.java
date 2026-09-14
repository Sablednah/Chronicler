package com.sablednah.chronicler.network;

import com.sablednah.chronicler.Chronicler;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The panel asking the server for something: open the journal (the key),
 * refresh what is on screen, or run one of the buttons it was sent. The server
 * decides everything; a request it does not like is dropped.
 *
 * @param action {@link #OPEN}, {@link #REFRESH} or {@link #RUN}
 * @param arg    OPEN: a quest id to open at, or empty; RUN: the button's command
 */
public record JournalRequestPayload(int action, String arg) implements CustomPacketPayload {

    public static final int OPEN = 0, REFRESH = 1, RUN = 2;

    public static final Type<JournalRequestPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Chronicler.MODID, "journal_request"));

    public static final StreamCodec<ByteBuf, JournalRequestPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, JournalRequestPayload::action,
            ByteBufCodecs.stringUtf8(512), JournalRequestPayload::arg,
            JournalRequestPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
