/**
 * MCP Server 版本号。与 mod/gradle.properties 的 mod_version 保持一致 ——
 * 见 index.ts 顶部同样的说明。单独成文件是为了让 explorer 等非入口模块
 * 也能带上版本（它们不能 import index.ts：那个文件在模块顶层就启动了服务）。
 */
export const VERSION = "0.3.4";
