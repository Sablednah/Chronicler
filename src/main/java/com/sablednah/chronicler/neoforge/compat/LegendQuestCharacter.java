package com.sablednah.chronicler.neoforge.compat;

import java.util.OptionalInt;
import java.util.OptionalLong;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.neoforge.Sheet;
import com.sablednah.legendquest.character.PlayerCharacter;
import com.sablednah.legendquest.neoforge.CharacterService;

import net.minecraft.server.level.ServerPlayer;

/**
 * The character sheet through LegendQuest. The ONLY class that may import
 * {@code com.sablednah.legendquest}. Uses LegendQuest's own entry points
 * ({@code addLevels}, {@code afterXpChange}, {@code grantSkillPoints}) rather
 * than re-deriving its curve -- they were made API for exactly this
 * (StoryTeller asked first), and a second copy of the arithmetic would drift.
 */
public final class LegendQuestCharacter {

    public static void register() {
        Sheet.install("legendquest", new Sheet.Provider() {
            @Override public OptionalInt level(ServerPlayer player) {
                return OptionalInt.of(CharacterService.level(player));
            }
            @Override public OptionalLong karma(ServerPlayer player) {
                return OptionalLong.of(CharacterService.data(player).karma());
            }
            @Override public boolean addKarma(ServerPlayer player, long delta) {
                CharacterService.ensureInitialised(player);
                CharacterService.data(player).addKarma(delta);
                sync(player); // the epithet and nameplate follow karma
                return true;
            }
            @Override public boolean addClassXp(ServerPlayer player, long amount) {
                PlayerCharacter pc = CharacterService.data(player);
                var classId = pc.mainClassId();
                if (classId.isEmpty()) return false;
                int before = CharacterService.level(player);
                pc.addXp(classId.get(), amount);
                CharacterService.afterXpChange(player, before); // announces a level like any other
                return true;
            }
            @Override public boolean addLevels(ServerPlayer player, int delta) {
                return CharacterService.addLevels(player, delta);
            }
            @Override public boolean grantSkillPoints(ServerPlayer player, int delta) {
                CharacterService.ensureInitialised(player);
                CharacterService.data(player).grantSkillPoints(delta);
                sync(player);
                return true;
            }
        });
        Chronicler.LOGGER.info("Chronicler: character sheets via LegendQuest");
    }

    /**
     * Push the sheet to the player. Guarded on its own: the write has already
     * landed, and a sync that cannot reach a client (a FakePlayer, a player
     * mid-logout) must not report the reward as failed.
     */
    private static void sync(ServerPlayer player) {
        try {
            CharacterService.refresh(player);
        } catch (RuntimeException e) {
            Chronicler.LOGGER.debug("Chronicler: LegendQuest refresh after a reward threw (write kept): {}", e.toString());
        }
    }

    private LegendQuestCharacter() {}
}
