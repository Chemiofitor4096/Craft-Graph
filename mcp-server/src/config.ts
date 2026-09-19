/**
 * 找到游戏内 Mod 暴露的 Bridge 服务。
 *
 * 优先顺序：
 *   1. 环境变量（便于测试和手动指定）
 *   2. 游戏 Mod 写下的发现文件
 *
 * 发现文件的意义：否则用户要在 Mod 配置和 MCP 配置里各填一次端口和 token，
 * 改一个忘一个，然后花半小时 debug 为什么连不上。
 */

import fs from "node:fs";
import os from "node:os";
import path from "node:path";

/** 与 protocol.md 里的 protocolVersion 对应。不匹配时明确报错，而不是发出畸形请求后神秘失败。 */
export const SUPPORTED_PROTOCOL_VERSION = 1;

export const DEFAULT_PORT = 25585;

export interface BridgeLocation {
  host: string;
  port: number;
  token: string | null;
  /** 从哪找到的，用于报错时给用户提示 */
  source: "env-url" | "env-file" | "discovery-file";
  /** discovery-file / env-file 的情况下，文件路径 */
  filePath?: string;
}

interface DiscoveryFile {
  protocolVersion?: number;
  host?: string;
  port?: number;
  token?: string;
  pid?: number;
  startedAt?: string;
}

/**
 * 游戏写下的发现文件路径（备用位置）。
 *
 * Mod 会写两份：
 *   1. 游戏目录下的 craftgraph/bridge.json —— 规范位置，但第三方启动器
 *      （PCL2 / HMCL / Prism）会把游戏装在别处，外面无从得知
 *   2. ~/.craftgraph/bridge.json —— 固定位置，任何启动器都能找到
 *
 * 这里返回第 2 种。第 1 种需要用户用 CRAFTGRAPH_BRIDGE_FILE 指定。
 */
export function userDirDiscoveryPath(): string {
  return path.join(os.homedir(), ".craftgraph", "bridge.json");
}

/**
 * 传统默认位置。仅在用户没配置任何东西时作为最后的兜底，
 * 主要用于「游戏没运行」的报错里给出一个有意义的路径提示。
 */
export function defaultDiscoveryPath(): string {
  const home = os.homedir();
  switch (process.platform) {
    case "win32": {
      const appData = process.env.APPDATA || path.join(home, "AppData", "Roaming");
      return path.join(appData, ".minecraft", "craftgraph", "bridge.json");
    }
    case "darwin":
      return path.join(home, "Library", "Application Support", "minecraft", "craftgraph", "bridge.json");
    default:
      return path.join(home, ".minecraft", "craftgraph", "bridge.json");
  }
}

function readDiscoveryFile(filePath: string): BridgeLocation | null {
  let raw: string;
  try {
    raw = fs.readFileSync(filePath, "utf8");
  } catch {
    return null;
  }

  let parsed: DiscoveryFile;
  try {
    parsed = JSON.parse(raw) as DiscoveryFile;
  } catch (err) {
    throw new Error(`发现文件不是合法 JSON：${filePath}\n${String(err)}`);
  }

  if (typeof parsed.protocolVersion === "number" && parsed.protocolVersion !== SUPPORTED_PROTOCOL_VERSION) {
    throw new Error(
      `协议版本不匹配：Mod 用的是 v${parsed.protocolVersion}，本 MCP Server 支持 v${SUPPORTED_PROTOCOL_VERSION}。\n` +
        `请把 Mod 和 MCP Server 升到同一版本。`,
    );
  }

  const isEnvFile = filePath === process.env.CRAFTGRAPH_BRIDGE_FILE;

  return {
    host: parsed.host ?? "127.0.0.1",
    port: typeof parsed.port === "number" ? parsed.port : DEFAULT_PORT,
    token: parsed.token ?? null,
    source: isEnvFile ? "env-file" : "discovery-file",
    filePath,
  };
}

/**
 * 解析 Bridge 地址。全部方式都失败时返回一个「默认猜测」，
 * 让调用方能给出「游戏没在运行」这种可读的报错，而不是直接崩掉。
 */
export function resolveBridgeLocation(): BridgeLocation {
  // 1. 完整的 URL 覆盖（测试用，也是用户手动指定的兜底手段）
  const envUrl = process.env.CRAFTGRAPH_BRIDGE_URL;
  if (envUrl && envUrl.trim() !== "") {
    let parsed: URL;
    try {
      parsed = new URL(envUrl);
    } catch {
      throw new Error(`CRAFTGRAPH_BRIDGE_URL 不是合法 URL：${envUrl}`);
    }
    return {
      host: parsed.hostname,
      port: parsed.port ? Number(parsed.port) : DEFAULT_PORT,
      token: process.env.CRAFTGRAPH_BRIDGE_TOKEN ?? null,
      source: "env-url",
    };
  }

  // 2. 指定发现文件路径
  const envFile = process.env.CRAFTGRAPH_BRIDGE_FILE;
  if (envFile && envFile.trim() !== "") {
    const loc = readDiscoveryFile(envFile);
    if (loc) return loc;
    throw new Error(`CRAFTGRAPH_BRIDGE_FILE 指向的文件读不到：${envFile}`);
  }

  // 3. 固定位置的副本（任何启动器都会写到这里）
  const userDirPath = userDirDiscoveryPath();
  const fromUserDir = readDiscoveryFile(userDirPath);
  if (fromUserDir) return fromUserDir;

  // 4. 传统默认位置
  const defaultPath = defaultDiscoveryPath();
  const found = readDiscoveryFile(defaultPath);
  if (found) return found;

  // 5. 都没有 —— 给出默认值，让上层报「游戏没在运行」
  return {
    host: "127.0.0.1",
    port: DEFAULT_PORT,
    token: process.env.CRAFTGRAPH_BRIDGE_TOKEN ?? null,
    source: "discovery-file",
    filePath: userDirPath,
  };
}

/** 请求超时。游戏线程被卡住时不应该让 AI 一直等。 */
export const REQUEST_TIMEOUT_MS = Number(process.env.CRAFTGRAPH_TIMEOUT_MS ?? 8000);

/** 拉全量快照时的单页大小。 */
export const SNAPSHOT_PAGE_SIZE = 500;

/** 快照页数上限，防止 Bridge 行为异常时无限循环。 */
export const SNAPSHOT_MAX_PAGES = 400;
