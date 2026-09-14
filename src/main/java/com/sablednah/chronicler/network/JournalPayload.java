package com.sablednah.chronicler.network;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.sablednah.chronicler.Chronicler;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * The journal panel's whole content, server to client: every chapter and quest
 * this player may see, their state, their page text and the buttons that make
 * sense. All text is resolved server-side through {@code Lang} -- the client
 * carries no catalogue and knows no rules; it draws what it is sent and runs
 * the commands the buttons name.
 *
 * <p>{@code open} asks the client to show the panel (the key, the item,
 * {@code /quest journal}); without it the payload only refreshes what is
 * already on screen. {@code focus} is a quest id to open at, or empty.</p>
 */
public record JournalPayload(boolean open, String focus, Component title, Component progress,
        Map<String, Component> labels, List<ChapterView> chapters) implements CustomPacketPayload {

    public static final byte LOCKED = 0, AVAILABLE = 1, ACTIVE = 2, COMPLETE = 3, COOLDOWN = 4;

    /** A button: what it says, its hover text, and the command it runs (no slash). */
    public record Button(Component label, Component tip, String command) {}

    public record QuestView(String id, Component name, byte status, String icon, boolean tracked,
            List<String> requires, List<Component> lines, List<Button> buttons) {}

    /** A prerequisite from another chapter, shown as a stand-in on this chapter's map. */
    public record External(String id, Component name, Component chapter, byte status, String icon) {}

    public record ChapterView(String id, Component name, String icon, List<Component> lines,
            List<Button> buttons, List<QuestView> quests, List<External> externals) {}

    public static final Type<JournalPayload> TYPE =
            new Type<>(Identifier.fromNamespaceAndPath(Chronicler.MODID, "journal"));

    public static final StreamCodec<RegistryFriendlyByteBuf, JournalPayload> CODEC =
            StreamCodec.of(JournalPayload::encode, JournalPayload::decode);

    private static void encode(RegistryFriendlyByteBuf buf, JournalPayload p) {
        buf.writeBoolean(p.open);
        buf.writeUtf(p.focus);
        text(buf, p.title);
        text(buf, p.progress);
        buf.writeVarInt(p.labels.size());
        p.labels.forEach((k, v) -> { buf.writeUtf(k); text(buf, v); });
        buf.writeVarInt(p.chapters.size());
        for (ChapterView c : p.chapters) {
            buf.writeUtf(c.id());
            text(buf, c.name());
            buf.writeUtf(c.icon());
            texts(buf, c.lines());
            buttons(buf, c.buttons());
            buf.writeVarInt(c.quests().size());
            for (QuestView q : c.quests()) {
                buf.writeUtf(q.id());
                text(buf, q.name());
                buf.writeByte(q.status());
                buf.writeUtf(q.icon());
                buf.writeBoolean(q.tracked());
                buf.writeVarInt(q.requires().size());
                for (String r : q.requires()) buf.writeUtf(r);
                texts(buf, q.lines());
                buttons(buf, q.buttons());
            }
            buf.writeVarInt(c.externals().size());
            for (External e : c.externals()) {
                buf.writeUtf(e.id());
                text(buf, e.name());
                text(buf, e.chapter());
                buf.writeByte(e.status());
                buf.writeUtf(e.icon());
            }
        }
    }

    private static JournalPayload decode(RegistryFriendlyByteBuf buf) {
        boolean open = buf.readBoolean();
        String focus = buf.readUtf();
        Component title = text(buf);
        Component progress = text(buf);
        int labelCount = buf.readVarInt();
        Map<String, Component> labels = new LinkedHashMap<>();
        for (int n = 0; n < labelCount; n++) labels.put(buf.readUtf(), text(buf));
        int chapterCount = buf.readVarInt();
        List<ChapterView> chapters = new ArrayList<>(chapterCount);
        for (int n = 0; n < chapterCount; n++) {
            String id = buf.readUtf();
            Component name = text(buf);
            String icon = buf.readUtf();
            List<Component> lines = texts(buf);
            List<Button> buttons = buttons(buf);
            int questCount = buf.readVarInt();
            List<QuestView> quests = new ArrayList<>(questCount);
            for (int q = 0; q < questCount; q++) {
                String qid = buf.readUtf();
                Component qname = text(buf);
                byte status = buf.readByte();
                String qicon = buf.readUtf();
                boolean tracked = buf.readBoolean();
                int reqCount = buf.readVarInt();
                List<String> requires = new ArrayList<>(reqCount);
                for (int r = 0; r < reqCount; r++) requires.add(buf.readUtf());
                quests.add(new QuestView(qid, qname, status, qicon, tracked, requires, texts(buf), buttons(buf)));
            }
            int externalCount = buf.readVarInt();
            List<External> externals = new ArrayList<>(externalCount);
            for (int e = 0; e < externalCount; e++) {
                externals.add(new External(buf.readUtf(), text(buf), text(buf), buf.readByte(), buf.readUtf()));
            }
            chapters.add(new ChapterView(id, name, icon, lines, buttons, quests, externals));
        }
        return new JournalPayload(open, focus, title, progress, labels, chapters);
    }

    private static void text(RegistryFriendlyByteBuf buf, Component c) {
        ComponentSerialization.STREAM_CODEC.encode(buf, c);
    }

    private static Component text(RegistryFriendlyByteBuf buf) {
        return ComponentSerialization.STREAM_CODEC.decode(buf);
    }

    private static void texts(RegistryFriendlyByteBuf buf, List<Component> lines) {
        buf.writeVarInt(lines.size());
        for (Component c : lines) text(buf, c);
    }

    private static List<Component> texts(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Component> out = new ArrayList<>(count);
        for (int n = 0; n < count; n++) out.add(text(buf));
        return out;
    }

    private static void buttons(RegistryFriendlyByteBuf buf, List<Button> buttons) {
        buf.writeVarInt(buttons.size());
        for (Button b : buttons) {
            text(buf, b.label());
            text(buf, b.tip());
            buf.writeUtf(b.command());
        }
    }

    private static List<Button> buttons(RegistryFriendlyByteBuf buf) {
        int count = buf.readVarInt();
        List<Button> out = new ArrayList<>(count);
        for (int n = 0; n < count; n++) out.add(new Button(text(buf), text(buf), buf.readUtf()));
        return out;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
