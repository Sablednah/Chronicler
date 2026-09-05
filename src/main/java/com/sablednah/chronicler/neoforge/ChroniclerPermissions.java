package com.sablednah.chronicler.neoforge;

import com.sablednah.chronicler.Chronicler;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.server.permission.PermissionAPI;
import net.neoforged.neoforge.server.permission.events.PermissionGatherEvent;
import net.neoforged.neoforge.server.permission.nodes.PermissionNode;
import net.neoforged.neoforge.server.permission.nodes.PermissionTypes;

/**
 * Permission nodes, through NeoForge's {@code PermissionAPI} -- which is
 * already the Vault here: LuckPerms and SableCraft Standards are both
 * handlers for it, so nothing in {@code compat/} is needed for permissions.
 *
 * <p>Boolean nodes only (Standards passes typed nodes through untouched), and
 * every default resolver reproduces {@code NODES.md}, so a server that
 * installs a manager and grants nothing behaves exactly as before.</p>
 */
public final class ChroniclerPermissions {

    /** The {@code /chronicler} admin tree. Op level 2 also passes. */
    public static final PermissionNode<Boolean> ADMIN = new PermissionNode<>(
            Chronicler.MODID, "admin", PermissionTypes.BOOLEAN,
            (player, uuid, context) -> false);

    @SubscribeEvent
    static void onGather(PermissionGatherEvent.Nodes event) {
        event.addNodes(ADMIN);
    }

    /** Admin check for a command source: op level 2, or the node when a player. */
    public static boolean isAdmin(CommandSourceStack source) {
        if (Commands.hasPermission(Commands.LEVEL_GAMEMASTERS).test(source)) return true;
        return source.getEntity() instanceof ServerPlayer player
                && PermissionAPI.getPermission(player, ADMIN);
    }

    private ChroniclerPermissions() {}
}
