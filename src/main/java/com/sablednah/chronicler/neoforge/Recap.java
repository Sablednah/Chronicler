package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.data.Quest;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * The story so far, told from what the player has done: every finished quest's {@code recap} line, in
 * chapter then quest order, and under each the {@code recap_endings} line for any ending they reached
 * through it. Nothing is remembered for this beyond the journal already keeps (completions, endings);
 * the words come from the quest files, so a pack without them simply has no recap. A mini quest's
 * line is read through the player's filled copy, so its holes read as what they were.
 */
public final class Recap {

    public static List<String> of(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        QuestLog log = QuestEngine.journal(player);
        String name = player.getName().getString();
        TreeMap<Identifier, List<net.minecraft.core.Holder.Reference<Quest>>> byChapter = new TreeMap<>(QuestEngine.chapterOrder(server));
        QuestEngine.quests(server).listElements().forEach(h -> {
            if (log.isComplete(h.key().identifier())) byChapter.computeIfAbsent(h.value().chapter(), k -> new ArrayList<>()).add(h);
        });
        List<String> out = new ArrayList<>();
        for (var entry : byChapter.entrySet()) {
            var mine = entry.getValue();
            mine.sort(java.util.Comparator.comparingInt((net.minecraft.core.Holder.Reference<Quest> h) -> h.value().order())
                    .thenComparing(h -> h.key().identifier().toString()));
            for (var h : mine) {
                Quest q = QuestEngine.questFor(player, h.key().identifier()).orElse(h.value());
                q.recap().filter(r -> !r.isBlank()).ifPresent(r -> out.add(r.replace("{player}", name)));
                var reached = log.endings(entry.getKey());
                for (String ending : q.endings()) {
                    String line = q.recapEndings().get(ending);
                    if (line != null && !line.isBlank() && reached.contains(ending.trim().toLowerCase(java.util.Locale.ROOT))) {
                        out.add(line.replace("{player}", name));
                    }
                }
                q.recapFlags().forEach((flag, line) -> {
                    if (line == null || line.isBlank()) return;
                    boolean set = flag.startsWith("world:") ? FlagStore.get(server).is(flag.substring("world:".length())) : log.hasFlag(flag);
                    if (set) out.add(line.replace("{player}", name));
                });
            }
        }
        return out;
    }

    private Recap() {}
}
