/**
 * 「这条配方读不懂」该怎么说给用户听。
 *
 * <h2>为什么值得单独一个模块</h2>
 *
 * 这句话出现在用户**最卡的那一刻** —— 配方树展开不下去、产线算不出原料的时候。
 * 写错的代价很直接：在 Create 专精的整合包上实测，它当时写的是
 * 「装 EMI 或加适配层后可以读到」，而
 *
 * 1. 我们的方案已经改成 **JEI 优先**（JEI 普及率远高于 EMI）；
 * 2. 「适配层」是内部概念，玩家看不懂；
 * 3. 更要紧的是它**没区分两种完全不同的原因** ——
 *    原版代码驱动的配方谁都读不到（物理限制），模组自定义格式是我们还没写适配器
 *    （值得做的事）。用户看到同一句话，没法判断该不该期待它被修好。
 *
 * 所以这里按命名空间分开说，并给出可执行的下一步（在游戏里用 JEI 核对）。
 */
export function opaqueHint(types: readonly string[]): string {
  const modded = types.filter((t) => !t.startsWith("minecraft:"));
  const vanilla = types.filter((t) => t.startsWith("minecraft:"));

  const parts: string[] = [];
  if (modded.length > 0) {
    parts.push(`CraftGraph 还没覆盖 ${modded.join("、")} 的配方格式（可以给这些模组写适配器）`);
  }
  if (vanilla.length > 0) {
    parts.push(`${vanilla.join("、")} 这类配方由代码驱动，没有声明式的输入输出可读`);
  }
  parts.push("要人工核对可以在游戏里按 R 用 JEI 查这些配方");
  return parts.join("；") + "。";
}
