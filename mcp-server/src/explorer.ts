/**
 * 可交互 HTML 视图 —— 给人看的渲染出口，与给 AI 读的 report.ts 并列。
 *
 * <h2>它解决什么问题</h2>
 *
 * markdown 报告是喂给模型的：为省 token 默认只展示前三层结构。用户想要
 * 「自己翻」的完整视图时，模型只能把结果复述一遍 —— 又贵又不方便。
 * 这里把**全量**结构（不裁剪）渲染成一个自包含网页写到磁盘，工具只返回路径，
 * 不占模型上下文。
 *
 * <h2>约束</h2>
 *
 * <ul>
 *   <li><b>自包含</b>：CSS/JS 全部内联，不引用任何 CDN —— 用户可能离线打开。
 *       smoke 里有断言盯着这一点（整份输出里不许出现 http(s)://）。</li>
 *   <li><b>无构建</b>：手写模板字符串，不引前端框架和打包器。
 *       这个仓库没有前端工具链，为一个输出格式引入 Node 之外的工具链不划算。</li>
 *   <li><b>不是</b>力导向图，也不做图片导出 —— 折叠大纲对「逐环节核对数字」
 *       这个真实用例更有效，图好看但读不了数。</li>
 * </ul>
 *
 * <h2>与 markdown 的关系</h2>
 *
 * 数字与判断（缺口分类、选路规则、落选原因）与 report.ts 同源：
 * 缺口判定共用 classifyGaps，文案与 markdown 版一一对应。
 * 表述方式随介质不同（markdown bold vs HTML strong），但事实只有一份。
 */

import fs from "node:fs";
import path from "node:path";

import { cacheDir, type RecipeStore, type StackKind } from "./cache.js";
import {
  type GapClassification,
  classifyGaps,
  collectMachineSteps,
  machineStatus,
  SKIP_REASON,
} from "./report.js";
import { RAW_REASON_TEXT, type PlanNode, type ProductionPlan } from "./plan.js";
import type { TreeNode, TreeResult } from "./tree.js";
import { VERSION } from "./version.js";

// ---------------------------------------------------------------- 小工具

function esc(s: unknown): string {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]!,
  );
}

/** 与 report.ts 的 round 同义：展示值最多保留两位小数。 */
function fmt(n: number): string {
  return String(Math.round(n * 100) / 100);
}

function unit(kind: StackKind): string {
  return kind === "fluid" ? " mB" : "";
}

/** 显示名 + id。两者都要过 esc —— 显示名来自语言包，内容不受我们控制。 */
function label(store: RecipeStore, id: string): string {
  const n = store.itemName(id);
  return n === id ? esc(id) : `${esc(n)} <code>${esc(id)}</code>`;
}

/** 文件名安全段：物品 id 里的冒号、斜杠都替换掉。 */
export function safeFilePart(s: string): string {
  return s.replace(/[^A-Za-z0-9._-]+/g, "__");
}

/**
 * 把渲染好的网页写进缓存目录的 explorer/ 子目录，返回绝对路径。
 *
 * 为什么放这里：它与 snapshot.json 同属「本机派生数据」，跟着缓存目录走
 * （含 CRAFTGRAPH_CACHE_DIR 覆盖）—— 测试脚本各自设置临时缓存目录，
 * 所以测试写文件天然不会碰用户目录。
 */
export function writeExplorerFile(baseName: string, html: string): string {
  const dir = path.join(cacheDir(), "explorer");
  fs.mkdirSync(dir, { recursive: true });
  const file = path.join(dir, `${safeFilePart(baseName)}.html`);
  fs.writeFileSync(file, html, "utf-8");
  return file;
}

// ---------------------------------------------------------------- 页面骨架

