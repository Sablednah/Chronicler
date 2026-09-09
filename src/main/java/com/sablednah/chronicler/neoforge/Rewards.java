package com.sablednah.chronicler.neoforge;

import java.util.LinkedHashMap;
import java.util.Map;

import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.Quest;
import com.sablednah.chronicler.data.RewardSpec;
import com.sablednah.chronicler.data.RewardTypes;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;

/**
 * How each reward type is <em>granted</em>. Same shape as {@link Trackers}:
 * data records say what, granters do it, and the registry is public so an
 * external reward type can register its own.
 *
 * <p>Every granter tells the player what landed, at the moment it lands. A
 * reward nobody was told about is indistinguishable from one that failed.</p>
 */
public final class Rewards {

    public interface Granter<R extends RewardSpec> {
        void grant(ServerPlayer player, R reward, Identifier questId, Quest quest);
    }

    private static final Map<Class<? extends RewardSpec>, Granter<?>> BY_CLASS = new LinkedHashMap<>();
    // Declared before the static block that fills BY_CLASS: a static after the block that uses it is a forward reference.
    private static int lastSpawned = 0;

    public static synchronized <R extends RewardSpec> void register(Class<R> type, Granter<R> granter) {
        BY_CLASS.put(type, granter);
    }

    @SuppressWarnings("unchecked")
    public static <R extends RewardSpec> void grant(ServerPlayer player, R reward, Identifier questId, Quest quest) {
        Granter<R> g;
        synchronized (Rewards.class) { g = (Granter<R>) BY_CLASS.get(reward.getClass()); }
        if (g == null) {
            Chronicler.LOGGER.warn("Chronicler: no granter for reward type {} on {} -- skipped",
                    reward.getClass().getSimpleName(), questId);
            return;
        }
        try {
            g.grant(player, reward, questId, quest);
        } catch (RuntimeException e) {
            // One bad reward must not cost the rest of the packet.
            Chronicler.LOGGER.error("Chronicler: reward {} on {} threw", reward.describe(), questId, e);
        }
    }

