package com.sablednah.chronicler.neoforge;

import java.util.OptionalInt;
import java.util.OptionalLong;

import net.minecraft.server.level.ServerPlayer;

/**
 * The character sheet -- the neutral bridge to LegendQuest. Level, karma,
 * class XP, levels and skill points. Empty answers mean "this server keeps
 * no character sheets"; a quest that pays karma says so, and one that needs
 * a level is never available. {@code compat/LegendQuestCharacter} fills it.
 */
public final class Sheet {

    public interface Provider {
        OptionalInt level(ServerPlayer player);
        OptionalLong karma(ServerPlayer player);
        boolean addKarma(ServerPlayer player, long delta);
        boolean addClassXp(ServerPlayer player, long amount);
        boolean addLevels(ServerPlayer player, int delta);
        boolean grantSkillPoints(ServerPlayer player, int delta);
    }

    private static volatile Provider provider = null;
    private static volatile String providerName = "none";

    public static boolean available() { return provider != null; }

    public static String providerName() { return providerName; }

    public static OptionalInt level(ServerPlayer player) {
        Provider p = provider;
        try { return p == null ? OptionalInt.empty() : p.level(player); } catch (RuntimeException e) { return OptionalInt.empty(); }
    }

    public static OptionalLong karma(ServerPlayer player) {
        Provider p = provider;
        try { return p == null ? OptionalLong.empty() : p.karma(player); } catch (RuntimeException e) { return OptionalLong.empty(); }
    }

    public static boolean addKarma(ServerPlayer player, long delta) {
        Provider p = provider;
        try { return p != null && p.addKarma(player, delta); } catch (RuntimeException e) { return false; }
    }

    public static boolean addClassXp(ServerPlayer player, long amount) {
        Provider p = provider;
        try { return p != null && p.addClassXp(player, amount); } catch (RuntimeException e) { return false; }
    }

    public static boolean addLevels(ServerPlayer player, int delta) {
        Provider p = provider;
        try { return p != null && p.addLevels(player, delta); } catch (RuntimeException e) { return false; }
    }

    public static boolean grantSkillPoints(ServerPlayer player, int delta) {
        Provider p = provider;
        try { return p != null && p.grantSkillPoints(player, delta); } catch (RuntimeException e) { return false; }
    }

    public static void install(String name, Provider p) {
        provider = p;
        providerName = name;
    }

    private Sheet() {}
}