const CSS = `
:root{--bg:#f7f6f3;--card:#ffffff;--fg:#1f2430;--muted:#6b7280;--border:#e3e1db;
--accent:#b45309;--accent-bg:#fdf3e3;--code-bg:#eeece6;--hit:#fde68a;
--ok:#15803d;--ok-bg:#ecfdf3;--warn:#92400e;--warn-bg:#fef9ec;--bad:#b91c1c;--bad-bg:#fdf0ef;
--raw:#15803d;}
:root[data-theme=dark]{--bg:#14161a;--card:#1d2026;--fg:#e6e7ea;--muted:#9aa1ac;--border:#2e333c;
--accent:#f0a24b;--accent-bg:#2a2115;--code-bg:#262a31;--hit:#4a3a12;
--ok:#5bbf7f;--ok-bg:#14231a;--warn:#d9a44a;--warn-bg:#2a2115;--bad:#e07b72;--bad-bg:#2b1a19;
--raw:#5bbf7f;}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);
font:15px/1.65 system-ui,-apple-system,"Segoe UI","Microsoft YaHei",sans-serif;}
header{padding:20px 24px 12px;border-bottom:1px solid var(--border);background:var(--card);}
h1{margin:0 0 4px;font-size:20px;}
.head-row{display:flex;flex-wrap:wrap;gap:10px;align-items:center;justify-content:space-between;}
.toolbar{display:flex;gap:8px;align-items:center;flex-wrap:wrap;}
.toolbar input{width:230px;padding:6px 10px;border:1px solid var(--border);border-radius:8px;
background:var(--bg);color:var(--fg);}
.toolbar button{padding:6px 10px;border:1px solid var(--border);border-radius:8px;
background:var(--card);color:var(--fg);cursor:pointer;}
.toolbar button:hover{border-color:var(--accent);}
.q-count{font-size:12px;color:var(--muted);}
.meta{margin-top:8px;font-size:12.5px;color:var(--muted);}
main{max-width:1080px;margin:0 auto;padding:18px 24px 60px;}
section{margin:18px 0;}
h2{font-size:16px;margin:26px 0 10px;padding-bottom:4px;border-bottom:1px solid var(--border);}
.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(150px,1fr));gap:10px;}
.card{background:var(--card);border:1px solid var(--border);border-radius:10px;padding:10px 14px;}
.card .v{font-size:20px;font-weight:600;}
.card .k{font-size:12px;color:var(--muted);}
.verdict{border-radius:10px;padding:12px 16px;border:1px solid var(--border);background:var(--card);}
.verdict.v-clean{border-color:var(--ok);background:var(--ok-bg);}
.verdict.v-no_duration,.verdict.v-manual_ok{border-color:var(--warn);background:var(--warn-bg);}
.verdict.v-incomplete{border-color:var(--bad);background:var(--bad-bg);}
ul.gaps{margin:8px 0 0;padding-left:20px;}
ul.gaps li{margin:4px 0;}
code{background:var(--code-bg);border-radius:4px;padding:1px 5px;font-size:12.5px;
font-family:ui-monospace,Consolas,monospace;word-break:break-all;}
table{border-collapse:collapse;width:100%;background:var(--card);border:1px solid var(--border);
border-radius:10px;overflow:hidden;}
th,td{padding:7px 12px;text-align:left;border-bottom:1px solid var(--border);font-size:14px;}
th{background:var(--code-bg);font-weight:600;}
tr:last-child td{border-bottom:none;}
.num{font-variant-numeric:tabular-nums;}
.unknown{color:var(--bad);font-weight:600;}
.muted{color:var(--muted);}
details.node{margin:6px 0;border:1px solid var(--border);border-left:3px solid var(--muted);
border-radius:8px;background:var(--card);}
details.node>summary{cursor:pointer;padding:7px 12px;list-style-position:outside;user-select:none;}
details.node>summary:hover{background:var(--accent-bg);}
details.node .body{padding:2px 14px 10px 30px;}
details.node.st-craft{border-left-color:var(--accent);}
details.node.st-raw{border-left-color:var(--raw);}
details.node.st-opaque,details.node.st-unresolved{border-left-color:var(--warn);}
details.node.st-cycle{border-left-color:var(--bad);}
details.node.st-truncated,details.node.st-budget{border-left-color:var(--muted);}
details.node.hit>summary{background:var(--hit);}
details.node.target>summary{outline:2px solid var(--accent);}
.st{display:inline-block;font-size:11px;padding:1px 7px;border-radius:99px;margin-left:6px;
border:1px solid var(--border);color:var(--muted);}
.st-raw{color:var(--ok);border-color:var(--ok);}
.st-opaque,.st-unresolved{color:var(--warn);border-color:var(--warn);}
.st-cycle{color:var(--bad);border-color:var(--bad);}
.rate{font-variant-numeric:tabular-nums;font-weight:600;margin-right:8px;}
.mach{color:var(--accent);font-size:13.5px;margin-right:8px;}
.mach.warn{color:var(--bad);}
.slots{color:var(--muted);font-size:13px;}
.facts{font-size:13.5px;color:var(--muted);margin:4px 0;}
.note{font-size:13.5px;color:var(--warn);margin:4px 0;}
details.alts{margin:4px 0;}
details.alts>summary{cursor:pointer;color:var(--muted);font-size:13px;}
details.alts li{margin:3px 0;font-size:13.5px;}
.why{color:var(--warn);}
.alts-count{font-size:13px;color:var(--muted);margin:4px 0;}
.legend{display:flex;flex-wrap:wrap;gap:10px;font-size:12.5px;color:var(--muted);margin:6px 0 10px;}
.legend .dot{display:inline-block;width:9px;height:9px;border-radius:50%;margin-right:4px;}
.callout{border:1px solid var(--border);border-left:3px solid var(--accent);
background:var(--accent-bg);border-radius:8px;padding:10px 14px;font-size:13.5px;margin:10px 0;}
footer{max-width:1080px;margin:0 auto;padding:0 24px 30px;font-size:12px;color:var(--muted);}
`;

