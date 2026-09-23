<div align="center">

# CraftGraph MCP

**Query your Minecraft modpack's recipes from an AI client — and turn them into production plans.**

English | [简体中文](README.zh_CN.md)

</div>

CraftGraph connects a **running** Minecraft instance to an AI client (Claude Code, Cursor, …)
over MCP. Ask *"how do I craft a torch"*, *"what can I use iron ingots for"*,
*"how do I build a line for 10 steel ingots per minute"* — the AI queries the live recipe data
of the pack you actually have loaded.

Here is real output from a vanilla 1.21.1 instance, asking for a torch:

```text
# 配方树：1 × 火把（minecraft:torch）

**1 × 火把（minecraft:torch）**
  ↳ minecraft:torch（minecraft:crafting）执行 1 次，每次产出 4
  **1 × 煤炭（minecraft:coal）** 【#minecraft:coals】
    ↳ minecraft:coal_from_blasting_coal_ore（minecraft:blasting）执行 1 次，每次产出 1
    ● **1 × 煤矿石（minecraft:coal_ore）**
  **1 × 木棍（minecraft:stick）** 【#c:rods/wooden】
    ↳ minecraft:stick（minecraft:crafting）执行 1 次，每次产出 4
    **2 × 橡木木板（minecraft:oak_planks）** 【#minecraft:planks】
      ↳ minecraft:oak_planks（minecraft:crafting）执行 1 次，每次产出 4
      ⋯ 该分支未展开，共 2 节点 / 1 条配方；原料 minecraft:oak_log ×1

## 基础原料汇总

| 物品 | 数量 |
|---|---|
| 煤矿石（minecraft:coal_ore） | 1 |
| 橡木原木（minecraft:oak_log） | 1 |
```

Note what it got right: `#minecraft:coals` means "any of the coal items" and it picked coal,
not charcoal; it merged the two separate plank slots of the stick recipe into `2 × oak_planks`;
and the raw material answer is **1 oak log**, not 3 — because one log yields four planks.

## Why not just use a wiki

Recipe data in a modpack is not static. KubeJS scripts rewrite recipes at load time, datapacks
override them, and every machine mod registers its own recipe types with its own semantics.
A wiki — or a scraper — gives you vanilla. CraftGraph reads what your instance has loaded
right now, including recipes that only exist at runtime.

## What it can do

| | |
|---|---|
| **Search** | `search_items`, `get_registry`, `list_recipe_types`, `expand_tag` |
| **Query recipes** | `get_recipes_for_output`, `get_recipes_for_input`, `get_recipe_details`, `find_alternative_recipes` |
| **Plan** | `build_recipe_tree` (recursive chain), `calculate_production_plan` (machines, raw material rates, byproducts, energy) |
| **Diagnose** | `get_bridge_status`, `refresh_recipes` |

Handled deliberately, because they are where naive tools produce wrong answers:

- **Recipe cycles** — iron block = 9 iron ingots, iron ingot = 1/9 iron block. Detected with
  two levels of lookahead, because a recipe whose *input* looks fine can still cycle one step later.
- **Tag ingredients** — `#forge:ingots/iron` means "any of these"; the tool picks a concrete item,
  shows which tag it came from, and lets you override the choice.
- **Recipes it cannot read** — flagged `opaque` instead of returning empty inputs.
  "No recipe" and "recipe I can't parse" are different answers, and the AI is told which one it got.
- **Probabilistic outputs** — reported as expected values, explicitly labelled as such.

## Requirements

| | |
|---|---|
| Minecraft | **1.21.1** (NeoForge 21.1.x) or **1.20.1** (MinecraftForge 47.2.0+) |
| Side | **Client** — the mod reads from the client, so singleplayer and multiplayer both work |
| Node.js | ≥ 20 (for the MCP server) |

## Install

Two halves, and **you need both** — the jar alone cannot answer anything, because all the
recipe-tree and planning logic lives in the server.

