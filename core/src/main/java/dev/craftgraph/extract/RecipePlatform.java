package dev.craftgraph.extract;

import dev.craftgraph.api.Models;

import java.util.List;

/**
 * 一个 Minecraft 版本的「读配方对象」能力（**这是两个变体之间唯一该重复的东西**）。
 *
 * <h2>它解决什么问题</h2>
 *
 * 1.20.1 和 1.21.1 的 {@link RecipeExtractor} 曾经是两份 ~340 行的近似拷贝：
 * 归一化、可读性判定、覆盖度、机器推断全都一样，只有「怎么从 MC 对象里取字段」不同。
 * 任何一处改动都要同步两份，忘了的那份要等另一个版本的玩家撞上才发现。
 *
 * <p>现在判断逻辑只剩 core 里的一份，变体只实现这四个方法。加一个 MC 版本 =
 * 写一个实现 + 一个配方枚举循环，不再复制整套抽取逻辑。
 *
 * <h2>为什么是泛型 {@code T}</h2>
 *
 * 1.21.1 的配方对象是 {@code RecipeHolder<?>}（id 和配方成对），1.20.1 是
 * {@code Recipe<?>} 加 map key。变体各自把 id 取出来再调用
 * {@link RecipeExtractor#extract(String, Object)}，这里只关心「这条配方对象」。
 *
 * <p>注意：**返回 null 的语义是「读不到」，不是「没有」** —— 见
 * {@link RecipeTypeAdapter} 与 {@code Readability}。
 *
 * @param <T> 这个版本的配方对象类型
 */
public interface RecipePlatform<T> {

    /** 配方类型 id，例如 {@code minecraft:smelting}；取不到时返回 {@code "unknown:unknown"}。 */
    String typeId(T recipe);

    /**
     * 通用接口（{@code getIngredients()}）读到的物品输入。
     *
     * <p>这一层只做「Ingredient → 候选 id」的搬运；标签还原由
     * {@link ExtractionContext} 负责（它拿得到标签索引）。
     */
    List<RawSlot> genericIngredients(T recipe);

    /**
     * 通用接口（{@code getResultItem}）读到的产出。
     *
     * <p>必须传真实的注册表 —— 传 null 会让需要注册表的配方（盔甲纹饰）NPE，
     * 而那个 NPE 曾被 catch 吞掉，伪装成「Minecraft 的限制」。
     *
     * @return 没有产出时返回 {@code null}
     */
    Models.ItemStack genericResult(T recipe);

    /**
     * {@code getToastSymbol()} 返回的物品 id，作为机器推断的输入。
     *
     * @return 取不到（空栈）时返回 {@code null}
     */
    String toastItemId(T recipe);
}