const JS = `
(function(){
var nodes=[].slice.call(document.querySelectorAll("details.node"));
var q=document.getElementById("q"),qc=document.getElementById("q-count");
if(q)q.addEventListener("input",function(){
  var v=q.value.trim().toLowerCase(),hits=0;
  nodes.forEach(function(n){
    var key=(n.dataset.key||n.querySelector("summary").textContent).toLowerCase();
    var m=v&&key.indexOf(v)>=0;
    n.classList.toggle("hit",!!m);
    if(m)hits++;
  });
  qc.textContent=v?hits+" 处命中":"";
  if(v)nodes.forEach(function(n){
    if(!n.classList.contains("hit"))return;
    var p=n.parentElement?n.parentElement.closest("details.node"):null;
    while(p){p.open=true;p=p.parentElement?p.parentElement.closest("details.node"):null;}
  });
});
var ea=document.getElementById("expand-all"),ca=document.getElementById("collapse-all");
if(ea)ea.onclick=function(){nodes.forEach(function(n){n.open=true;});};
if(ca)ca.onclick=function(){nodes.forEach(function(n){n.open=false;});};
var tb=document.getElementById("theme");
if(tb)tb.onclick=function(){
  var r=document.documentElement,cur=r.dataset.theme==="dark"?"light":"dark";
  r.dataset.theme=cur;try{localStorage.setItem("cg-theme",cur);}catch(e){}
};
function openHash(){
  var h=location.hash.slice(1);if(!h)return;
  var el=document.getElementById(h);if(!el||!el.classList.contains("node"))return;
  var p=el;while(p){if(p.tagName==="DETAILS")p.open=true;p=p.parentElement?p.parentElement.closest("details"):null;}
  el.scrollIntoView({block:"center"});el.classList.add("target");
}
window.addEventListener("hashchange",openHash);openHash();
})();`;

function page(title: string, meta: string, body: string): string {
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(title)} · CraftGraph</title>
<script>try{var t=localStorage.getItem("cg-theme")||(matchMedia("(prefers-color-scheme: dark)").matches?"dark":"light");document.documentElement.dataset.theme=t;}catch(e){}</script>
<style>${CSS}</style>
</head>
<body>
<header>
  <div class="head-row">
    <h1>${esc(title)}</h1>
    <div class="toolbar">
      <input id="q" type="search" placeholder="搜索物品 / 配方 / 机器…" autocomplete="off">
      <span id="q-count" class="q-count"></span>
      <button id="expand-all" type="button">全部展开</button>
      <button id="collapse-all" type="button">全部折叠</button>
      <button id="theme" type="button">明暗</button>
    </div>
  </div>
  <div class="meta">${meta}</div>
