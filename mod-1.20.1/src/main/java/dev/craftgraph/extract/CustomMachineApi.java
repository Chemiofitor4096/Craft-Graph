package dev.craftgraph.extract;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * Custom Machinery 的 API 桥（**1.20.1 变体专属**）。
 *
 * <h2>为什么是反射，而不是 compileOnly</h2>
 *
 * 项目里其他模组适配器都走 {@code modCompileOnly}，让编译器逐条核对调用。CM 这里
 * 走不通：它没有发布到任何可用的 Maven（作者自己的 {@code maven.frinn.fr} 上找不到
 * 对应坐标，CurseForge 也没有公开的 maven 坐标），而把 1.7 MB 的模组 jar 提交进仓库
 * 是另一种债。两害相权，选一个**面极小**的反射桥：
 *
 * <ul>
 *   <li>只碰 3 个方法（{@code getMachineId} / {@code getRecipeTime} / {@code getRequirements}）</li>
 *   <li>只碰 1 个静态字段（{@code IRequirement.CODEC}）和它的 {@code encodeStart}</li>
 *   <li>其余全部是普通 Java —— requirement 的内容由 codec 编码成 JSON 后，
 *       交给 core 的 {@code ProfileRecipeParser}（按 {@code CustomMachineProfile}）解析，不认识任何 CM 类</li>
 * </ul>
 *
 * <p>任何一步失败都只让这个适配器「不生效」（配方退回 opaque），不会带崩快照构建。
 * {@code npm run live} 里有一条针对组装机配方的断言，专门盯「CM 改了 API，反射悄悄失效」。
 *
 * <h2>线程</h2>
 *
 * 只在主线程的抽取过程中使用（与方法调用点一致），所以缓存不需要同步。
 */
final class CustomMachineApi {

    private static final Logger LOGGER = LoggerFactory.getLogger("craftgraph-custommachinery");

    private static final String RECIPE_CLASS =
            "fr.frinn.custommachinery.common.crafting.machine.CustomMachineRecipe";
    private static final String REQUIREMENT_CLASS =
            "fr.frinn.custommachinery.api.requirement.IRequirement";
    /**
     * CM 0.10.7x（1.21.x）把 requirement 包了一层：{@code getRequirements()} 返回的是
     * {@code RecipeRequirement}（内含 requirement + chance + delay），概率也从 requirement
     * 自己的字段搬到了这一层。1.20.1 的 0.10.9.6 没有这个类，所以它是**可选**的。
     */
    private static final String RECIPE_REQUIREMENT_CLASS =
            "fr.frinn.custommachinery.api.requirement.RecipeRequirement";

    private final Class<?> recipeClass;
    private final Method machineId;
    private final Method recipeTime;
    private final Method requirements;
    private final Object requirementCodec;
    private final Method encodeStart;
    /** 1.21.x 才有；1.20.1 上为 null。 */
    private final Class<?> recipeRequirementClass;
    private final Method requirementGetter;
    private final Method chanceGetter;

    private CustomMachineApi(Class<?> recipeClass, Method machineId, Method recipeTime, Method requirements,
                             Object requirementCodec, Method encodeStart,
                             Class<?> recipeRequirementClass, Method requirementGetter, Method chanceGetter) {
        this.recipeClass = recipeClass;
        this.machineId = machineId;
        this.recipeTime = recipeTime;
        this.requirements = requirements;
        this.requirementCodec = requirementCodec;
        this.encodeStart = encodeStart;
        this.recipeRequirementClass = recipeRequirementClass;
        this.requirementGetter = requirementGetter;
        this.chanceGetter = chanceGetter;
    }

