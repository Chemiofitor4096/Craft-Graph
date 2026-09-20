package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.crafting.SizedFluidIngredient;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft 对象 → 我们自己的记录。**这一层就是「这个 MC 版本」的全部**。
 *
 * <h2>它的存在意义</h2>
 *
 * 适配器的判断逻辑（哪一类配方、概率怎么分类、中间产物怎么排除）是版本无关的，
 * 但「怎么从游戏对象里取出 id」是版本相关的 —— 而且差异比想象中大：
 *
 * <ul>
 *   <li>流体的原料类型：1.21.1 是 NeoForge 的 {@code SizedFluidIngredient}，
 *       而 Forge 1.20.1 根本没有这个类（Create 用的是它自己的 {@code FluidIngredient}）。</li>
 *   <li>配方的身份：1.21.1 从 {@code RecipeHolder} 拿 id，1.20.1 没有那个类。</li>
 *   <li>注册表访问：{@code HolderLookup.Provider} 与 {@code RegistryAccess} 是两套。</li>
 * </ul>
 *
 * 所以每个 MC 版本各有一份这个文件，而 {@link RecipeTypeAdapter} 的实现只在
 * 「读哪个访问器」上不同。1.20.1 那份要照着改的就是这里。
 *
 * <p>所有方法都**不编造**：取不到 id 时返回 null 或空列表，由调用方决定怎么诚实处理。
 * 编一个 {@code "unknown:unknown"} 出来会让下游以为读到了东西。
 */
final class ItemIds {

    private ItemIds() {
    }

    // ---------------------------------------------------------------- 物品

    /**
     * 一个物品槽位接受的全部物品 id。
     *
     * <p>注意 {@code Ingredient#getItems()} 给的是**展开后的物品**，它不会告诉你
     * 这个槽位原本是不是标签 —— 还原标签是后面 {@code ExtractionContext} 的活。
     */
    static List<String> items(Ingredient ingredient) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            String id = itemId(stack);
            if (id != null) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** 物品槽位。空槽位由调用方滤掉（{@link RawSlot} 不接受空）。 */
    static RawSlot itemSlot(Ingredient ingredient) {
        return RawSlot.items(items(ingredient));
    }

    /** @return 取不到注册表 id 时为 null（不该发生，但不能编一个名字顶上） */
    static String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? null : id.toString();
    }

    static String itemId(Item item) {
        if (item == null) return null;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? null : id.toString();
    }

    /** 一个具体产出。物品类产出没有「读不到」的余地 —— 取不到 id 时调用方应当跳过它。 */
    static Models.ItemStack stack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String id = itemId(stack);
        return id == null ? null : new Models.ItemStack(id, stack.getCount(), null);
    }

    // ---------------------------------------------------------------- 流体

    /**
     * 一个流体槽位接受的全部流体 id，以及它要求的量（mB）。
     *
     * <p>1.20.1 上这个方法要改成读 Create 自己的 {@code FluidIngredient} ——
     * 那是这次移植里唯一一处**接口形状**的变化，不只是改类名。
     */
    static RawSlot fluidSlot(SizedFluidIngredient ingredient) {
        Set<String> ids = new LinkedHashSet<>();
        for (FluidStack stack : ingredient.ingredient().getStacks()) {
            String id = fluidId(stack);
            if (id != null) ids.add(id);
        }
        return RawSlot.fluids(ingredient.amount(), List.copyOf(ids));
    }

    static String fluidId(FluidStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        ResourceLocation id = BuiltInRegistries.FLUID.getKey(stack.getFluid());
        return id == null ? null : id.toString();
    }

    static Models.FluidStack fluid(FluidStack stack) {
        String id = fluidId(stack);
        return id == null ? null : new Models.FluidStack(id, stack.getAmount());
    }

    /** 批量转换并丢掉取不到 id 的那些（返回 null 的元素不能留在列表里）。 */
    static List<Models.FluidStack> fluids(List<FluidStack> stacks) {
        List<Models.FluidStack> out = new ArrayList<>(stacks.size());
        for (FluidStack stack : stacks) {
            Models.FluidStack fluid = fluid(stack);
            if (fluid != null) out.add(fluid);
        }
        return out;
    }
}