</header>
<main>
${body}
</main>
<footer>CraftGraph 生成 · 文件自包含，可离线打开与分享；数字口径与 MCP 报告一致。</footer>
<script>${JS}</script>
</body>
</html>`;
}

function pageMeta(store: RecipeStore): string {
  const s = store.status;
  return (
    `CraftGraph v${esc(VERSION)} · dataVersion ${s.dataVersion} · ` +
    (s.offline ? "离线快照（游戏未在运行，数据可能过时）" : "来自运行中的游戏") +
    ` · 生成于 ${new Date().toLocaleString("zh-CN")}`
  );
}

function cards(items: { v: string; k: string }[]): string {
  return (
    `<section class="cards">` +
    items.map((i) => `<div class="card"><div class="v">${i.v}</div><div class="k">${esc(i.k)}</div></div>`).join("") +
    `</section>`
  );
}

function table(headers: string[], rows: string[][]): string {
  return (
    `<table><thead><tr>${headers.map((h) => `<th>${h}</th>`).join("")}</tr></thead><tbody>` +
    rows.map((r) => `<tr>${r.map((c) => `<td>${c}</td>`).join("")}</tr>`).join("") +
    `</tbody></table>`
  );
}

// ---------------------------------------------------------------- 节点状态

const ST_BADGE: Record<string, string> = {
  raw: "基础原料",
  opaque: "读不懂",
  cycle: "循环依赖",
  truncated: "深度截断",
  budget: "数量截断",
  unresolved: "无法解析",
};

const LEGEND: { status: string; color: string; text: string }[] = [
  { status: "craft", color: "accent", text: "有配方，已展开" },
  { status: "raw", color: "raw", text: "无配方产出，算作基础原料" },
  { status: "opaque", color: "warn", text: "配方读不懂，其原料未计入" },
  { status: "cycle", color: "bad", text: "循环依赖，已截断" },
  { status: "truncated", color: "muted", text: "达到深度/数量上限，未展开" },
];

function legendHtml(): string {
  return (
    `<div class="legend">` +
    LEGEND.map(
      (l) => `<span><span class="dot" style="background:var(--${l.color})"></span>${esc(l.text)}</span>`,
    ).join("") +
    `</div>`
  );
}

/** 落选原因的措辞（SKIP_REASON）与机器数状态（machineStatus）都从 report.ts 引入 ——
 * 同一个事实要走两个出口时，判定与文案只能有一份实现。 */

// ---------------------------------------------------------------- 产线

export function renderPlanHtml(store: RecipeStore, plan: ProductionPlan, targetLabel: string): string {
  const machineSteps = collectMachineSteps(plan.root);
  const g = classifyGaps(store, plan, machineSteps);

  // 结论：判定在 classifyGaps（只有一份），这里的措辞是 HTML 介面的呈现。
  const VERDICT: Record<GapClassification["verdict"], { cls: string; text: string }> = {
    clean: { cls: "v-clean", text: "没有已知缺口 —— 每个环节都算出了台数，原料也都查得到来源。" },
    manual_ok: {
      cls: "v-manual_ok",
      text: "原料表可用；机器台数都算出来了。工作台合成的环节只有合成次数（原版没有耗时字段，谈不上台数）。",
    },
    no_duration: { cls: "v-no_duration", text: "原料表可用；台数有算不出的（见下），那些环节得靠你按实际布置估。" },
    incomplete: {
      cls: "v-incomplete",
      text: "结构可以参考，但原料与副产的数字不要直接照着建产线（见下「有环节没展开」）。",
    },
  };
  const v = VERDICT[g.verdict];
  const gapItems: string[] = [];
  if (g.noDuration.length > 0) {
    gapItems.push(
      `<li><b>台数算不出</b>：${g.noDuration.map((n) => label(store, n)).join("、")} —— 这些环节是机器加工，` +
        `但读不到耗时（模组机器配方常缺 processingTime，游戏会当成瞬间完成）。` +
        `<b>不是「不需要机器」，也不是「手工合成」</b>，只是我们读不到它的速度。</li>`,
    );
  }
  if (g.noRecipe.length > 0) {
    const items = g.noRecipe.map((n) => `${label(store, n.item)} ${fmt(n.ratePerMinute)}${unit(n.kind)}/分`);
    gapItems.push(
      `<li><b>原料链到此为止</b>：${items.slice(0, 4).join("、")}${items.length > 4 ? ` 等 ${items.length} 种` : ""} —— ` +
        `这个包的数据里没有能产出它们的配方，得你自己获得（挖、刷、或别的方式）。</li>`,
    );
  }
  if (g.incomplete.length > 0) {
    const byReason = new Map<string, string[]>();
    for (const i of g.incomplete) {
      const arr = byReason.get(i.reason);
      if (arr) arr.push(i.item); else byReason.set(i.reason, [i.item]);
    }
    const parts = [...byReason].map(([reason, items]) => `${items.map((n) => label(store, n)).join("、")}（${esc(reason)}）`);
    gapItems.push(`<li><b>有环节没展开</b>：${parts.join("；")} —— <b>所以原料与副产数字是下限</b>。</li>`);
  }
  if (g.probabilistic.length > 0) {
    gapItems.push(
      `<li><b>按期望值估算</b>：${g.probabilistic.map((n) => label(store, n)).join("、")} 是概率产出，实际会偏少。</li>`,
    );
  }
  if (g.dataCaveat.length > 0) gapItems.push(`<li><b>数据底子</b>：${esc(g.dataCaveat)}</li>`);
  if (g.others.length > 0) gapItems.push(`<li><b>其他</b>：${g.others.map(esc).join("；")}</li>`);

  let body = `<section class="verdict ${v.cls}"><b>结论</b>：${esc(v.text)}`;
  if (gapItems.length > 0) body += `<ul class="gaps">${gapItems.join("")}</ul>`;
  body += `</section>`;

  if (g.manualCrafting.length > 0) {
    body +=
      `<div class="callout">ℹ️ 另有 ${g.manualCrafting.length} 个工作台合成环节：` +
      `原版设计如此（没有耗时字段），只能给出合成次数，报「手工」是对的。</div>`;
  }

  // ---- 汇总卡 ----
  const machineKinds = plan.machines.length;
  const machineTotal = plan.machines.reduce((s, m) => s + m.count, 0);
  const unknownMachines = machineSteps.filter((s) => s.machines == null).length;
  body += cards([
    { v: String(plan.rawMaterials.length), k: "种基础原料" },
    {
      v: machineKinds === 0 ? "—" : String(machineTotal),
      k: machineKinds === 0 ? "无机器环节" : `台机器（${machineKinds} 种${unknownMachines > 0 ? `，${unknownMachines} 处未知` : ""}）`,
    },
    { v: String(plan.byproducts.length), k: "种副产物" },
    {
      v: plan.totalEnergyPerMinute == null ? "未知" : fmt(plan.totalEnergyPerMinute),
      k: plan.totalEnergyPerMinute == null ? "能耗（读不到数据）" : "FE/分钟 能耗",
    },
  ]);

  // ---- 机器表 ----
  body += `<section><h2>需要多少机器</h2>`;
  if (machineSteps.length === 0) {
    body += `<p class="muted">这份规划里没有需要机器的环节，全是工作台合成。</p>`;
  } else {
    body += table(
      ["环节", "机器", "耗时", "台数"],
      machineSteps.map((s) => [
        label(store, s.item),
        s.machineId ? label(store, s.machineId) : `<span class="muted">读不到机器名</span>`,
        s.secondsPerCraft == null ? `<span class="muted">未知</span>` : `${fmt(s.secondsPerCraft)} 秒`,
        s.machines == null ? `<span class="unknown">未知</span>` : `<span class="num">${s.machines}</span>`,
      ]),
    );
    if (unknownMachines > 0) {
      body += `<p class="muted">台数「未知」${unknownMachines} 处：这些环节耗时读不到（模组机器配方常缺 processingTime），所以算不出台数 —— 不是「不需要机器」。</p>`;
    }
    body += `<p class="muted">机器数按「满载运行」估算，未考虑上下游没对齐导致的空转。</p>`;
  }
  body += `</section>`;

  // ---- 原料 ----
  body += `<section><h2>每分钟原料需求</h2>`;
  if (plan.rawMaterials.length === 0) {
    body += `<p class="muted">没有识别出基础原料。</p>`;
  } else {
    body += table(
      ["物品", "每分钟"],
      plan.rawMaterials.map((m) => [label(store, m.item), `<span class="num">${fmt(m.ratePerMinute)}${unit(m.kind)}</span>`]),
    );
  }
  body += `</section>`;

  // ---- 副产 ----
  if (plan.byproducts.length > 0) {
    body += `<section><h2>副产物</h2>`;
    body += table(
      ["物品", "每分钟", "来源", "说明"],
      plan.byproducts.map((bp) => [
        label(store, bp.item),
        `<span class="num">${fmt(bp.ratePerMinute)}${unit(bp.kind)}</span>`,
        `<code>${esc(bp.fromRecipe)}</code>`,
        bp.probabilistic ? "期望值（概率产出）" : "必然产出",
      ]),
    );
    if (plan.byproductReuse.length > 0) {
      body += `<p><b>其中可以回代的</b>（提示 —— 上面的原料表没有假设你这么做）：</p><ul>`;
      for (const reuse of plan.byproductReuse) {
        const where: string[] = [];
        for (const use of reuse.usedBy) where.push(`规划里的 ${label(store, use.item)} 已经在用它`);
        for (const alt of reuse.alternativeFor) {
          where.push(`把 ${label(store, alt.item)} 改用 <code>${esc(alt.recipeId)}</code> 就能吃下它`);
        }
        body += `<li>${label(store, reuse.item)} ${fmt(reuse.ratePerMinute)}${unit(reuse.kind)}/分 —— ${where.join("；")}。</li>`;
      }
      body += `</ul><p class="muted">要不要回代由你定：能不能用取决于上游能不能把副产运回去，数字里没有替你假设。</p>`;
    } else {
      body += `<p class="muted">副产只做统计，没有回代抵扣上游消耗。</p>`;
    }
    body += `</section>`;
  }

  // ---- 能耗 ----
  body += `<section><h2>能耗</h2>`;
  body +=
    plan.totalEnergyPerMinute === null
      ? `<p class="muted">有环节读不到能耗数据（原版配方大多没有这个字段），无法给出总量。</p>`
      : `<p>合计约 <b>${fmt(plan.totalEnergyPerMinute)} FE/分钟</b>。</p>`;
  body += `</section>`;

  // ---- 逐环节明细（全量，不裁剪 —— 这正是网页版存在的意义）----
  const routeRule =
    `<div class="callout"><b>选路规则</b>：每个物品在「不构成循环」的候选里，取<b>合并后输入种数最少</b>的一条，` +
    `并列时取单次产出多的。输入按合并后算 —— 3×3 有序合成摆 8 个材料、其实只有 2 种，算 2 而不是 8。` +
    `「输入 N 种」越小的候选越可能被换用：不比它差的候选会列出数字，明显更差的只报数量。</div>`;
  const ids = { n: 0 };
  body += `<section><h2>逐环节明细</h2>${routeRule}${planNodeHtml(store, plan.root, 0, ids)}</section>`;

  return page(
    `产线规划：每分钟 ${plan.target.ratePerMinute}${unit(plan.target.kind)} ${targetLabel}`,
    pageMeta(store),
    body,
  );
}

/** ids 是共享计数器 —— 节点 id 必须全页唯一（深链锚点靠它）。 */
function planNodeHtml(store: RecipeStore, node: PlanNode, depth: number, ids: { n: number }): string {
  const id = `n${ids.n++}`;

  const bits: string[] = [
    `<span class="rate">${fmt(node.ratePerMinute)}${unit(node.kind)}/分</span>`,
    label(store, node.item),
  ];
  const badge = ST_BADGE[node.status] ? `<span class="st st-${esc(node.status)}">${esc(ST_BADGE[node.status])}</span>` : "";

  if (node.status === "craft") {
    // 判定来自 machineStatus（report.ts 独一份）；措辞是 HTML 介面的呈现
    const ms = machineStatus(node);
    if (ms === "manual") {
      bits.push(`<span class="mach">工作台（按次合成）</span>`);
    } else if (ms === "computed") {
      bits.push(`<span class="mach">${esc(node.machineId ? store.itemName(node.machineId) : "机器")} ×${node.machines}</span>`);
    } else {
      bits.push(`<span class="mach warn">台数未知</span>`);
    }
    if (node.inputSlots != null) bits.push(`<span class="slots">输入 ${node.inputSlots} 种</span>`);
  }

  const facts: string[] = [];
  if (node.recipeId) facts.push(`配方 <code>${esc(node.recipeId)}</code>`);
  if (node.recipeType && node.recipeType !== "minecraft:crafting") facts.push(`类型 <code>${esc(node.recipeType)}</code>`);
  if (node.craftsPerMinute != null) facts.push(`每分钟 ${fmt(node.craftsPerMinute)} 次`);
  if (node.outputPerCraft != null) facts.push(`每次产 ${fmt(node.outputPerCraft)}${node.probabilistic ? "（期望）" : ""}`);
  if (node.secondsPerCraft != null) facts.push(`单次 ${fmt(node.secondsPerCraft)} 秒`);
  if (node.chosenFromTag) {
    const alts = node.tagAlternatives?.length ? `（其他候选：${node.tagAlternatives.map(esc).join("、")}）` : "";
    facts.push(`输入从 #${esc(node.chosenFromTag)} 选定${alts}`);
  }
  if (node.status === "raw") {
    // 文案与 plan.ts 写进 note 的是同一份（RAW_REASON_TEXT）—— 两个出口必须说同一句话
    facts.push(RAW_REASON_TEXT[node.rawReason ?? "no_recipe"]);
  }

  // 落选候选 —— 语义与 report.ts 的 renderPlanNode 一致
  let altsHtml = "";
  const detail = node.alternativesDetail;
  if (detail && detail.length > 0) {
    const chosenNoDuration = node.secondsPerCraft == null;
    const li = detail.map((a) => {
      const slots = a.inputSlots == null ? "读不懂输入" : `输入 ${a.inputSlots} 种`;
      const per = a.perCraft == null ? "产出读不懂" : `每次产 ${fmt(a.perCraft)}`;
      let note = "";
      if (a.skipReason != null) {
        note = ` —— <span class="why">${esc(`按规则它更优，但${SKIP_REASON[a.skipReason]}`)}</span>`;
      } else if (chosenNoDuration && a.secondsPerCraft != null) {
        note = ` —— 单次 ${fmt(a.secondsPerCraft)} 秒，<b>换它才算得出这个环节的台数</b>`;
      } else if (a.secondsPerCraft != null) note = `，单次 ${fmt(a.secondsPerCraft)} 秒`;
      return `<li><code>${esc(a.recipeId)}</code>（${esc(a.type)}，${slots}/${per}）${note}</li>`;
    });
    const rest = node.alternatives.length - detail.length;
    altsHtml = `<details class="alts"><summary>落选候选 ${detail.length} 条${rest > 0 ? `（另有 ${rest} 条明显更差，未列）` : ""}</summary><ul>${li.join("")}</ul></details>`;
  } else if (node.alternatives.length > 0) {
    altsHtml = `<div class="alts-count">另有 ${node.alternatives.length} 条候选配方</div>`;
  }

  const note = node.note ? `<div class="note">${esc(node.note)}</div>` : "";
  const kids = node.children.map((c) => planNodeHtml(store, c, depth + 1, ids)).join("");
  const open = depth <= 1 ? " open" : "";
  // data-key 是搜索的匹配域：物品 id + 显示名 + 配方 id + 机器 id
  const key = `${node.item} ${node.recipeId ?? ""} ${node.machineId ?? ""} ${store.itemName(node.item)}`;

  return (
    `<details class="node st-${esc(node.status)}" id="${id}"${open} data-key="${esc(key)}">` +
    `<summary>${bits.join(" ")}${badge}</summary>` +
    `<div class="body">${facts.length > 0 ? `<div class="facts">${facts.join(" · ")}</div>` : ""}${note}${altsHtml}${kids}</div>` +
    `</details>`
  );
}

