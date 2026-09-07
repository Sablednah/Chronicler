package com.sablednah.chronicler.neoforge;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.Chronicler;
import com.sablednah.chronicler.data.ChroniclerIds;
import com.sablednah.chronicler.data.QuestItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * A loot function, {@code {"function": "chronicler:quest_item", "id": "zarp:ember_heart"}},
 * so a loot table -- a ZombieMod genus's, a chest's -- drops a marked quest item
 * built from its registry entry, rather than every table restating the name and
 * lore by hand. The stack's item comes from the entry too; the table's own item is
 * only a placeholder.
 */
public final class QuestItemLoot {

    public record Function(Identifier id, int count) implements LootItemFunction {
        public static final MapCodec<Function> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                ChroniclerIds.CODEC.fieldOf("id").forGetter(Function::id),
                com.mojang.serialization.Codec.INT.optionalFieldOf("count", 1).forGetter(Function::count))
                .apply(i, Function::new));

        @Override
        public LootItemFunctionType<? extends LootItemFunction> getType() { return TYPE.get(); }

        @Override
        public ItemStack apply(ItemStack stack, LootContext context) {
            ItemStack built = QuestItem.build(context.getLevel().registryAccess(), id, Math.max(1, count * Math.max(1, stack.getCount())));
            if (built.isEmpty()) {
                Chronicler.LOGGER.warn("Chronicler: loot function names unknown quest item {}", id);
                return stack;
            }
            return built;
        }
    }

    private static final DeferredRegister<LootItemFunctionType<?>> TYPES =
            DeferredRegister.create(Registries.LOOT_FUNCTION_TYPE, Chronicler.MODID);
    public static final java.util.function.Supplier<LootItemFunctionType<Function>> TYPE =
            TYPES.register("quest_item", () -> new LootItemFunctionType<>(Function.CODEC));

    public static void register(IEventBus modBus) { TYPES.register(modBus); }

    private QuestItemLoot() {}
}
