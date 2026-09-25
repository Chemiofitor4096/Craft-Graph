/**
 * 字段覆盖度：`duration` / `machine` 各有多少条配方读到了 —— 统计、渲染、解析。
 *
 * <h2>为什么它是独立的模块</h2>
 *
 * 这是**诊断功能**，不是报告渲染：消费者是 `get_bridge_status`（manager.ts）
 * 和 `live.ts`，与树/产线的 markdown、TSV、HTML 渲染没有关系。
 * 从 report.ts 拆出来后，report.ts 只剩一件事：把算好的结果渲染出来。
 *
 * <h2>为什么这个统计必须出现在 agent 看得到的地方</h2>
 *
 * 这两个字段曾经在 1290 条真实配方上**全是 null** —— 原版通用接口给不出它们，
 * 而没人去读各配方类自己的字段。后果是 `calculate_production_plan`
 * 每个环节都报「手工」，机器数计算整个失效。
 *
 * 它藏了很久的原因值得记住：41 项算法测试 + 83 个 JUnit 用例 + 17 项契约测试
 * **全部通过**，因为夹具里手写了 duration，而没有任何一层断言过真数据里的这两个字段。
 *
 * 所以现在它们出现在 `get_bridge_status` 里：agent 每次排查问题都会看到，
 * 也就能据此告诉玩家「机器数只对熔炼那部分配方有效」，
 * 而不是拿一份看起来完整、实际只有 8.7% 覆盖的数据去规划产线。
 */

import type { Recipe } from "./types.js";

export interface FieldCoverage {
  total: number;
  withDuration: number;
  withMachine: number;
  /** 有耗时数据的配方类型，按类型 id 排序 */
  durationTypes: { type: string; withDuration: number; total: number }[];
}

/**
 * 从 `get_bridge_status` 渲染出来的文本里**读回**覆盖度。
 *
 * <h2>为什么它必须和渲染函数住在同一个文件</h2>
 *
 * 它一开始写在 `live.ts` 里（那是唯一的使用者），于是**没有任何测试碰得到它** ——
 * 结果它把「机器」那一对数取错了：分母当成了分子。症状很阴险：
 *
 * - 断言打印出「机器 15241/15241」，而真实是「10945/15241」；
 * - 而且 `withMachine > 0` 因为拿到了分母，**永远为真** —— 一条不可能失败的断言。
 *
 * 在近原版实例上它没被发现，因为那里的机器覆盖恰好是 100%，两个数字相同。
 * 第一次跑到覆盖不全的整合包上才露出来。
 *
 * 所以：渲染与解析放在一起，由 smoke 的往返测试钉住（渲染 → 解析 → 数字必须回到原值）。
 * **只在一侧有实现、又没有测试的格式约定，迟早会漂移。**
 *
 * @returns 解析不出覆盖度行时返回 {@code null}（旧版 Mod 没有这一行）
 */
export function parseFieldCoverage(text: string): FieldCoverageWithListing | null {
  const m = text.match(/字段覆盖：耗时 (\d+)\/(\d+)[^·]*· 机器 (\d+)\/(\d+)/);
  if (!m) return null;

  const durationTypes: FieldCoverage["durationTypes"] = [];
  let durationTypeCount = 0;
  const typeLine = text.match(/带耗时的类型：(.*)$/m);
  let listText = typeLine?.[1] ?? "";

  // 类型多的时候渲染会截断成「…、createdieselgenerators:bulk_fermenting 4/4，等 18 种」——
  // 注意那一项和「等 N 种」之间是**中文逗号**，不是顿号。
  // 不先把这段切掉的话，最后一项会因为正则不匹配而被丢掉，总数也读不到
  // （实测：整合包 18 种类型时丢一项，而断言拿它当全部）。
  const more = listText.match(/，?等 (\d+) 种$/);
  if (more) {
    durationTypeCount = Number(more[1]);
    listText = listText.slice(0, listText.length - more[0].length);
  }

  for (const part of listText.split("、")) {
    const t = part.match(/^([\w:.\-]+) (\d+)\/(\d+)$/);
    if (t) durationTypes.push({ type: t[1]!, withDuration: Number(t[2]), total: Number(t[3]) });
  }
  if (durationTypeCount === 0) durationTypeCount = durationTypes.length;

  return {
    total: Number(m[2]),
    withDuration: Number(m[1]),
    withMachine: Number(m[3]),
    durationTypes,
    durationTypeCount,
  };
}

/** 解析结果附带「列表是否被截断」的信息 —— 断言不该把「列出来的那几种」当成全部。 */
export interface FieldCoverageWithListing extends FieldCoverage {
  /** 带耗时的类型总共多少种（含被截断没列出来的）。 */
  durationTypeCount: number;
}

export function computeFieldCoverage(recipes: Recipe[]): FieldCoverage {
  // 两个 map 分开数，一遍扫完。刻意不用「遇到有耗时的条目才建桶」那种写法 ——
  // 那样桶里的 total 会取决于配方出现的顺序，把覆盖率算得比实际好看，
  // 而「算得比实际好看」正是这类统计最危险的失效方式。
  const totals = new Map<string, number>();
  const durations = new Map<string, number>();
  let withDuration = 0;
  let withMachine = 0;

  for (const r of recipes) {
    totals.set(r.type, (totals.get(r.type) ?? 0) + 1);
    if (r.duration != null) {
      withDuration++;
      durations.set(r.type, (durations.get(r.type) ?? 0) + 1);
    }
    if (r.machine != null) withMachine++;
  }

  return {
    total: recipes.length,
    withDuration,
    withMachine,
    durationTypes: [...durations.entries()]
      .map(([type, withDur]) => ({ type, withDuration: withDur, total: totals.get(type) ?? withDur }))
      .sort((a, b) => a.type.localeCompare(b.type)),
  };
}

/** 覆盖度渲染成给 AI 看的几行。类型列表会截断 —— 大整合包里可能有几十种带耗时的类型。 */
export function renderFieldCoverage(c: FieldCoverage, maxTypes = 8): string[] {
  if (c.total === 0) return [];
  const pct = (n: number) => ((n * 100) / c.total).toFixed(1);
  const lines = [
    `- 字段覆盖：耗时 ${c.withDuration}/${c.total}（${pct(c.withDuration)}%）· ` +
      `机器 ${c.withMachine}/${c.total}（${pct(c.withMachine)}%）`,
  ];

  if (c.withDuration < c.total) {
    lines.push(
      "  没有耗时的配方**无法计算机器数**，`calculate_production_plan` 会把它们按「手工」处理。" +
        "这是数据本身的限制（原版只有熔炼类配方带耗时），不是查询出错。",
    );
  }
  if (c.durationTypes.length > 0) {
    const shown = c.durationTypes.slice(0, maxTypes).map((t) => `${t.type} ${t.withDuration}/${t.total}`);
    const more = c.durationTypes.length > maxTypes ? `，等 ${c.durationTypes.length} 种` : "";
    lines.push(`  带耗时的类型：${shown.join("、")}${more}`);
  }
  return lines;
}