**1. The mod** — from the
[Releases page](https://github.com/Chemiofitor4096/Craft-Graph/releases), download the jar for
your game version and put it in your mods folder:

| Your game | Download |
|---|---|
| Minecraft 1.21.1 + NeoForge | `craftgraph-<version>.jar` |
| Minecraft 1.20.1 + Forge 47.2.0+ | `craftgraph-<version>-mc1.20.1.jar` |

No release for your version? Build it yourself:

```bash
git clone https://github.com/Chemiofitor4096/Craft-Graph.git
cd Craft-Graph/mod && ./gradlew build          # 1.21.1
cd Craft-Graph/mod-1.20.1 && ./gradlew build   # 1.20.1 (needs JDK 17)
# → mod/build/libs/craftgraph-*.jar  /  mod-1.20.1/build/libs/craftgraph-*-mc1.20.1.jar
```

**2. The MCP server** — not published to npm yet, so build it from source:

```bash
cd Craft-Graph/mcp-server && npm install && npm run build
```

Then point your AI client at `mcp-server/dist/index.js`. For Claude Code:

```jsonc
{
  "mcpServers": {
    "craftgraph": {
      "command": "node",
      "args": ["/absolute/path/to/Craft-Graph/mcp-server/dist/index.js"]
    }
  }
}
```

**No port or token to configure.** The mod writes `~/.craftgraph/bridge.json` on startup,
and the MCP server finds it there. Both a vanilla-launcher and a third-party-launcher
location are written, so custom game directories work too.

Start Minecraft, load a world, and ask your AI client something. If it says it cannot reach
the game, `get_bridge_status` will tell you exactly what is wrong.

## Teach your AI client how to use it (optional, recommended)

`skill/craftgraph/SKILL.md` is a skill for AI clients: it teaches the workflow this tool
assumes — confirm the target rate *before* planning, how to read depth / `opaque` / truncation,
how to relay gaps honestly, and (the part that actually goes wrong) not to hand-recompute what
the tool already computed.

Install it by copying the directory into your client's skills folder:

```bash
cp -r skill/craftgraph ~/.claude/skills/     # Claude Code
cp -r skill/craftgraph ~/.zcode/skills/      # ZCode
```

Without it the model still works, but it tends to re-derive rates by hand and to scatter the
"here is what this plan cannot tell you" notes through the answer.

## Use it as a dependency

The mod is also published to **KessokuMaven**, so you can depend on it instead of building it
from source. The intended use is writing your own client for the bridge protocol and reusing
its DTOs (`dev.craftgraph.api.Models`) instead of re-declaring them by hand.

```groovy
repositories {
    maven { url = 'https://maven.kessokuteatime.work/releases' }
}

dependencies {
    // Compile against the DTOs only. To also load the mod at runtime, put the jar in mods/.
    compileOnly 'dev.craftgraph:craftgraph:0.3.3'            // Minecraft 1.21.1
    // The 1.20.1 build publishes under its own artifact id:
    // compileOnly 'dev.craftgraph:craftgraph-mc1.20.1:0.3.3'
}
```

A sources jar is published alongside, so the DTOs are readable from your IDE. They are a
direct mirror of [`doc/protocol.md`](doc/protocol.md) — **that document is the real contract**,
and it is the one to follow if the two ever disagree.

Browse published versions: <https://maven.kessokuteatime.work/#/releases/dev/craftgraph/craftgraph>

## How it works

```text
AI client (Claude Code / Cursor)
      │  MCP (stdio)
      ▼
MCP server (TypeScript)          recipe trees, production plans, caching
      │  HTTP + WebSocket, 127.0.0.1 only, Bearer token
      ▼
Bridge mod (Java / NeoForge)     reads recipes, builds indexes, serves them
      │  RecipeManager
      ▼
Minecraft 1.21.1 + your modpack
```

Two design decisions worth knowing about:

**The mod is a dumb data source.** All graph and planning logic lives in the TypeScript
server, because the mod runs inside a fragile environment: algorithms there cannot be tested
without launching the game, and a game crash would take them down with it.

**The mod serves immutable snapshots, not live queries.** Iterating a modpack's recipe
manager is main-thread-only work. Doing it per HTTP request means the game stutters every
time the AI asks something. Instead the snapshot is built once when recipes load (and rebuilt
on reload), so the request path never touches a game object — which also removes an entire
class of threading bugs.

## Documentation

| Document | Contents |
|---|---|
| [`AGENTS.md`](AGENTS.md) | Instructions for AI coding agents working in this repo — what to read first and the rules that prevent silently wrong answers |
| [`doc/protocol.md`](doc/protocol.md) | The bridge HTTP contract — the single source of truth for both halves |
| [`doc/decisions.md`](doc/decisions.md) | Locked technical decisions and what changing them would cost |
| [`doc/development.md`](doc/development.md) | Dev setup, the four test layers, token measurements, lessons learned |
| [`doc/format-evaluation.md`](doc/format-evaluation.md) | Measured evaluation of output formats, including a compact DSL proposal |

## Status

Verified end to end on a real 1.21.1 instance: 1290 recipes indexed, 2% flagged unreadable,
~40 ms main-thread extraction, AI client connected with zero configuration.

| | |
|---|---|
| Bridge mod | Works. 107 JUnit tests, none of which need Minecraft |
| MCP server | Works. 41 algorithm tests, MCP protocol tests, cross-language contract tests |
| Recipe coverage | All recipe types are read. 5 of the 7 types present in vanilla are 100% readable |
| Machine & duration data | Every recipe gets a machine; the 112 cooking recipes get a duration. Energy is not exposed by vanilla at all — it stays `null` rather than being invented |
| Smithing recipes | Read via an access transformer, the same way JEI does it. Netherite upgrades are fully readable; armour trims are read as far as they can honestly be |
| Modded recipes | Not yet measured — see limitations below |
| Recipe viewer integration | Not started. JEI first, EMI optional — see limitations below |
| In-game UI | Out of scope for the MVP by design |

## Known limitations

**Unreadable recipes are real, and they are counted.** On vanilla, 31 of 1290 recipes are
flagged `opaque`: 18 armour trims and 13 code-driven special recipes (dyeing, map cloning,
fireworks, banner duplication). Run `npm run inspect` to see the breakdown by recipe type for
your own pack.

There is a third case worth knowing about: some recipe types **produce nothing at all** — fuel
definitions such as `createaddition:liquid_burning` or `petrochem:*_fuel` read as "burn this,
get energy". Their inputs are read fine and their output really is empty, so labelling them
`opaque` would make the AI say "I cannot read this" when the truth is "this produces nothing".
They carry `producesNothing: true` instead. Only an adapter that has actually read that recipe
type's own output fields is allowed to claim this — a recipe whose output merely *could not be
read* stays `opaque`.

The 13 code-driven specials cannot be fixed by anyone: their logic lives in Java, they declare
no ingredients, and recipe viewers special-case them for display rather than reading them.
`opaque` is the honest answer here, and the AI is told to say "I cannot read this" instead of
"this needs no materials".

The 18 armour trims are a different story, and worth understanding because they show where the
line is. Their inputs **are** readable — `SmithingRecipe` does not override `getIngredients()`,
but the three slots sit in package-private fields, so the mod ships an
`META-INF/accesstransformer.cfg` that makes them public (this is exactly what JEI does). What is
*not* representable is the output: `SmithingTrimRecipe#getResultItem()` returns a hardcoded
placeholder (`new ItemStack(Items.IRON_CHESTPLATE)` with the first trim pattern and redstone),
because the real result is combinatorial — any trimmable armour piece, with the trim applied.
So trims report their three inputs and an explicitly empty output, which keeps them `opaque`
with the reason "output unreadable" rather than the earlier "input and output both unreadable".

That placeholder is worth a warning for anyone touching this code: **fixing only the inputs
would have been worse than doing nothing.** Once the inputs are non-empty, `Readability` stops
flagging the recipe, and that hardcoded iron chestplate goes from "a marked placeholder" to "a
confidently wrong answer". The adapter therefore suppresses the generic result explicitly for
trims.

Observed behaviour before the fix, when asked "how do I make a netherite helmet": the AI relayed
"the input cannot be read, and that does not mean it needs no materials" — then described the
vanilla recipe from its own training data, explicitly labelled as not coming from the game. On
vanilla that happened to be right; on a modpack, where a KubeJS script or another mod may have
changed it, the same sentence would be confidently wrong. **`opaque` prevents a *silent* wrong
answer; it does not stop the model from filling the gap with memory** — which is why widening
real coverage matters more than the coverage percentage suggests.

**Machine counts only cover part of the chain.** Vanilla has no duration field for crafting
recipes, so workbench steps are reported as manual rather than as a machine count. That is
correct — you do not build a crafting table per craft — but it means a plan for something like
a torch reports machines only for the smelting step. `get_bridge_status` states the coverage
numbers explicitly so the AI can caveat its answer.

**Modded machine recipes are partly covered, and a recipe viewer can only fix part of the
rest.** Inspecting Create's own recipe data (1843 recipes, 15 of its own types) showed what
those recipes look like: inputs are declared declaratively, but `results` is a *list* with
per-entry `count` and `chance`, `processingTime` carries the duration, and fluids appear
alongside items in both directions.

So the mod now ships a Create adapter (soft dependency — no Create installed, no adapter, and
no crash) that reads all four: multiple outputs, per-output probability, fluids both ways, and
the processing time. That turns a Create crushing recipe from "1 of 3 outputs, no probabilities,
no duration" into the real thing — and `processingTime` is what lets Create machines get a real
machine count. **No recipe viewer can supply that**: JEI and EMI expose no concept of duration at
all.

What is still missing, and why it is not a viewer problem either:

- `sequenced_assembly` was the other gap and is now flattened properly. It nests a whole list of
  sub-recipes, so reading it naively would *look* readable while omitting most of the real
  material cost. The adapter reads Create's own source semantics rather than guessing: the pass
  count is `sequence.size() × loops` (so every step's ingredients are consumed `loops` times),
  and the `results` list carries **weights, not probabilities** (`getOutputChance()` is
  `weight / totalWeight`). It also drops the **transitional item** from each step's inputs —
  that item is produced in-line, and leaving it in would put something the player cannot obtain
  into the raw-material list. The total processing time is reported too, which downstream turns
  into a count of parallel assembly lines.
- **Machine names for modded recipes need JEI.** Create does not override `getToastSymbol()`,
  so its recipes get the interface default (`crafting_table`) which we deliberately refuse to
  trust — and there is no reliable way to derive the machine from the recipe type either
  (Create's sandpaper is an *item*, splashing and haunting share one machine, filling/emptying
  split across a spout and a drain, and a sequenced assembly line is several blocks). JEI's
  catalysts are the authoritative answer, which is why JEI is the first viewer to integrate
  rather than EMI.
- Energy is still `null` everywhere.

**Measured on a real Create-focused pack.** A medium pack built around Create
(15,241 recipes, 3,585 tags, 70 recipe types) gave these numbers:

| | |
|---|---|
| Unreadable recipes | 632 / 15,241 (**4%**) |
| Main-thread extraction | **155 ms** (indexing 2 ms) |
| Recipes with a duration | 572 / 15,241 (3.8%) — all of them vanilla cooking types |
| Recipes with a machine | 10,945 / 15,241 (71.8%) |

Two things stand out. The 3.8% duration coverage is why Create machines got no machine
counts: **no modded recipe type carried a duration**, because the duration lives in each
mod's own field and there was no adapter for it. And the 28% without a machine are all
modded machine types — a recipe viewer's catalysts are the only reliable source for those,
which is the concrete reason JEI is worth integrating.

`/snapshot` for this pack is ~10 MB of JSON. The extraction cost scales with recipe count
(155 ms at 15k ≈ 10 µs/recipe), so 50,000 recipes would land near 500 ms — over the 200 ms
budget, and the point where frame-slicing becomes necessary rather than optional.

**Earlier measurements come from a near-vanilla instance** (1,290 recipes), so token figures
in this README are quoted from there unless stated otherwise.

**Supports 1.21.1 (NeoForge) and 1.20.1 (MinecraftForge 47.2.0+).** No Fabric, no other versions.

The 1.20.1 build shares the same algorithms and protocol (`core/`); known differences between
the two are written down in [doc/porting-1.20.1.md](doc/porting-1.20.1.md).

## License

MIT — see [LICENSE](LICENSE).