    static {
        register(RewardTypes.Item.class, (player, r, questId, quest) -> {
            if (r.questItem().isPresent()) {
                ItemStack stack = com.sablednah.chronicler.data.QuestItem.build(player.level().registryAccess(), r.questItem().get(), r.count());
                if (stack.isEmpty()) {
                    Chronicler.LOGGER.warn("Chronicler: quest {} rewards unknown quest item {}", questId, r.questItem().get());
                    Feedback.chat(player, Lang.fmt("msg.reward.unknown_item", "item", r.questItem().get()));
                    return;
                }
                player.getInventory().add(stack);
                if (!stack.isEmpty()) player.drop(stack, false);
                Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
                return;
            }
            var holder = BuiltInRegistries.ITEM.get(r.item());
            if (holder.isEmpty()) {
                Chronicler.LOGGER.warn("Chronicler: quest {} rewards unknown item {}", questId, r.item());
                Feedback.chat(player, Lang.fmt("msg.reward.unknown_item", "item", r.item()));
                return;
            }
            int left = r.count();
            while (left > 0) {
                int n = Math.min(left, holder.get().value().getDefaultMaxStackSize());
                ItemStack stack = new ItemStack(holder.get(), n);
                player.getInventory().add(stack);
                if (!stack.isEmpty()) player.drop(stack, false); // a full pack drops it at the feet
                left -= n;
            }
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
        });

        register(RewardTypes.Xp.class, (player, r, questId, quest) -> {
            player.giveExperiencePoints(r.amount());
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
        });

        register(RewardTypes.Money.class, (player, r, questId, quest) -> {
            var paid = Money.pay(player, r.amount(), "chronicler:" + questId);
            if (paid.isPresent()) {
                Feedback.chat(player, Lang.fmt("msg.reward.given", "line",
                        Lang.fmt("rew.money_paid", "amount", paid.get())));
            } else {
                Feedback.chat(player, Lang.fmt("msg.reward.money_none", "amount", r.amount()));
            }
        });

        register(RewardTypes.Reputation.class, (player, r, questId, quest) -> {
            int before = Rep.get(player, r.standing());
            var landed = Rep.adjust(player, r.standing(), r.delta(), "chronicler:" + questId);
            if (landed.isEmpty()) {
                Feedback.chat(player, Lang.fmt("msg.reward.reputation_none", "line", r.describe()));
                return;
            }
            // The landed value, not the number in the file: "+10" when you were
            // already at the ceiling is a lie the player can check with /rep.
            int moved = landed.getAsInt() - before;
            String line = Lang.fmt(moved >= 0 ? "rew.reputation_up" : "rew.reputation_down",
                    "amount", Math.abs(moved), "standing", Lang.pretty(r.standing()));
            String band = Rep.band(r.standing(), landed.getAsInt())
                    .map(b -> Lang.fmt("msg.reward.reputation_band", "standing", Lang.pretty(r.standing()), "band", b))
                    .orElse("");
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", line + band));
        });

        register(RewardTypes.Karma.class, (player, r, questId, quest) -> character(player, r,
                Sheet.addKarma(player, r.delta())));
        register(RewardTypes.ClassXp.class, (player, r, questId, quest) -> character(player, r,
                Sheet.addClassXp(player, r.amount())));
        register(RewardTypes.Levels.class, (player, r, questId, quest) -> character(player, r,
                Sheet.addLevels(player, r.count())));
        register(RewardTypes.SkillPoints.class, (player, r, questId, quest) -> character(player, r,
                Sheet.grantSkillPoints(player, r.count())));

        register(RewardTypes.NpcSay.class, (player, r, questId, quest) -> {
            var npc = npcFor(player, r.quest().orElse(questId));
            if (npc.isEmpty()) { Feedback.chat(player, r.text()); return; }
            Npcs.provider().get().say(player.level().getServer(), npc.get(), r.text(), r.radius());
        });
        register(RewardTypes.NpcRemove.class, (player, r, questId, quest) -> {
            var npc = npcFor(player, r.quest().orElse(questId));
            r.text().ifPresent(t -> Feedback.chat(player, t));
            if (npc.isEmpty()) return;
            var server = player.level().getServer();
            Npcs.provider().get().remove(server, npc.get());
            GiverStore.get(server).removeNpc(npc.get());
            Chronicler.LOGGER.info("Chronicler: quest {} removed the NPC of {} ({})", questId, r.quest().orElse(questId), npc.get());
        });
        register(RewardTypes.Ending.class, (player, r, questId, quest) -> QuestEngine.reachEnding(player, quest, r.ending()));
        register(RewardTypes.Flag.class, (player, r, questId, quest) -> {
            if (r.player()) {
                QuestEngine.journal(player).setFlag(r.name(), r.value(), quest.chapter());
            } else {
                FlagStore.get(player.level().getServer()).set(r.name(), r.value());
                Chronicler.LOGGER.info("Chronicler: world flag {} = {} (quest {} by {})",
                        FlagStore.normalise(r.name()), r.value(), questId, player.getName().getString());
            }
            // Flags are plumbing, not payment: say nothing unless it was the world's.
            if (!r.player()) Feedback.chat(player, Lang.fmt("msg.reward.given", "line",
                    Lang.fmt(r.value() ? "rew.flag_world_set" : "rew.flag_world_clear", "flag", Lang.pretty(r.name()))));
        });

        register(RewardTypes.Command.class, (player, r, questId, quest) -> {
            String expanded = r.command()
                    .replace("{player}", player.getName().getString())
                    .replace("{uuid}", player.getUUID().toString())
                    .replace("{quest}", questId.toString())
                    .replace("{x}", Integer.toString(player.blockPosition().getX()))
                    .replace("{y}", Integer.toString(player.blockPosition().getY()))
                    .replace("{z}", Integer.toString(player.blockPosition().getZ()));
            // Run AS the player with gamemaster permission, the way a LegendQuest
            // skill runs its commands: the quest is the authority, not the player's
            // own rank, so a reward may grant what the player could not ask for.
            var source = player.createCommandSourceStack()
                    .withPermission(LevelBasedPermissionSet.GAMEMASTER);
            if (r.silent()) source = source.withSuppressedOutput();
            player.level().getServer().getCommands().performPrefixedCommand(source, expanded);
        });
    }

