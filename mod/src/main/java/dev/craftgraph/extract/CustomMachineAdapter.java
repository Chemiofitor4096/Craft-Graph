package dev.craftgraph.extract;

import dev.craftgraph.api.Models;
import dev.craftgraph.extract.custommachine.CustomMachineRecipeJson;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Custom Machinery 的配方适配器（**1.21.1 变体**）。
 *
 * <h2>它做什么</h2>
 *
 * CM 的配方对象自己不知道「输入是什么」—— 输入输出全在一串 requirement 里。
 * 这个适配器把整条配方交给 {@link CustomMachineApi} 编码成 JSON，再由 core 的
 * {@link CustomMachineRecipeJson} 解析成 {@link RawSlot} / {@link Models}。
 * 判断逻辑全在 core（可单测），这里只做「取对象、搬数据」。
 *
 * <h2>为什么 complete() 很重要</h2>
 *
 * CM 的 requirement 有三十种。物品、流体、耐久、结构这些我们读得懂；战利品表、
 * 掉落、物品转换会产出东西，但产出不是声明式的。这时返回 {@code complete=false}，
 * 让 extractor 把整条配方标成 opaque —— 一份「读了 80% 但不说」的原料表，
 * 比「读不懂」危险得多。
 *
 * <h2>为什么缓存</h2>
 *
 * 抽取一条配方会依次调用 ingredients / results / chanceResults / fluidResults /
 * fluidIngredients / complete，每次都要同一份 JSON。解析一次存起来，按配方实例
 * 弱引用缓存；抽取只在主线程，所以不需要同步。
 */
final class CustomMachineAdapter implements RecipeTypeAdapter<Recipe<?>> {

    private final CustomMachineApi api;

    /** 配方实例 → 解析结果。WeakHashMap：配方重载后旧实例可以被回收。 */
    private final Map<Recipe<?>, CustomMachineRecipeJson.Parsed> cache = new WeakHashMap<>();

    CustomMachineAdapter() {
        this.api = CustomMachineApi.load();
    }

    @Override
    public boolean handles(Recipe<?> recipe) {
        return api != null && api.handles(recipe);
    }

    @Override
    public String machine(Recipe<?> recipe) {
        if (api == null) return null;
        try {
            return api.machineId(recipe);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public Integer duration(Recipe<?> recipe) {
        if (api == null) return null;
        try {
            return api.recipeTime(recipe);
        } catch (Throwable t) {
            return null;
        }
    }

    @Override
    public List<RawSlot> ingredients(Recipe<?> recipe) {
        return parsed(recipe).itemInputs();
    }

    @Override
    public List<RawSlot> fluidIngredients(Recipe<?> recipe) {
        return parsed(recipe).fluidInputs();
    }

    @Override
    public List<Models.ItemStack> results(Recipe<?> recipe) {
        return parsed(recipe).results();
    }

    @Override
    public List<Models.ChanceOutput> chanceResults(Recipe<?> recipe) {
        return parsed(recipe).chanceResults();
    }

    @Override
    public List<Models.FluidStack> fluidResults(Recipe<?> recipe) {
        return parsed(recipe).fluidResults();
    }

    /**
     * 只有「一条 requirement 都没漏」时才能断言「它不产出」。
     * 读不懂的类型会让 complete 为 false，这里返回 false，上层标 opaque。
     */
    @Override
    public boolean declaresNoOutput(Recipe<?> recipe) {
        CustomMachineRecipeJson.Parsed parsed = parsed(recipe);
        return parsed.complete() && !parsed.hasOutputs();
    }

    @Override
    public boolean complete(Recipe<?> recipe) {
        return parsed(recipe).complete();
    }

    // ---------------------------------------------------------------- 内部

    private CustomMachineRecipeJson.Parsed parsed(Recipe<?> recipe) {
        CustomMachineRecipeJson.Parsed cached = cache.get(recipe);
        if (cached != null) return cached;

        CustomMachineRecipeJson.Parsed parsed;
        try {
            parsed = CustomMachineRecipeJson.parse(api.encode(recipe), this::tagMembers);
        } catch (Throwable t) {
            // 编码失败 / JSON 结构异常：这条配方当作「什么都没读到」，
            // 上层会标 opaque —— 不能假装它没有原料。
            parsed = CustomMachineRecipeJson.Parsed.unreadable();
        }
        cache.put(recipe, parsed);
        return parsed;
    }

    /** 把 CM 的 {@code #tag} 展开成成员 id；还原成协议里的标签由 TagIndex 反查完成。 */
    private List<String> tagMembers(String kind, String tagId) {
        ResourceLocation id = ResourceLocation.tryParse(tagId);
        if (id == null) return List.of();

        if (Models.Ingredient.KIND_FLUID.equals(kind)) {
            List<String> out = new ArrayList<>();
            for (Holder<Fluid> holder : BuiltInRegistries.FLUID.getTagOrEmpty(TagKey.create(Registries.FLUID, id))) {
                ResourceLocation key = BuiltInRegistries.FLUID.getKey(holder.value());
                if (key != null) out.add(key.toString());
            }
            return out;
        }

        List<String> out = new ArrayList<>();
        for (Holder<Item> holder : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, id))) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(holder.value());
            if (key != null) out.add(key.toString());
        }
        return out;
    }
}