    /**
     * 解析一次 CM 的 API。
     *
     * @return API 不可用（模组版本不符、类被改名）时返回 {@code null}；
     *         调用方应当据此让适配器完全不生效，而不是抛异常
     */
    static CustomMachineApi load() {
        try {
            Class<?> recipeClass = Class.forName(RECIPE_CLASS);
            Class<?> requirementClass = Class.forName(REQUIREMENT_CLASS);
            Method machineId = recipeClass.getMethod("getMachineId");
            Method recipeTime = recipeClass.getMethod("getRecipeTime");
            Method requirements = recipeClass.getMethod("getRequirements");
            Object codec = requirementClass.getField("CODEC").get(null);
            Method encodeStart = codec.getClass().getMethod("encodeStart", DynamicOps.class, Object.class);

            // 1.21.x 的包装层：不存在时（1.20.1）保持 null，encode 里自然跳过。
            Class<?> recipeRequirementClass = null;
            Method requirementGetter = null;
            Method chanceGetter = null;
            try {
                recipeRequirementClass = Class.forName(RECIPE_REQUIREMENT_CLASS);
                requirementGetter = recipeRequirementClass.getMethod("requirement");
                chanceGetter = recipeRequirementClass.getMethod("chance");
            } catch (ClassNotFoundException absent) {
                // 1.20.1 的 CM 没有这一层，正常路径
            }

            return new CustomMachineApi(recipeClass, machineId, recipeTime, requirements, codec, encodeStart,
                    recipeRequirementClass, requirementGetter, chanceGetter);
        } catch (Throwable t) {
            // 用 Throwable：类加载失败是 Error。后果限定为「这批配方仍然读不懂」。
            LOGGER.warn("CraftGraph: Custom Machinery is installed but its recipe API could not be resolved; "
                            + "its recipes stay unreadable (this is an adapter problem, not missing data): {}",
                    t.toString());
            return null;
        }
    }

    boolean handles(Recipe<?> recipe) {
        return recipeClass.isInstance(recipe);
    }

    /** 机器 id，例如 {@code dut:assembling_machine}；取不到时为 null。 */
    String machineId(Recipe<?> recipe) throws ReflectiveOperationException {
        Object value = machineId.invoke(recipe);
        return value instanceof ResourceLocation id ? id.toString() : null;
    }

    /** 耗时（游戏刻）。 */
    Integer recipeTime(Recipe<?> recipe) throws ReflectiveOperationException {
        Object value = recipeTime.invoke(recipe);
        return value instanceof Integer ticks ? ticks : null;
    }

    /**
     * 把整条配方编码成 JSON：{@code machine} / {@code time} / {@code requirements} / {@code unencoded}。
     *
     * <p>{@code requirements} 里每条都是 CM 自己的 codec 编出来的，形状与配方 JSON 一致；
     * 编码失败的那条只计数（{@code unencoded}），由 core 侧把整条配方标成不完整。
     */
    String encode(Recipe<?> recipe) throws ReflectiveOperationException {
        JsonObject root = new JsonObject();

        String machine = machineId(recipe);
        if (machine != null) root.addProperty("machine", machine);
        Integer time = recipeTime(recipe);
        if (time != null) root.addProperty("time", time);

        JsonArray array = new JsonArray();
        int unencoded = 0;
        for (Object element : (List<?>) requirements.invoke(recipe)) {
            Object requirement = element;
            Double chance = null;

            // 1.21.x：list 里是 RecipeRequirement 包装，内层才是真正的 requirement，
            // 概率也挂在包装上。拆出来后再编码，产出的 JSON 形状与 1.20.1 一致。
            if (recipeRequirementClass != null && recipeRequirementClass.isInstance(element)) {
                try {
                    requirement = requirementGetter.invoke(element);
                    chance = (Double) chanceGetter.invoke(element);
                } catch (Throwable t) {
                    unencoded++;
                    continue;
                }
            }

            Optional<JsonElement> encoded = encodeRequirement(requirement);
            if (encoded.isEmpty()) {
                unencoded++;
                continue;
            }
            JsonElement json = encoded.get();
            if (chance != null && chance < 1.0D && json.isJsonObject()) {
                // 内层 requirement 的 codec 不带 chance（它在包装层），这里补回成
                // 1.20.1 的扁平形状，core 的解析器就完全不用知道版本差异。
                json.getAsJsonObject().addProperty("chance", chance);
            }
            array.add(json);
        }
        root.add("requirements", array);
        root.addProperty("unencoded", unencoded);
        return root.toString();
    }

    @SuppressWarnings("unchecked")
    private Optional<JsonElement> encodeRequirement(Object requirement) {
        try {
            DataResult<JsonElement> result = (DataResult<JsonElement>)
                    encodeStart.invoke(requirementCodec, JsonOps.INSTANCE, requirement);
            return result.result();
        } catch (Throwable t) {
            // 单个 requirement 编不出来：计数即可，不要让它带崩整条链
            return Optional.empty();
        }
    }
}