    static {
        register(RewardTypes.Title.class, (player, r, questId, quest) ->
                Feedback.title(player, r.title(), r.subtitle().orElse("")));

        register(RewardTypes.Message.class, (player, r, questId, quest) -> {
            if (r.actionBar()) Feedback.actionBar(player, r.text()); else Feedback.chat(player, r.text());
        });

        register(RewardTypes.Spawn.class, (player, r, questId, quest) -> {
            var level = player.level();
            var rng = level.getRandom();
            lastSpawned = 0;
            for (int n = 0; n < r.count(); n++) {
                double angle = rng.nextDouble() * Math.PI * 2;
                double dist = 2 + rng.nextDouble() * Math.max(0, r.radius() - 2);
                int x = (int) Math.round(player.getX() + Math.cos(angle) * dist);
                int z = (int) Math.round(player.getZ() + Math.sin(angle) * dist);
                var at = level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                        new net.minecraft.core.BlockPos(x, 0, z));
                if (r.genus().isPresent() && Genera.available()) {
                    Identifier genusId = Identifier.tryParse(r.genus().get());
                    var mob = genusId == null ? java.util.Optional.<net.minecraft.world.entity.Mob>empty()
                            : Genera.spawn(level, genusId, new net.minecraft.world.phys.Vec3(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D));
                    if (mob.isPresent()) {
                        mob.get().setPersistenceRequired();
                        if (r.health() > 0) {
                            var attr = mob.get().getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                            if (attr != null) { attr.setBaseValue(r.health()); mob.get().setHealth((float) r.health()); }
                        }
                        decorate(mob.get(), r); // after ZombieMod: the genus dressed it, we fill the gaps
                        lastSpawned++;
                        continue;
                    }
                    // an unknown genus, or a base that is not a mob: fall through to the stand-in
                }
                if (r.genus().isPresent() && r.entity().isEmpty()) {
                    Chronicler.LOGGER.warn("Chronicler: quest {} spawns genus {} but {} and no entity stands in", questId, r.genus().get(),
                            Genera.available() ? "it did not spawn" : "ZombieMod is not installed");
                    return;
                }
                var type = r.entity().flatMap(id -> BuiltInRegistries.ENTITY_TYPE.get(id));
                if (type.isEmpty()) {
                    Chronicler.LOGGER.warn("Chronicler: quest {} spawns unknown entity {}", questId, r.entity().orElse(null));
                    return;
                }
                var spawned = type.get().value().create(level, net.minecraft.world.entity.EntitySpawnReason.EVENT);
                if (spawned == null) return;
                spawned.snapTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, rng.nextFloat() * 360F, 0F);
                if (spawned instanceof net.minecraft.world.entity.Mob mob) {
                    mob.finalizeSpawn(level, level.getCurrentDifficultyAt(at),
                            net.minecraft.world.entity.EntitySpawnReason.EVENT, null);
                    mob.setPersistenceRequired();
                    if (r.health() > 0) {
                        var attr = mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
                        if (attr != null) { attr.setBaseValue(r.health()); mob.setHealth((float) r.health()); }
                    }
                }
                decorate(spawned, r);
                if (level.addFreshEntity(spawned)) lastSpawned++;
            }
        });
    }

    private static void character(ServerPlayer player, RewardSpec r, boolean landed) {
        if (landed) {
            Feedback.chat(player, Lang.fmt("msg.reward.given", "line", r.describe()));
        } else if (!Sheet.available()) {
            Feedback.chat(player, Lang.fmt("msg.reward.no_character", "line", r.describe()));
        } else {
            Feedback.chat(player, Lang.fmt("msg.reward.no_class", "line", r.describe()));
        }
    }

    /** How many entities the most recent spawn effect placed; the self-test's window onto a lookup that lags a tick. */
    public static int lastSpawned() { return lastSpawned; }

    private static void decorate(net.minecraft.world.entity.Entity entity, RewardTypes.Spawn r) {
        r.name().ifPresent(n -> { entity.setCustomName(Feedback.colored(n)); entity.setCustomNameVisible(true); });
        r.tag().ifPresent(t -> entity.addTag(Trackers.TAG_PREFIX + t));
        if (!r.equipment().isEmpty() && entity instanceof net.minecraft.world.entity.LivingEntity living) {
            r.equipment().forEach((slotName, item) -> {
                net.minecraft.world.entity.EquipmentSlot slot;
                try { slot = net.minecraft.world.entity.EquipmentSlot.byName(slotName.trim().toLowerCase(java.util.Locale.ROOT)); }
                catch (IllegalArgumentException e) { Chronicler.LOGGER.warn("Chronicler: spawn names unknown slot '{}'", slotName); return; }
                if (!r.override() && !living.getItemBySlot(slot).isEmpty()) return; // the genus got there first, and that is the point
                try {
                    // 26.x: parse() returns an ItemInput record (holder + component patch), not ItemResult.
                    net.minecraft.commands.arguments.item.ItemInput parsed = new net.minecraft.commands.arguments.item.ItemParser(entity.level().registryAccess())
                            .parse(new com.mojang.brigadier.StringReader(item.trim()));
                    ItemStack stack = new ItemStack(parsed.item(), 1);
                    stack.applyComponents(parsed.components());
                    living.setItemSlot(slot, stack);
                    if (living instanceof net.minecraft.world.entity.Mob mob) mob.setDropChance(slot, 0F);
                } catch (Exception e) {
                    Chronicler.LOGGER.warn("Chronicler: spawn cannot equip '{}': {}", item, e.getMessage());
                }
            });
        }
    }

    /** The placed NPC that gives a quest, if Cast placed one. */
    private static java.util.Optional<java.util.UUID> npcFor(ServerPlayer player, Identifier questId) {
        if (!Npcs.available()) return java.util.Optional.empty();
        var server = player.level().getServer();
        return GiverStore.get(server).placedFor(questId).filter(id -> Npcs.provider().get().byId(server, id).isPresent());
    }

    public static void init() {}

    private Rewards() {}
}
