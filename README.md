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
| Minecraft | 1.21.1 |
| Mod loader | NeoForge 21.1.x |
| Side | **Client** — the mod reads from the client, so singleplayer and multiplayer both work |
| Node.js | ≥ 20 (for the MCP server) |

## Install

Two halves, and **you need both** — the jar alone cannot answer anything, because all the
recipe-tree and planning logic lives in the server.

**1. The mod** — download `craftgraph-<version>.jar` from the
[Releases page](https://github.com/Chemiofitor4096/Craft-Graph/releases) and put it in your
mods folder. No release for your version? Build it yourself:

```bash
git clone https://github.com/Chemiofitor4096/Craft-Graph.git
cd Craft-Graph/mod && ./gradlew build
# → mod/build/libs/craftgraph-*.jar  →  put it in .minecraft/mods/
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
| [`doc/protocol.md`](doc/protocol.md) | The bridge HTTP contract — the single source of truth for both halves |
| [`doc/decisions.md`](doc/decisions.md) | Locked technical decisions and what changing them would cost |
| [`doc/development.md`](doc/development.md) | Dev setup, the four test layers, token measurements, lessons learned |
| [`doc/format-evaluation.md`](doc/format-evaluation.md) | Measured evaluation of output formats, including a compact DSL proposal |

## Status

Verified end to end on a real 1.21.1 instance: 1290 recipes indexed, 3% flagged unreadable,
37 ms main-thread extraction, AI client connected with zero configuration.

| | |
|---|---|
| Bridge mod | Works. 104 JUnit tests, none of which need Minecraft |
| MCP server | Works. 41 algorithm tests, MCP protocol tests, cross-language contract tests |
| Recipe coverage | All recipe types are read. 5 of the 7 types present in vanilla are 100% readable |
| Machine & duration data | Every recipe gets a machine; the 112 cooking recipes get a duration. Energy is not exposed by vanilla at all — it stays `null` rather than being invented |
| Modded recipes | Not yet measured — see limitations below |
| EMI integration | Not started (would improve coverage on heavy tech packs) |
| In-game UI | Out of scope for the MVP by design |

## Known limitations

**Unreadable recipes are real, and they are counted.** On vanilla, 40 of 1290 recipes are
flagged `opaque`: 27 smithing recipes and 13 code-driven special recipes (dyeing, map cloning,
fireworks, banner duplication). Run `npm run inspect` to see the breakdown by recipe type for
your own pack.

The smithing 27 are worth knowing about in detail, because they are the ones players ask about
most (netherite gear). `SmithingRecipe` does not override `getIngredients()`, so the inherited
default returns an empty list and there is nothing declarative to read — the three slots are
only reachable through `isTemplateIngredient` / `isBaseIngredient` / `isAdditionIngredient`.
Marking them `opaque` is the honest answer, and the AI is told to say "I cannot read this"
instead of "this needs no materials".

Observed behaviour when asked "how do I make a netherite helmet": the AI did relay "the input
cannot be read, and that does not mean it needs no materials" — then went on to describe the
vanilla recipe from its own training data, explicitly labelled as not coming from the game.
On vanilla that happens to be right. On a modpack, where a KubeJS script or another mod may
have changed it, the same sentence would be confidently wrong. **`opaque` prevents a *silent*
wrong answer; it does not stop the model from filling the gap with memory.** Recovering the
smithing ingredients (iterate the item registry, test the three predicates) is possible but has
not been done — and it is worth more than the raw coverage number suggests.

**Machine counts only cover part of the chain.** Vanilla has no duration field for crafting
recipes, so workbench steps are reported as manual rather than as a machine count. That is
correct — you do not build a crafting table per craft — but it means a plan for something like
a torch reports machines only for the smelting step. `get_bridge_status` states the coverage
numbers explicitly so the AI can caveat its answer.

**Not yet tested against a large tech pack.** Modded machine recipes are the interesting case,
and they are the ones most likely to be `opaque`. If you try it on a big pack, that number
is the thing to look at.

**Vanilla-only measurements.** The performance and token figures above come from a near-vanilla
instance. A pack with 50 000 recipes will behave differently — the snapshot build cost in
particular is expected to grow.

**NeoForge 1.21.1 only.** No Fabric, no other Minecraft versions.

## License

MIT — see [LICENSE](LICENSE).