// ---------------------------------------------------------------- 配方树

export function renderTreeHtml(store: RecipeStore, result: TreeResult, targetLabel: string): string {
  let body = cards([
    { v: String(result.nodeCount), k: "个节点" },
    { v: String(result.rawMaterials.length), k: "种基础原料" },
    { v: String(result.recipesUsed.length), k: "条配方" },
    { v: result.truncated ? "是" : "否", k: "被截断（是则原料表是下限）" },
  ]);

  body += legendHtml();

  body += `<section><h2>基础原料合计</h2>`;
  if (result.rawMaterials.length === 0) {
    body += `<p class="muted">没有识别出基础原料。</p>`;
  } else {
    body += table(
      ["物品", "总共需要"],
      result.rawMaterials.map((m) => [label(store, m.item), `<span class="num">${fmt(m.count)}${unit(m.kind)}</span>`]),
    );
  }
  body += `</section>`;

  if (result.warnings.length > 0) {
    body += `<section><h2>需要注意</h2><ul class="gaps">${result.warnings.map((w) => `<li>${esc(w)}</li>`).join("")}</ul></section>`;
  }

  const ids = { n: 0 };
  body += `<section><h2>结构</h2>${treeNodeHtml(store, result.root, 0, ids)}</section>`;

  return page(`配方树：${targetLabel}`, pageMeta(store), body);
}

