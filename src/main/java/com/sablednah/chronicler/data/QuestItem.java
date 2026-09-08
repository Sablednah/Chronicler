package com.sablednah.chronicler.data;

import java.util.List;
import java.util.Optional;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.sablednah.chronicler.ChroniclerRegistries;
import com.sablednah.chronicler.neoforge.Feedback;
import com.sablednah.chronicler.neoforge.Lang;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * A quest item: a vanilla item wearing a name, a lore and an invisible mark
 * ({@code minecraft:custom_data} with {@code chronicler:item}), so a vanilla
 * client draws it and nothing but the mark says what it is. Renaming it in an
 * anvil does not change what it is; the mark does. A datapack registry
 * ({@code chronicler:item}), so packs ship their own.
 *
 * <p>No {@code ItemStack} is ever held in a codec: the id is, and the stack is
 * built at use time -- on 26.x a stack cannot exist while registries load.</p>
 */
public record QuestItem(Identifier item, String name, List<String> lore, boolean glint, int maxStack, boolean usable) {

    /** {@code usable: false} (the default) strips eating, drinking and use-remainders: insulin is a thing you carry, not a thing you drink. */

    public static final String MARK = "chronicler:item";

    public static final Codec<QuestItem> CODEC = RecordCodecBuilder.create(i -> i.group(
            Identifier.CODEC.fieldOf("item").forGetter(QuestItem::item),
            Codec.STRING.fieldOf("name").forGetter(QuestItem::name),
            Codec.STRING.listOf().optionalFieldOf("lore", List.of()).forGetter(QuestItem::lore),
            Codec.BOOL.optionalFieldOf("glint", true).forGetter(QuestItem::glint),
            Codec.INT.optionalFieldOf("max_stack", 0).forGetter(QuestItem::maxStack),
            Codec.BOOL.optionalFieldOf("usable", false).forGetter(QuestItem::usable))
            .apply(i, QuestItem::new));

    public static Optional<QuestItem> get(HolderLookup.Provider registries, Identifier id) {
        return registries.lookup(ChroniclerRegistries.ITEM)
                .flatMap(r -> r.get(ResourceKey.create(ChroniclerRegistries.ITEM, id)))
                .map(h -> h.value());
    }

    /** The name as text, for objectives and rewards; the id, prettied, when the pack is not loaded. */
    public static String displayName(Identifier id) {
        return CURRENT == null ? Lang.pretty(id.getPath())
                : get(CURRENT, id).map(q -> Feedback.colored(q.name()).getString()).orElseGet(() -> Lang.pretty(id.getPath()));
    }

    /** The registries of the running server, for text that has no player in hand. Set on server start. */
    public static volatile HolderLookup.Provider CURRENT = null;

    /** Build {@code count} of this item, marked. Empty if the base item is unknown. */
    public static ItemStack build(HolderLookup.Provider registries, Identifier id, int count) {
        Optional<QuestItem> q = get(registries, id);
        if (q.isEmpty()) return ItemStack.EMPTY;
        var holder = BuiltInRegistries.ITEM.get(q.get().item());
        if (holder.isEmpty()) return ItemStack.EMPTY;
        ItemStack stack = new ItemStack(holder.get(), count);
        stack.set(DataComponents.CUSTOM_NAME, Feedback.colored("&r" + q.get().name()).copy().withStyle(s -> s.withItalic(false)));
        if (!q.get().lore().isEmpty()) {
            stack.set(DataComponents.LORE, new ItemLore(q.get().lore().stream()
                    .map(l -> (Component) Feedback.colored("&7" + l).copy().withStyle(s -> s.withItalic(false))).toList()));
        }
        if (q.get().glint()) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true);
        if (q.get().maxStack() > 0) stack.set(DataComponents.MAX_STACK_SIZE, Math.min(99, q.get().maxStack()));
        if (!q.get().usable()) {
            stack.remove(DataComponents.CONSUMABLE);
            stack.remove(DataComponents.FOOD);
            stack.remove(DataComponents.USE_REMAINDER);
        }
        CompoundTag tag = new CompoundTag();
        tag.putString(MARK, id.toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    /** Which quest item this stack is, by its mark alone. */
    public static Optional<Identifier> markOf(ItemStack stack) {
        if (stack.isEmpty()) return Optional.empty();
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return Optional.empty();
        return data.copyTag().getString(MARK).map(Identifier::tryParse);
    }

    public static boolean is(ItemStack stack, Identifier id) {
        return markOf(stack).map(id::equals).orElse(false);
    }

    public static Codec<QuestItem> codec() { return CODEC; }

    static final ResourceKey<net.minecraft.core.Registry<net.minecraft.world.item.Item>> ITEMS = Registries.ITEM;
}
