package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.TagIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1.21.1 的 {@link RecipePlatform} 实现 —— **这个变体里唯一碰 MC 配方对象的地方**。
 *
 * <p>与 1.20.1 那份的差别只有两处 API 形状：注册表访问是 {@link HolderLookup.Provider}
 * （1.20.1 是 {@code RegistryAccess}），标签枚举用 {@code lookupOrThrow + listTags}
 * （1.20.1 是 {@code registryOrThrow + getTagNames}）。判断逻辑一行都没有。
 */
final class VanillaRecipePlatform implements RecipePlatform<Recipe<?>> {

    private final HolderLookup.Provider registries;

    VanillaRecipePlatform(HolderLookup.Provider registries) {
        this.registries = registries;
    }

    @Override
    public String typeId(Recipe<?> recipe) {
        ResourceLocation key = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return key == null ? "unknown:unknown" : key.toString();
    }

    @Override
    public List<RawSlot> genericIngredients(Recipe<?> recipe) {
        List<RawSlot> out = new ArrayList<>();
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient.isEmpty()) continue;
            RawSlot slot = ItemIds.itemSlot(ingredient);
            if (!slot.isEmpty()) out.add(slot);
        }
        return out;
    }

    @Override
    public Models.ItemStack genericResult(Recipe<?> recipe) {
        // 必须传真实注册表：传 null 会让需要注册表的配方 NPE，而 NPE 曾被吞成「读不懂」。
        return ItemIds.stack(recipe.getResultItem(registries));
    }

    @Override
    public String toastItemId(Recipe<?> recipe) {
        return ItemIds.itemId(recipe.getToastSymbol());
    }

    /** 从注册表构建标签索引（抽取前调用一次，必须主线程）。 */
    static ExtractionContext context(HolderLookup.Provider registries) {
        return new ExtractionContext(
                buildTagIndex(registries, Registries.ITEM),
                buildTagIndex(registries, Registries.FLUID));
    }

    private static <T> TagIndex buildTagIndex(
            HolderLookup.Provider registries,
            ResourceKey<? extends Registry<T>> registryKey) {

        // lookupOrThrow 返回的是 RegistryLookup 而不是 Registry —— 前者只读，正好够用。
        HolderLookup.RegistryLookup<T> lookup = registries.lookupOrThrow(registryKey);

        Map<String, List<String>> tags = new LinkedHashMap<>();
        lookup.listTags().forEach(namedSet -> {
            List<String> members = new ArrayList<>();
            for (Holder<T> holder : namedSet) {
                holder.unwrapKey().ifPresent(k -> members.add(k.location().toString()));
            }
            if (!members.isEmpty()) {
                tags.put(namedSet.key().location().toString(), members);
            }
        });
        return TagIndex.of(tags);
    }
}
