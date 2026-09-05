package com.sablednah.chronicler.neoforge;

import java.util.function.Supplier;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.core.QuestLog;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * Attachment registration. One attachment: the player's journal.
 *
 * <p>An attachment rather than SavedData because a journal belongs to the
 * player and must survive death -- {@code copyOnDeath} is the whole point.
 * Anything that has to answer for an OFFLINE player (a leaderboard, an admin
 * wiping someone's progress) belongs in SavedData instead, and will move
 * there the day such a question is asked.</p>
 */
public final class ChroniclerAttachments {

    private static final DeferredRegister<AttachmentType<?>> ATTACHMENTS =
            DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES, Chronicler.MODID);

    public static final Supplier<AttachmentType<QuestLog>> JOURNAL =
            ATTACHMENTS.register("journal", () -> AttachmentType
                    .builder(QuestLog::new)
                    .serialize(QuestLog.MAP_CODEC)
                    .copyOnDeath()
                    .build());

    public static void register(IEventBus modEventBus) {
        ATTACHMENTS.register(modEventBus);
    }

    private ChroniclerAttachments() {}
}
