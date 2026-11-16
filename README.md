# Slimefun4-CN · Folia 整合版

> 本仓库是基于 [Slimefun4 中文社区版](https://github.com/SlimefunGuguProject/Slimefun4) 的 **Folia 整合分支**，由  
> [LiuyeStudio](https://github.com/LiuyeStudio/Slimefun4) 维护。
>
> **原中文社区版 README 已归档为 [README-UPSTREAM.md](README-UPSTREAM.md)** —— 下载渠道、Wiki、QQ/KOOK 群、  
> 订阅计划、Maven 依赖坐标、社区与免责声明等上游原有说明都在那里。  
> 本文件只回答一个问题：**相对中文社区版，这里改了什么。**

---

## 一、与上游的关系

| 项目         | 说明                                                                                                                                                                                          |
| ---------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 基线         | `SlimefunGuguProject/Slimefun4` 的 `c111c3573`（本地 remote `upstream`）                                                                                                                         |
| 当前仓库       | `LiuyeStudio/Slimefun4`，分支 `master`                                                                                                                                                         |
| Folia 适配来源 | [LiuyeStudio/Slimefun4-Folia](https://github.com/LiuyeStudio/Slimefun4-Folia)（核动豆 NukeBean）+ [Craft233MC/Slimefun4](https://github.com/Craft233MC/Slimefun4) 的 `dev`（neokoni），两条主线合并后逐个解决冲突 |
| 改动规模       | 相对基线约 **127 个文件 / +3,151 / -609 行**                                                                                                                                                         |
| 不变的        | 汉化文本、物品/研究/配方数据、指南、自动更新器、bStats 等中文社区版既有内容一律保留；玩法数值未改                                                                                                                                       |
| 目标服务端      | **Folia 26.3**（同时保留 Paper 兼容路径）                                                                                                                                                             |

> 使用汉化版后请勿到官方问题追踪器提 issue，这条规则与上游一致，详见归档 README。

---

## 二、改动总览

| 类别       | 主要变化                                                                          |
| -------- | ----------------------------------------------------------------------------- |
| Folia 支持 | `folia-supported: true`、Minecraft 26.3 版本常量、全面移除 Bukkit 全局调度器、新增区域/实体安全调度 API |
| 线程归属     | 数据库异步回调按「实体 → 位置 → 全局」派发；修复破坏方块与右键交互时的区域线程异常                                  |
| 存储安全     | SQL 字面量转义、旧物品数据兼容与修复、`loadBlockDataAsync(List)` 回调时机修正                        |
| 功能       | 新增「书 + 粘液球 → 粘液科技指南」配方（可配置）、背包/冷却器等多线程修复                                      |
| 构建       | 固定 `dough-api 1.3.1-folia` 并本地 vendoring、产物改名与日期版本、CodeMC 发布、鬼斩资源站发布工作流       |
| 测试       | 新增 9 个测试类，覆盖 SQL 转义、旧数据修复、玩家任务注册表、聊天输入流程等                                     |

---

## 三、Folia 支持（核心改动）

### 3.1 为什么必须改

Folia 把世界拆成多个**区域线程**，并禁用 Bukkit 全局调度器：  
`Bukkit.getScheduler().runTask / runTaskLater / runTaskTimer / callSyncMethod` 在 26.3 上直接抛  
`UnsupportedOperationException`。此外，在全局区域线程（`region={null}`）上调用  
`Location#getChunk()`、`World#getChunkAt()`、方块或实体读写，会抛  
`Thread failed main thread check`。而 `Bukkit.isPrimaryThread()` 在 Folia 的全局区域线程上会**误判为  
`true`**，导致大量「以为在主线程就直接执行」的防御代码失效。

### 3.2 统一调度抽象

所有任务改走 `Slimefun.getFoliaLib().getScheduler()`（FoliaLib `PlatformScheduler`）：  
`runLater` / `runTimer` / `runTimerAsync` / `runLaterAsync` / `runAtLocationLater`，  
返回值统一为 `WrappedTask`。涉及 `BlockDataController`、`TickerTask`、`AbstractArmorTask`、  
`AsyncRecipeChoiceTask`、`AutoSavingService`、`GitHubService/Task`、`HologramsService`、  
`SQLProfiler`、`EnvironmentChecker`、`ParticleUtil`、`IntegrationsManager`、`ElytraImpactListener` 等。

`Slimefun` 新增一组区域安全 API（供主程序与附属插件使用）：

| 方法                                               | 调度目标                                           |
| ------------------------------------------------ | ---------------------------------------------- |
| `runSync(Runnable[, delay])`                     | 全局同步（Folia 下为全局区域线程，**仅用于不碰方块/实体的纯逻辑**）        |
| `runAtRegion(Location, Runnable[, delay])`       | 位置所属的区域线程                                      |
| `runAtEntity(Entity, Runnable[, delay])`         | 实体所属的区域线程                                      |
| `runSyncAtEntity(Runnable, Entity[, delay])`     | 同上，参数顺序兼容 dev 分支写法                             |
| `runSyncAtLocation(Runnable, Location[, delay])` | 同上                                             |
| `getFoliaLib()`                                  | 直接拿 FoliaLib 实例，用 `isFolia()` 分流 Paper / Folia |

`TaskUtil.runSyncMethod` 不再使用 `callSyncMethod`，改为 `CompletableFuture` + 区域调度；  
`FoliaTaskQueue` 增加 `execute(Plugin, Location)`、`thenRun(Location, …)`、  
`thenRepeatEvery(Location, …)`，让多方块动画（堆肥机、自动淘金机、采矿机等）按区域执行。

### 3.3 数据库回调的区域归属（本轮重点修复）

`ADataController.invokeCallback` 原本对 `runOnMainThread() = true` 的回调一律 `runSync`，  
回调体却在操作方块 —— 破坏「数据尚未加载完」的 Slimefun 方块时会报  
`Thread failed main thread check: Async chunk retrieval`。修复方式：

- `IAsyncReadCallback` 新增 `getRegionAnchor()` / `getEntityAnchor()` 两个可选锚点；
- `ADataController` 按 **实体锚点 → 位置锚点 → 全局** 派发，并新增带 `Location` 的 `invokeCallback` 重载；
- `BlockDataController` 的所有异步读回调都带上锚点（Location、`SlimefunBlockData#getLocation`、  
  `SlimefunUniversalBlockData#getLastPresent`、区块换算出的坐标）；
- `TeleporterListener`、`SlimefunItemInteractListener`、`BackpackCommand`、`ClearDataCommand`、  
  `StorageCacheUtils`、`DebugFishListener` 等回调补齐实体/位置锚点。

### 3.4 同类点位整改

`InventoryUtil` 背包开关按玩家实体调度；`TickerTask` 同步 ticker、`ParticleUtil` 方块高亮、  
`ErrorReport` 带坐标的报告、`HologramsService.removeHologram`、`ItemFilter`、`Reactor` 按位置调度；  
`ElevatorPlate` 传送、`SlimefunBowListener` 箭实体、`BackpackListener` 打开背包按实体调度；  
`ProgrammableAndroid`、`StorageCacheUtils.hasUniversalBlock` 的同步查询改走带 `Location` 的  
`TaskUtil` 重载。所有 `Bukkit.isPrimaryThread()` 判断均在 Folia 下改为显式 `isFolia()` 分流，  
避免误判。

### 3.5 两个已排除的坑（维护请注意）

1. **区域线程自死锁**：`TaskUtil.runSyncMethod` 曾无条件把任务排到同一区域的下一 tick 再  
   `future.get(1s)` 阻塞，而 `PlayerInteractEvent` 本身就跑在该区域线程上，导致  
   `Timeout when executing sync method` → 拆箱 `NullPointerException`。现在  
   `Bukkit.isPrimaryThread()` 为真时**直接同步执行**，只有真正的异步线程才派发并等待；  
   `hasUniversalBlock` 另用 `Boolean.TRUE.equals(...)` 兜底。
2. **构建缓存**：改动 `build.gradle.kts` 后增量 `shadowJar` 可能 `FROM-CACHE` 恢复旧包。  
   验证/部署前请 `./gradlew clean shadowJar test`，并检查 `build/libs/` 下实际文件名与时间戳。

---

## 四、存储与数据安全

- `SqlUtils` 新增 `escapeSqlLiteral`：把 SQL 字符串字面量中的 `'` 转义为 `''`、`\` 转义为 `\\`，  
  避免带引号或反斜杠的键名拼进 SQL 时报错或注入；新增 `SqlUtilsEscapingTest`。
- 新增 `ItemDataRecovery`、`LegacyItemStackDeserializer`、`LegacyEnchantmentDataFixer`，  
  配套调整 `ItemStackDataCodec`、`LegacyItemMetaDeserializer`，用于旧版物品数据/附魔数据的兼容恢复；  
  新增 `PlayerTaskRegistry` 统一管理玩家任务。
- 修正 `BlockDataController.loadBlockDataAsync(List, callback)` 的回调在加载完成**之前**就同步执行的时序 bug。
- 新增 `AbstractPlayerTaskTest`、`PlayerTaskRegistryTest`、`ItemDataRecoveryTest` 等共 9 个测试类。  
  最近一次本地验证（2026-11-15）：`./gradlew clean shadowJar test --offline` → **41 测试 / 0 失败 / 1 项真实数据库测试跳过**。

---

## 五、功能与内容

- **Minecraft 26.3**：`MinecraftVersion` 增加 `MINECRAFT_26_3`，编译基线 Paper-API `1.21.11-R0.1-SNAPSHOT` / Java 21。
- **指南书配方**：新增「书 + 粘液球」合成粘液科技指南（`SlimefunGuideRecipeListener`），  
  由 `config.yml` 的 `guide.crafting` 控制，默认 `true`。
- **dev 分支合并带入的修复**：背包 / 冷却器多线程异常、灵魂绑定物品归还、抓钩改用  
  `teleportAsync`、自动附魔/附魔事件同步、全息投影编辑、  `getNearbyEntities`、GEO 矿工、  
  Vanilla 掉落处理按 Location 执行等。

---

## 六、构建与发布

- **依赖固定**：`dough-api` 固定为 `1.3.1-folia`（含 FoliaLib），jar 与最小 pom 放在  
  `local-deps/io/github/baked-libs/dough-api/1.3.1-folia/`，并把该本地仓库放在 `repositories` **首位**，  
  使构建可离线复现，不再受远端 SNAPSHOT 变动影响。
- **构建脚本**：`group = com.github.slimefunce`；版本默认 `Snapshot-<yyyyMMdd>`（可用  
  `-PprojectVersion=...` 覆盖）；产物为 `build/libs/SlimefunCE-CN-Snapshot-<yyyyMMdd>.jar`；  
  Maven 发布指向 CodeMC，凭证只从环境变量读取（`GRADLE_PROJECT_MAVEN_USERNAME/PASSWORD`，  
  回退 `MAVEN_ACCOUNT/MAVEN_PASSWORD`），不硬编码。
- **CI**：`.github/workflows/publish.yml` 负责自动构建、发 GitHub Release 并发布鬼斩资源站；  
  `dev-ci.yml`、`spotless.yml` 等只做构建 / 格式检查。
- **常用命令**：
  ```bash
  ./gradlew clean shadowJar test --offline --console=plain
  ```

### 6.1 自动发版（GitHub Release + 鬼斩资源站）

- **鬼斩项目**：`SlimefunCE-Folia`，ID `UCBWAU1W`，type `plugin`，当前为草稿（公开页面不可见，  
  带 token 的 API 可访问；草稿默认没有 channel，首次发版自动创建 `release`）。ID 已写为 workflow 默认值，  
  也可用变量 `GZ_PROJECT_ID` 或手动输入 `project_id` 覆盖。
- **触发**：推送 `v*` 标签（版本号 = 标签名），或在 Actions 页手动运行  
  `Build, Release & Publish to Guizhan Resources`，可填 `tag` / `project_id` / `channel` / `changelog`  
  并可关闭 `sync_profile`；手动运行且 `tag` 留空时用 `Snapshot-<yyyyMMdd>`。
- **流程**：JDK 21 + Gradle `shadowJar`（`-PprojectVersion` 使 `plugin.yml` 版本与发布版本一致）→  
  统一产物名 `SlimefunCE-CN.<版本>.jar` 并存为 CI 产物 → **创建 / 更新 GitHub Release**（带同一个 jar，  
  `--generate-notes` 生成说明；已存在的草稿 / 预发布转正式版并替换旧产物）→ **发布鬼斩同名版本**。  
  两边是同一个文件、同一次构建，鬼斩 changelog 直接取 GitHub Release 说明。
- **鬼斩发版**：直接调用 v1 REST API（不依赖第三方 Action），channel 不存在时自动创建；  
  同名版本已存在则跳过上传 jar，只 PATCH 同步兼容性元数据，重复运行不会产生重复版本。
- **完善项目资料**：发版时按 `.github/scripts/guizhan_project_profile.py` 检查草稿项目，  
  只补全空缺的 `summary` / `description`（取本仓库 README）/ `license_type`（GPL-3.0）/  
  `homepage_url` / `source_url` / `issues_url` / `wiki_url` / `tags`（`slimefun`），不覆盖已填内容；  
  需 `project:update` scope，token 无此权限时只告警、不影响发版。
- **配置**：Secret `GZ_TOKEN`（已配置；兼容旧名 `GUIZHAN_TOKEN`），需 `project:version:create` /  
  `project:version:update` / `project:channel:create`，以及资料补全用的 `project:update` scope  
  （缺 `project:update` 只跳过资料补全，不影响发版）。可选 Variable `GZ_PROJECT_ID`、  
  `GZ_CHANNEL`、`GZ_PLATFORMS`、`GZ_MC_VERSIONS`（默认 `release`、`paper` / `folia`、  
  MC `1.21.2 ~ 26.3` 共 15 个鬼斩有效值）。`GITHUB_TOKEN` 由 Actions 自动注入，workflow 用 `contents: write`。  
  未配置 `GZ_TOKEN` 时仍会构建并发布 GitHub Release，只跳过鬼斩。
- **changelog**：手动输入 > GitHub Release 说明 > 指向本次提交的默认文本。
- **注意**：手动触发且 tag 不存在时，`gh release create --target` 会基于本次提交创建同名 tag；  
  鬼斩项目仍是草稿，公开下载需先在鬼斩提交审核并通过。

---

## 七、部署与实机验证

- 部署时把产物复制为服务器里的 `plugins/Slimefun-DEV-SNAPSHOT.jar`（**保持文件名不变**），  
  避免与原文件名插件同时存在导致同名插件被加载两次。
- 已在 Folia 26.3 实机验证：`Enabling Slimefun vSnapshot-20261115` → `Done (16.286s)`，  
  加载 569 个物品 / 258 个研究 / 2,049 个配方；启动日志中  
  `ERROR` / `Exception` / `Async chunk retrieval` / `Thread failed main thread check` /  
  `Timeout when executing sync method` / `Could not pass event` 计数均为 0。
- 方块破坏与右键交互路径需玩家进服触发，启动阶段覆盖不到；改动调度代码后请务必实机走一遍。

---

## 八、已知限制

- `SlimefunChunkData#getChunk()` 里的 `Bukkit.isPrimaryThread()` 守卫在 Folia 全局区域线程仍会误判，  
  属 API 内部防御检查；调用方需自行按区域调度。
- `api/events/*` 中 `super(!Bukkit.isPrimaryThread())` 的异步标记语义在 Folia 下可能不准确  
  （只影响事件元数据，不影响功能），暂未改动以降低风险。
- `PlayerBackpack.getAsync(item, callback, true)` 仍会取到全局 executor，目前**无调用者**；  
  新代码请使用单参数版本并显式按实体/位置调度。
- `HologramOwner#updateHologram` 的直接分支内部已按位置调度，实际安全，但外层判断未在 Folia 下分流。

---

## 九、Folia 适配来源与鸣谢

Folia 支持并非从零实现，而是在以下项目基础上整合、修复而来。按  
[GNU GPLv3](LICENSE) 保留原始归属声明；本分支的修改同样以 GPLv3 发布。

- **[核动豆（NukeBean）](https://github.com/nukebean)** 与  
  **[LiuyeStudio / 流叶科技工作室](https://github.com/LiuyeStudio)**  
  — [LiuyeStudio/Slimefun4-Folia](https://github.com/LiuyeStudio/Slimefun4-Folia) 的区域化调度适配、  
  任务模型重构与持续维护。`FoliaTaskQueue`、`PlayerTaskRegistry`、`ItemDataRecovery`、  
  `LegacyEnchantmentDataFixer`、`LegacyItemStackDeserializer`、`SlimefunGuideRecipeListener`  
  等文件源自该项目，并针对汉化版做了适配修改。
- **[neokoni](https://github.com/neokoni)**（[Craft233MC/Slimefun4](https://github.com/Craft233MC/Slimefun4)）  
  — 另一条 Folia 适配主线，其 `dev` 分支通过 git 合并引入；背包 / 冷却器多线程修复、  
  `runSyncAtEntity` / `runSyncAtLocation` 等区域安全调度来自这里。
- **[cyantangerine (Elvin)](https://github.com/cyantangerine)**  
  — 移植整合、冲突解决、Folia 26.3 兼容性修复、区域归属回调重构与实机验证。

署名仅保留在 README 与源码 Javadoc 中；`plugin.yml` 的 authors 与游戏内致谢文本维持上游原样。  
上游 [Slimefun 4](https://github.com/Slimefun/Slimefun4) 与  
[Slimefun4 汉化版](https://github.com/SlimefunGuguProject/Slimefun4) 的版权声明与许可条款继续有效。

---

## 十、上游原始文档

- **[README-UPSTREAM.md](README-UPSTREAM.md)** — 中文社区版原始 README（下载、Wiki、社区、依赖坐标、免责声明等）
- [Slimefun4 汉化版仓库](https://github.com/SlimefunGuguProject/Slimefun4) / [官方仓库](https://github.com/Slimefun/Slimefun4)
- [非官方中文 Wiki](https://slimefun-wiki.guizhanss.cn/) / [CHANGELOG.md](CHANGELOG.md) / [LICENSE](LICENSE)