function treeNodeHtml(store: RecipeStore, node: TreeNode, depth: number, ids: { n: number }): string {
  const id = `n${ids.n++}`;

  const bits: string[] = [`<span class="rate">${fmt(node.count)}${unit(node.stackKind)}</span>`, label(store, node.item)];
  const badge = ST_BADGE[node.kind] ? `<span class="st st-${esc(node.kind)}">${esc(ST_BADGE[node.kind])}</span>` : "";

  const facts: string[] = [];
  if (node.recipeId) {
    facts.push(`配方 <code>${esc(node.recipeId)}</code>`);
    if (node.recipeType && node.recipeType !== "minecraft:crafting") facts.push(`类型 <code>${esc(node.recipeType)}</code>`);
    if (node.crafts != null) facts.push(`做 ${fmt(node.crafts)} 次`);
    if (node.outputPerCraft != null) facts.push(`每次产 ${fmt(node.outputPerCraft)}${node.probabilistic ? "（期望）" : ""}`);
  }
  if (node.chosenFromTag) {
    const alts = node.tagAlternatives?.length ? `（其他候选：${node.tagAlternatives.map(esc).join("、")}）` : "";
    facts.push(`从 #${esc(node.chosenFromTag)} 选定${alts}`);
  }
  if (node.mergedSlots && node.mergedSlots > 1) facts.push(`由 ${node.mergedSlots} 个原始槽位合并`);

  const note = node.note ? `<div class="note">${esc(node.note)}</div>` : "";
  const kids = node.children.map((c) => treeNodeHtml(store, c, depth + 1, ids)).join("");
  const open = depth <= 1 ? " open" : "";
  const key = `${node.item} ${node.recipeId ?? ""} ${store.itemName(node.item)}`;

  return (
    `<details class="node st-${esc(node.kind)}" id="${id}"${open} data-key="${esc(key)}">` +
    `<summary>${bits.join(" ")}${badge}</summary>` +
    `<div class="body">${facts.length > 0 ? `<div class="facts">${facts.join(" · ")}</div>` : ""}${note}${kids}</div>` +
    `</details>`
  );
}
