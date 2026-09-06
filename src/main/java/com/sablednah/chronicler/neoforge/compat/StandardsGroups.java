package com.sablednah.chronicler.neoforge.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.ChroniclerConfig;
import com.sablednah.chronicler.neoforge.Party;
import com.sablednah.standards.api.groups.Group;
import com.sablednah.standards.api.groups.GroupKind;
import com.sablednah.standards.api.groups.Groups;

import net.minecraft.server.level.ServerPlayer;

/**
 * Party membership through Standards' Groups seam. The ONLY class that may
 * import {@code com.sablednah.standards.api.groups}; loaded only when
 * Standards is present, via {@code Chronicler.optionalIntegration}.
 *
 * <p>Which group kind is "the party" is config (`party.groupKinds`), first
 * registered kind wins. LegendQuest parties are the intended provider; until
 * LegendQuest registers them, Standards' own {@code standards:group} is the
 * fallback in the shipped default.</p>
 */
public final class StandardsGroups {

    /**
     * Called from the mod constructor. Reads NO config here: config is not
     * loaded while mods are being constructed, and asking throws
     * "Cannot get config value before config is loaded", which failed mod
     * construction and took the server down. The kind list is read per call
     * instead, which is also what lets a config edit apply live.
     */
    public static void register() {
        Party.install("standards", StandardsGroups::members);
        Chronicler.LOGGER.info("Chronicler: party membership via Standards groups (kinds from party.groupKinds)");
    }

    private static List<ServerPlayer> members(ServerPlayer player) {
        for (String kindId : ChroniclerConfig.PARTY_GROUP_KINDS.get()) {
            GroupKind kind = Groups.kind(kindId).orElse(null);
            if (kind == null) continue;
            Group group = Groups.primary(player, kind).orElse(null);
            if (group == null) return List.of(player);
            List<ServerPlayer> out = new ArrayList<>();
            var list = player.level().getServer().getPlayerList();
            for (UUID id : group.members()) {
                ServerPlayer p = list.getPlayer(id);
                if (p != null) out.add(p);
            }
            if (!out.contains(player)) out.add(player);
            return out;
        }
        return List.of(player);
    }

    private StandardsGroups() {}
}
