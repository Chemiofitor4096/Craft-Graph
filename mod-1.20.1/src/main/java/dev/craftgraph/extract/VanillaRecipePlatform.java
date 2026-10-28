package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.normalize.TagIndex;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
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
 * 1.20.1 的 {@link RecipePlatform} 实现 —— **这个变体里唯一碰 MC 配方对象的地方**。
 *
 * <p>判断逻辑（归一化、可读性、覆盖度、机器推断）全在 core 的
 * {@link RecipeExtractor} 里，这里只回答四个问题：类型 id、通用输入、通用产出、toast symbol。
 */
final class VanillaRecipePlatform implements RecipePlatform<Recipe<?>> {

    private final RegistryAccess registries;

    VanillaRecipePlatform(RegistryAccess registries) {
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
    static ExtractionContext context(RegistryAccess registries) {
        return new ExtractionContext(
                buildTagIndex(registries, Registries.ITEM),
                buildTagIndex(registries, Registries.FLUID));
    }

    private static <T> TagIndex buildTagIndex(
            RegistryAccess registries,
            ResourceKey<? extends Registry<T>> registryKey) {

        Registry<T> registry = registries.registryOrThrow(registryKey);

        Map<String, List<String>> tags = new LinkedHashMap<>();
        registry.getTagNames().forEach(tagKey -> {
            List<String> members = new ArrayList<>();
            registry.getTag(tagKey).ifPresent(namedSet -> {
                for (Holder<T> holder : namedSet) {
                    holder.unwrapKey().ifPresent(k -> members.add(k.location().toString()));
                }
            });
            if (!members.isEmpty()) {
                tags.put(tagKey.location().toString(), members);
            }
        });
        return TagIndex.of(tags);
    }
}
