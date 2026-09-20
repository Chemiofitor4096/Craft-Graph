package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraftforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Minecraft 对象 → 我们自己的记录（**1.20.1 版**）。**这一层就是「这个 MC 版本」的全部**。
 *
 * <h2>它和 1.21.1 那份的差别</h2>
 *
 * 逻辑与结构完全一样，只有「对象的类型」不同：
 *
 * <ul>
 *   <li>流体栈是 {@code net.minecraftforge.fluids.FluidStack}（1.21.1 是 neo 那边的同名类）。</li>
 *   <li><b>没有通用的流体原料类型。</b>1.21.1 有 NeoForge 的 {@code SizedFluidIngredient}，
 *       Forge 1.20.1 没有 —— 流体原料只有各模组自己的形状（Create 那套在
 *       {@link CreateFluids} 里）。所以这里只提供「从一堆 id 造槽位」这一半，
 *       「怎么从一个模组的原料对象里取出 id」由那家适配器自己管。</li>
 *   <li>配方身份与注册表访问在别处（见 {@code RecipeExtractor} 与 {@code ClientBridge}）。</li>
 * </ul>
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
     * 这个槽位原本是不是标签 —— 还原标签是 {@code ExtractionContext} 的活。
     */
    static List<String> items(Ingredient ingredient) {
        Set<String> ids = new LinkedHashSet<>();
        for (ItemStack stack : ingredient.getItems()) {
            String id = itemId(stack);
            if (id != null) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /** 物品槽位。空槽位由调用方滤掉。 */
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

    /** 一个具体产出。物品类产出没有「读不到」的余地 —— 取不到 id 时返回 null，调用方跳过它。 */
    static Models.ItemStack stack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        String id = itemId(stack);
        return id == null ? null : new Models.ItemStack(id, stack.getCount(), null);
    }

    // ---------------------------------------------------------------- 流体

    /** 流体 id 列表（去重）。 */
    static List<String> fluidIds(Collection<FluidStack> stacks) {
        Set<String> ids = new LinkedHashSet<>();
        for (FluidStack stack : stacks) {
            String id = fluidId(stack);
            if (id != null) ids.add(id);
        }
        return List.copyOf(ids);
    }

    /**
     * 用「数量 + 一包 id」造流体槽位。
     *
     * <p>1.20.1 上没有统一的流体原料对象（见类注释），所以槽位由各适配器
     * 把它自己那套原料类型拆成这两样之后送进来。
     */
    static RawSlot fluidSlot(int amount, Collection<String> fluidIds) {
        return RawSlot.fluids(amount, List.copyOf(fluidIds));
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
