package com.sablednah.chronicler.neoforge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.core.QuestLog;
import com.sablednah.chronicler.core.QuestScope;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardSpec;
import com.sablednah.chronicler.data.RewardTypes;

import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/**
 * What a party has done, for the members who were not there. A party quest only ever reached the members
 * online at the time; this remembers, by the party's stable id ({@link Party#id}), every party-scope quest
 * it finished, every ending it reached and every player flag its choices set, so a member who logs in
 * later (or joins the party later) is brought in line: done is done for the party (ZARP: one world, one
 * party). SavedData because it must answer for players who are offline -- Groups answers only for the
 * online, by design.
 *
 * <p>Catching up marks the quests complete and grants their rewards that belong to a person (xp, items,
 * money, reputation, karma...), never what already happened to the world (spawns, lines said, commands,
 * titles, blocks) -- the world has those. A quest still under way with a member online is joined at their
 * stage and progress, the same rule as joining mid-quest.</p>
 */
public final class PartyLedger extends SavedData {

    private static final Codec<PartyLedger> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("done", Map.of()).forGetter(s -> copy(s.done)),
            Codec.unboundedMap(Codec.STRING, Codec.STRING.listOf()).optionalFieldOf("endings", Map.of()).forGetter(s -> copy(s.endings)),
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.BOOL)).optionalFieldOf("flags", Map.of()).forGetter(s -> Map.copyOf(s.flags)))
            .apply(i, PartyLedger::new));

    public static final SavedDataType<PartyLedger> TYPE =
            new SavedDataType<>("chronicler_party", PartyLedger::new, CODEC, null);

    /** party -> quest ids finished */
    private final Map<String, Set<String>> done = new LinkedHashMap<>();
    /** party -> "chapter|ending" */
    private final Map<String, Set<String>> endings = new LinkedHashMap<>();
    /** party -> "owner|flag" -> value */
    private final Map<String, Map<String, Boolean>> flags = new LinkedHashMap<>();

    public PartyLedger() {}

    private PartyLedger(Map<String, List<String>> done, Map<String, List<String>> endings, Map<String, Map<String, Boolean>> flags) {
        done.forEach((k, v) -> this.done.put(k, new LinkedHashSet<>(v)));
        endings.forEach((k, v) -> this.endings.put(k, new LinkedHashSet<>(v)));
        flags.forEach((k, v) -> this.flags.put(k, new LinkedHashMap<>(v)));
    }

    private static Map<String, List<String>> copy(Map<String, Set<String>> in) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        in.forEach((k, v) -> out.put(k, List.copyOf(v)));
        return out;
    }

    public static PartyLedger get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(TYPE);
    }

    // --- recording, as the party does things ---

    /** Is this quest one the ledger keeps: party scope, and not a repeatable (those stay each member's own). */
    static boolean kept(MinecraftServer server, Quest quest) {
        return quest != null && !quest.repeatable() && QuestEngine.scopeOf(server, quest) == QuestScope.PARTY;
    }

    static void recordDone(ServerPlayer player, Identifier quest, Quest q) {
        MinecraftServer server = player.level().getServer();
        if (!kept(server, q)) return;
        Party.id(player).ifPresent(p -> { if (get(server).done.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(quest.toString())) get(server).setDirty(); });
    }

    static void recordEnding(ServerPlayer player, Quest q, String ending) {
        MinecraftServer server = player.level().getServer();
        if (q == null || QuestEngine.scopeOf(server, q) != QuestScope.PARTY) return;
        String key = q.chapter() + "|" + ending.trim().toLowerCase(java.util.Locale.ROOT);
        Party.id(player).ifPresent(p -> { if (get(server).endings.computeIfAbsent(p, k -> new LinkedHashSet<>()).add(key)) get(server).setDirty(); });
    }

    static void recordFlag(ServerPlayer player, Quest q, Identifier owner, String name, boolean value) {
        MinecraftServer server = player.level().getServer();
        if (q == null || QuestEngine.scopeOf(server, q) != QuestScope.PARTY) return;
        String key = owner + "|" + FlagStore.normalise(name);
        Party.id(player).ifPresent(p -> {
            Boolean was = get(server).flags.computeIfAbsent(p, k -> new LinkedHashMap<>()).put(key, value);
            if (was == null || was != value) get(server).setDirty();
        });
    }

    /** A replayed chapter starts over for the party as well, or catching up would finish it again. */
    static void forgetChapter(ServerPlayer player, Identifier chapter, List<Identifier> quests) {
        MinecraftServer server = player.level().getServer();
        Party.id(player).ifPresent(p -> {
            PartyLedger l = get(server);
            boolean changed = false;
            Set<String> d = l.done.get(p);
            if (d != null) for (Identifier q : quests) changed |= d.remove(q.toString());
            Set<String> e = l.endings.get(p);
            if (e != null) changed |= e.removeIf(k -> k.startsWith(chapter + "|"));
            Map<String, Boolean> f = l.flags.get(p);
            if (f != null) changed |= f.keySet().removeIf(k -> k.startsWith(chapter + "|"));
            if (changed) l.setDirty();
        });
    }

    // --- catching up ---

    /**
     * The party each online player was last caught up with. The periodic pass catches up only a player whose
     * party changed (they joined one): run blind every half minute, it would put back a party quest somebody
     * had just abandoned. Session state, cleared on ServerStopped like every other.
     */
    private static final Map<java.util.UUID, String> LAST_PARTY = new java.util.concurrent.ConcurrentHashMap<>();

    /** The periodic pass: catch up anyone whose party is not the one they were last caught up with. */
    public static void catchUpMovers(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String now = Party.id(player).orElse("");
            if (!now.equals(LAST_PARTY.get(player.getUUID()))) catchUp(player);
        }
    }

    public static void forget(java.util.UUID player) { LAST_PARTY.remove(player); }

    static void clearSession() { LAST_PARTY.clear(); }

    static int sessionStateCount() { return LAST_PARTY.size(); }

    /** A reward that belongs to the person, not the world: granted again to a member catching up. */
    static boolean personal(RewardSpec r) {
        RewardSpec e = RewardTypes.When.unwrap(r);
        return e instanceof RewardTypes.Item || e instanceof RewardTypes.Xp || e instanceof RewardTypes.Money
                || e instanceof RewardTypes.Reputation || e instanceof RewardTypes.Karma || e instanceof RewardTypes.ClassXp
                || e instanceof RewardTypes.Levels || e instanceof RewardTypes.SkillPoints || e instanceof RewardTypes.Advancement
                || (e instanceof RewardTypes.Flag f && f.player());
    }

    /**
     * Bring this player's journal in line with their party: its flags, endings and finished quests, then any
     * party quest an online member is on and they are not. Quiet when there is nothing to do; one line each
     * for what was finished and what was joined when there is.
     */
    public static void catchUp(ServerPlayer player) {
        var party = Party.id(player);
        LAST_PARTY.put(player.getUUID(), party.orElse(""));
        if (party.isEmpty()) return;
        MinecraftServer server = player.level().getServer();
        PartyLedger l = get(server);
        QuestLog log = QuestEngine.journal(player);

        l.flags.getOrDefault(party.get(), Map.of()).forEach((key, value) -> {
            int bar = key.indexOf('|');
            String name = key.substring(bar + 1);
            if (log.hasFlag(name) == value) return;
            Identifier owner = Identifier.tryParse(key.substring(0, bar));
            if (owner != null) log.setFlag(name, value, owner); else log.setFlag(name, value);
        });
        for (String key : l.endings.getOrDefault(party.get(), Set.of())) {
            int bar = key.indexOf('|');
            Identifier chapter = Identifier.tryParse(key.substring(0, bar));
            if (chapter != null) log.addEnding(chapter, key.substring(bar + 1));
        }

        List<String> finished = new ArrayList<>();
        boolean rewards = ChroniclerConfig.PARTY_CATCH_UP_REWARDS.get();
        for (String s : l.done.getOrDefault(party.get(), Set.of())) {
            Identifier id = Identifier.tryParse(s);
            if (id == null || log.isComplete(id)) continue;
            var holder = QuestEngine.questFor(player, id);
            if (holder.isEmpty() || !kept(server, holder.get())) continue;
            Quest q = holder.get();
            log.complete(id);
            if (rewards) for (RewardSpec r : q.rewards()) if (personal(r)) Rewards.grant(player, r, id, q);
            finished.add(q.name());
        }

        List<String> joined = new ArrayList<>();
        for (ServerPlayer m : Party.members(player)) {
            if (m == player) continue;
            for (var e : QuestEngine.journal(m).activeView().entrySet()) {
                Identifier id = e.getKey();
                if (log.isActive(id) || log.isComplete(id)) continue;
                var holder = QuestEngine.questFor(m, id);
                if (holder.isEmpty() || !kept(server, holder.get()) || !QuestEngine.available(player, id, holder.get())) continue;
                log.startFrom(id, e.getValue());
                joined.add(holder.get().name());
            }
        }

        if (!finished.isEmpty()) Feedback.chat(player, Lang.fmt("msg.party.caught_up", "quests", String.join(Lang.get("cond.list_join"), finished)));
        if (!joined.isEmpty()) Feedback.chat(player, Lang.fmt("msg.party.joined", "quests", String.join(Lang.get("cond.list_join"), joined)));
        if (!finished.isEmpty()) Achievements.sweep(player);
    }

    /** How many quests the ledger has this party finishing; the self-test's window. */
    int doneCount(String party) {
        return done.getOrDefault(party, Set.of()).size();
    }
}
