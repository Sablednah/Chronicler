package com.sablednah.chronicler.client;

import com.sablednah.chronicler.network.JournalPayload;
import com.sablednah.chronicler.network.JournalRequestPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;

/** The journal panel's content as this server last sent it; null on a server without Chronicler. */
public final class ClientJournal {

    private static volatile JournalPayload last;

    public static JournalPayload get() {
        return last;
    }

    /** From the network thread's enqueued work, so on the client thread. */
    public static void accept(JournalPayload payload) {
        last = payload;
        if (!payload.open()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof JournalScreen open) {
            open.focus(payload.focus());
        } else {
            mc.setScreen(new JournalScreen(payload.focus()));
        }
    }

    /**
     * Is this server running Chronicler? Asked of the connection, so a server
     * without it is never sent a packet it would disconnect us for. Silent when
     * not: a key that does nothing beats a complaint every time it is brushed.
     */
    static boolean serverListens() {
        var connection = Minecraft.getInstance().getConnection();
        return connection != null && connection.hasChannel(JournalRequestPayload.TYPE);
    }

    static void open() {
        send(JournalRequestPayload.OPEN, "");
    }

    static void refresh() {
        send(JournalRequestPayload.REFRESH, "");
    }

    static void run(String command) {
        send(JournalRequestPayload.RUN, command);
    }

    private static void send(int action, String arg) {
        if (serverListens()) ClientPacketDistributor.sendToServer(new JournalRequestPayload(action, arg));
    }

    static void clear() {
        last = null;
    }

    private ClientJournal() {}
}
