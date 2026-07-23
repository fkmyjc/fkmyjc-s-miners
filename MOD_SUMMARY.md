# fkmyjcs_miners 模组总结（架构与 API 速查）

> **用途**：本文件是模组的结构化参考。每次开始修改前先读它，可快速定位相关代码、减少重新梳理成本；
> 每次修改代码后，请同步修订本文件对应段落（尤其是文末「关键引用表」），保持与新代码一致。
>
> **维护约定**
> - 改动某子系统 → 更新对应章节；新增/重命名/删除公共方法 → 更新「关键引用表」。
> - 顶部「最近更新」记录日期与摘要，便于判断文档时效。
> - 本文所有 `文件:行号` 均来自编写时的源码，行号随重构漂移，若发现不符以实际代码为准并顺手修正。
>
> **最近更新**：2026-07-23 — 控制器方块物品 tooltip 现显示多方块包围盒尺寸 `宽×高×深`（X×Y×Z）；新增 `MultiBlockPattern.getSize()`，三个控制器方块 `TestMachineBlock`/`BigTestMachineBlock`/`GiantTestMachineBlock` 重写 `appendHoverText` 经 `getPattern(this)` 取尺寸。另含 2026-07-13 的候选组悬停清单 z=400 提升、id 青色、三库字段显示一致；矿脉查询/修改 API、候选组多方块、AE2 样板导出、探矿/集成层全貌；结构 JSON 支持 `bindDirection`（默认 true，false 时四方向任一成形即成立）与方块 NBT（SNBT 字符串，`writeNbt` 控制建造是否写入）。

---

## 1. 工程基础

| 项 | 值 |
|---|---|
| 加载器 / 版本 | Forge 1.20.1-47.4.21，official-mapped（`gradle.properties:4,10,29,32`） |
| 包名（base package） | `com.fkmyjc.fkmyjcs_miners`（`Fkmyjcs_miners.java:33`，与 `mod_group_id`/`mod_id` 一致） |
| 工程根 | `C:/Users/WDYKM/IdeaProjects/fkmyjcs_miners` |
| 编译 JDK | **JDK 21**（系统 `JAVA_HOME` 常失效，命令显式 `export JAVA_HOME="/c/Program Files/Java/jdk-21.0.10"`） |
| 编译 | `./gradlew compileJava -x test` |
| 打包 | `./gradlew build -x test` → `build/libs/fkmyjcs_miners-1.0-SNAPSHOT.jar` |
| 运行客户端 | `./gradlew runClient` |
| 主类 | `Fkmyjcs_miners`（构造器 `Fkmyjcs_miners.java:36`），`@Mod("fkmyjcs_miners")` |

**启动期装配（`Fkmyjcs_miners.java:36-66`）要点**
- `ModRegistry.register(modBus)`：菜单类型、方块、物品注册。
- `MinecraftForge.EVENT_BUS.addListener(onAddReloadListeners)`：数据包重载时刷新多方块结构 / 矿脉矿石池 / 时运矿石列表（`Fkmyjcs_miners.java:81-87`）。**注意** `AddReloadListenerEvent` 是游戏事件总线事件，挂 modBus 会启动崩溃。
- `onClientSetup`：注册 `VeinScreen` / `VeinMapScreen` 屏幕与右侧探矿叠加层（`Fkmyjcs_miners.java:89-100`）。
- `AutoBuildManager` 与 `ClientProspectOverlay` 通过 `EVENT_BUS.register(...)` 挂游戏总线（非注解扫描，规避时序问题）。
- 网络通道 `ModNetwork.register()`（`Fkmyjcs_miners.java:61`）。

---

## 2. 包结构总览

```
com.fkmyjc.fkmyjcs_miners
├── Fkmyjcs_miners        主类 / 装配
├── Config                Forge 配置（COMMON + CLIENT）
├── ModRegistry           注册表入口
├── block/                TestMachine / Big / Giant 控制器方块
├── item/                 探矿杖、探矿仪、高级探矿仪、StackEnergyStorage
├── menu/ , screen/       矿脉信息 GUI（单区块）、7×7 矿脉地图 GUI
├── vein/                 矿脉系统（核心数据 + 查询/修改 API）
├── chunkdata/            矿脉持久化（按维度 SavedData）
├── multiblock/           多方块结构模型、校验、自动建造
├── prospect/             探矿（道具逻辑、存储、客户端叠加层、网络包）
├── ore/                  矿石列表管理（OreListManager / FortuneMiningListManager）
├── network/              ModNetwork 通道 + 数据包
├── command/              /vein 指令
└── integration/          查看器集成（JEI / REI / EMI / Jade / AE2）
```

---

## 3. 矿脉系统（`vein/` + `chunkdata/`）

### 3.1 数据模型
- **`Vein`**（`Vein.java:28`）：不可变快照（除 `reserves` 可变）。字段 `type / mainOre / secondaryOre / rock / mainW / secW / rockW / reserves`。三权重之和恒为 100（`Vein.java:34-37`）。`reserves` 仅由 `VeinManager` 缓存共享，权威可变来源在 `ChunkVeinData`。
- **`VeinType`**（`VeinType.java:11`）：`SINGLE` / `DOUBLE` / `POVERTY`。生成概率 单20% / 双60% / 贫穷20%（`VeinManager.java:334-339`）。
- **`ChunkVeinData`**（`ChunkVeinData.java:30`）：**某区块矿脉的权威持久化容器**，保存全部可变字段 + `genSalt` + `initialized`。`NBT` 序列化见 `serializeNBT:119` / `deserializeNBT:134`；旧格式仅含 `reserves`（无 `type`）时 `initialized=false` 交由重新生成（`ChunkVeinData.java:136`）。

### 3.2 生成与读取
- `VeinManager.getVein(level, ChunkPos)`（`VeinManager.java:43`）：查缓存 → 否则 `loadOrGenerate`。`loadOrGenerate:56` 在服务端从 `VeinSavedData` 取（未初始化则确定性生成并写回），客户端回退纯确定性生成（不持久化）。
- 生成确定性：种子 = `keyOf(level,cp) ^ 0x9E3779B97F4A7C15L ^ salt`（`VeinManager.java:313`）。**不含版本号**，故已落盘矿脉永不被新代码覆盖。
- 权重模型（单 65±10 / 双 45±10+次20±10 / 贫穷 30±10 / 纯岩石 100%），储量初始值由 `Config.veinReservesBase±veinReservesSpread` 浮动（`VeinManager.java:378-385`）。

### 3.3 按坐标查询 / 修改 API（坐标驱动公共入口）
> 设计对齐 `/vein` 指令，但为编程接口，便于 KubeJS / 其它模组调用。

- **查询**
  - `Vein getVein(Level, BlockPos)`（`VeinManager.java:93`）
  - `VeinReport queryVein(Level, BlockPos)` / `queryVein(Level, int chunkX, int chunkZ)`（`VeinManager.java:102,107`）
  - `VeinReport`（`VeinReport.java:13`）：只读快照，含 `dimension / chunkX / chunkZ / initialized / genSalt` + 完整 `Vein` 字段视图 + 派生判定 `isPureRock()` / `isDouble()` / `hasSecondary()` 与 `mainOreId()` 等 id 字符串。非服务端 `initialized=false`。
- **修改**
  - `ModifyResult modifyVeinAt(Level, BlockPos, VeinEdit)` / `(Level, int, int, VeinEdit)`（`VeinManager.java:137,142`）。**仅服务端维度有效**，否则 `ModifyResult.fail("仅服务端维度可修改矿脉")`。返回 `success + message + 修改后 VeinReport`。
  - `VeinEdit`（`VeinEdit.java:33`）：链式 builder，**全字段可选**。
    - 类型 `.type(0|1|2)`（贫穷/单/双）。
    - 矿石三态：`setXxx(id)` / `clearXxx()`（主矿/次矿清除为 null，岩石清除回退石头）/ 保持；`"*:none"` 等价清除。
    - 权重：`.weights(main,sec[,rock])` 任一非 null 触发**归一化到 100**（等比缩放，`VeinManager.java:251-268`）。
    - 储量：`.reserves(int)` 设值 / `.addReserves(int)` 增量。
    - 重新生成：`.reset()` / `.reset(salt)`（整条随机重抽，忽略其余字段）。
  - 语义细节（`applyEdit:176`）：设次矿且非双→升级双并补默认次矿权重；清除次矿且为双→降级单；矿石经 `VeinOreRegistry.normalizeOreItem` 归一，失败回退 `ForgeRegistries.ITEMS` 直取。
  - 写回链路：落盘 `sd.setDirty()` → 缓存失效 `invalidate` → 仅向**正在查看该区块**的在线玩家重同步叠加层 `syncOverlay`（`VeinManager.java:167-172,294`）。

### 3.4 持久化
- `VeinSavedData`（`VeinSavedData.java:23`）：按维度 `SavedData`，文件 `data/<dim>/fkmyjcs_miners_chunk_veins.dat`。`get(ServerLevel):29`、`getOrCreate(ChunkPos):68`、`forEach(BiConsumer):78`（供 `/vein reset all`）。

---

## 4. 多方块系统（`multiblock/`）

### 4.1 结构模型 `MultiBlockPattern`（`MultiBlockPattern.java:43`）
- 常量：`SKIP=' '`（空格不校验）、`CONTROLLER_DEFAULT='C'`（`MultiBlockPattern.java:45-46`）。
- 字段：`controllerChar`、`layers`（每层一行的字符矩阵）、`mapping: Map<Char,List<String>>`（字符→候选列表）、控制器坐标 `cLayer/cRow/cCol`、`id`（结构稳定标识）、`name`（显示名）（`MultiBlockPattern.java:48-53`）。
- **候选组（2026-07-13 新增）**：`mapping` 的 value 支持**字符串或字符串数组**；数组 = 一组可互换方块，世界方块落在任一个即算成形（`parseExpected:103`，`blockMatches:153` 遍历候选，`matches:118` 调用）。
- 代表方块：预览 / 自动建造 / 材料清单均取候选组**第一个** `cand.get(0)`（`Mapping` 单值即长度 1 的候选组）。

### 4.2 关键方法
| 方法 | 行 | 说明 |
|---|---|---|
| `fromJson(JsonObject)` | `:68` | 由数据包 JSON 构造（解析 id/name，缺省空串） |
| `getId()` / `getName()` / `getDisplayName()` | `:292/:297/:305` | 结构标识 / 显示名 / 优先 name 回退 id 的展示名（供后续 JEI 显示） |
| `matches(level, controllerPos, facing)` | `:118` | 校验世界结构是否成形（候选组任一匹配即通过） |
| `getControllerBlock()` | `:178` | 代表控制器方块（候选组取第一个） |
| `getRenderBlocks()` | `:206` | 预览方块列表（代表方块） |
| `getMaterialGroups()` | `:232` | 材料组 `MaterialGroup(char, count, List<ItemStack> alternatives)`，alternatives 已带正确数量 |
| `candidateCountAt(layer,row,col)` | `:317` | 该格候选数（>1 即多选） |
| `candidateStatesAt(layer,row,col)` | `:334` | 该格所有可解析候选方块状态 |
| `computeWorldPlacements(controllerPos, facing)` | `:368` | 返回 `BlockPlacement(worldPos, state, cost, source)` 供自动建造 |
| `getSize()` | `:366`（新增） | 包围盒尺寸 `{宽,高,深}`（X×Y×Z），由所有非空格已映射格的局部坐标求最小/最大；供控制器物品 tooltip 显示 |

### 4.2.5 2D 示意图渲染器 `MultiblockSchematicRenderer`（`integration/MultiblockSchematicRenderer.java:34`）
- 用 vanilla `renderItem` 把每格方块画成图标网格；候选组每 1 秒轮播图标并加金色角标。
- **悬停清单**：鼠标悬停候选组格子时自绘半透明清单（`drawCandidateTooltip:107`）；渲染时提升到 `z=400`（`gui.pose().translate(0,0,400)`），避免被 JEI/REI/EMI 原生槽位遮挡。清单位置左右夹紧、顶部行改画在格子下方以避让控制器槽。

### 4.3 控制器物品 tooltip（2026-07-23 新增）
- 三个控制器方块 `TestMachineBlock` / `BigTestMachineBlock` / `GiantTestMachineBlock` 均重写 `appendHoverText`，经 `MultiBlockRegistry.getPattern(this)` 取对应 `MultiBlockPattern`，若有则追加一行尺寸 `宽×高×深`（X×Y×Z），文案键 `tooltip.fkmyjcs_miners.multiblock.size`（zh_cn：`大小：%d×%d×%d（宽×高×深）`）。
- 尺寸由 `MultiBlockPattern.getSize()` 计算：扫描所有非空格、已映射字符格，按局部坐标 `(x-cCol, y-cLayer, z-cRow)` 求包围盒跨度。旋转只交换 X/Z 数值集、不影响乘积，故标注「宽×高×深」无歧义。
- `controller`：控制器字符（如 `"C"`）。
- `id`（可选）：结构稳定标识，建议命名空间形式（如 `"fkmyjcs_miners:test_machine"`），供后续 JEI 显示 / 外部反查；缺省空串。
- `name`（可选）：结构显示名（如 `"测试机器"`），供后续 JEI 等展示；缺省空串，回退取 `id`。
- `layers[0]` = 最底层；**最后一层 = 顶层（控制器层）**；所有层共用外框尺寸，控制器用 `mapping.C` 反查。
- `mapping`：字符 → 字符串（单值）或字符串数组（候选组）；值可为 `namespace:block_id` 或 `#forge:tag`。
- 现有结构：`test_machine`(2层) / `big_test_machine`(7→5→3→1 倒金字塔) / `giant_test_machine`(15→13→11 台阶金字塔, 515 方块) / `candidate_test_machine`(候选组测试)。
- 候选组示例（`candidate_test_machine.json`）：`"M": ["#forge:storage_blocks/gold","#forge:storage_blocks/diamond","minecraft:emerald_block"]`。

### 4.4 自动建造 `AutoBuildManager`（`AutoBuildManager.java:51`）
- 触发：玩家手持 `Config.autoBuildTriggerItem`（默认 `minecraft:stick`）右键控制器 → `startBuild:68`。
- 服务端 tick 推进 `onServerTick:95`；玩家离线/换维度取消任务。
- 速度：创造模式 `creativeDurationTicks`（默认 10 tick=0.5s）均分放置；生存模式 `survivalBlocksPerSecond`（默认 4），结构 >240 块自动加速使总耗时 ≈ 60s × `survivalTimeMultiplier`（默认 1.0）（`budgetSurvival:156`）。
- 放置规则：不替换已有固体方块，流体与空气处放置；精确扣除用逐槽 `shrink`（`consumeItems:208`，因 `Inventory.removeItem` 删整堆不可用）；tag 映射接受该 tag 下任意物品（`slotMatches:234`）。

---

## 5. 探矿系统（`prospect/`）

- **道具**：`ProspectWandItem`（64 耐久，右键探当前区块，损耗 1，写入玩家 `persistentData`）、`ProspectDeviceItem`（FE/RF 设备，`StackEnergyStorage`，容量 `Config.prospectEnergyCapacity` 默认 1,000,000，每次 `energyPerUse` 默认 500）、`AdvancedProspectDeviceItem`（默认 100M 容量，开图/探区块计费）。
- **标签**：带 `#fkmyjcs_miners:vein_display` 的物品（手持时右侧叠加层常显）。
- **存储 `ProspectStore`**（`ProspectStore.java:24`）：把每区块 `VeinSummary` 写入玩家 `persistentData`（键 `fkmyjcs_miners:prospect`，按区块 `toLong()` 存），`prospect(player,chunk[,announce]):32/40`、`sendAll:87`（登录同步）、`hasChunk:106`。
- **网络**：`ModNetwork.CHANNEL`（`ModNetwork.java:20`）注册 `ProspectSyncPacket`(id 0) 与 `OverlayTogglePacket`(id 1)；`ProspectStore.prospect` 走 delta 同步，客户端 `ClientProspectMirror` 合并。
- **叠加层 `ClientProspectOverlay`**（`RenderGuiEvent.Post`）：右侧显示本区块矿脉信息；默认快捷键 `\` 切换显示/隐藏，另含上下移动键（`Fkmyjcs_miners.java:52-56` 注册 `RegisterKeyMappingsEvent`，1.20.1 用此法而非已废弃的 `ClientRegistry`）。
- **Jade 联动**：`@WailaPlugin("fkmyjcs_miners")`，看向控制器显示「已成形/未成形」。

---

## 6. 集成层（`integration/`）

### 6.1 通用范式（可选集成，缺失零崩溃）
- 依赖：`compileOnly fg.deobf(...)` + `runtimeOnly fg.deobf(...)`（仅 compile 期链接，不打包进 jar；`build.gradle:174-242`）。
- 声明：在 `mods.toml` 加 `mandatory=false, side=CLIENT` 的依赖段（`mods.toml:65-112`）。
- 发现机制：JEI `@JeiPlugin` / REI `@REIPlugin` / EMI `@EmiEntrypoint` 由对应 mod 扫描，本 mod 缺失时不加载插件类 → 永不崩溃。
- **AE2 特例**：为把 AE2 类引用隔离，JEI 插件本身 `不 import` 任何 AE2 类型，仅在 `ModList.get().isLoaded("ae2")` 守卫下调用 `Ae2JeiTransfer.register`（见下）。

### 6.2 JEI 多方块预览
- `FkmyjcsJeiPlugin`（`FkmyjcsJeiPlugin.java:23`，`@JeiPlugin`）：`registerCategories` → `MultiblockPreviewCategory`；`registerRecipes` 由 `IntegrationSupport.getPreviewTargets` 生成每层一条 `MultiblockPreviewRecipe`（分页，每层一 recipe）；`registerRecipeTransferHandlers:44`（见 6.4）。
- `MultiblockPreviewCategory`（`MultiblockPreviewCategory.java:24`）：`TYPE = RecipeType.create("fkmyjcs_miners","multiblock_preview",...)`（`MultiblockPreviewCategory.java:25`）。`setRecipe:63` 控制器→OUTPUT 槽(右上角)，材料→INPUT 原生槽（每候选组一个槽，JEI 约 1s 轮播候选项）；`draw:83` **字段显示（name 白字 / id 灰字，来自 `pattern.getDisplayName()/getId()`）置于顶部并按 `WIDTH-12` 自动换行（防溢出）**，示意图(`drawLayer:105`)与页码(`Lx/y`)下移至字段下方(`top:102`)，避免与字段重叠（`Font.split`+`drawString(FormattedCharSequence)`）。
- `IntegrationSupport`（`IntegrationSupport.java:23`）：共享数据来源，`CATEGORY_KEY:25`、`getPreviewTargets:44`、`controllerItem:56`、`materials:63`、`materialStacks:68`。

### 6.3 REI / EMI / Jade
- `integration/rei/`：`FkmyjcsReiPlugin`、`MultiblockPreviewCategory`、`MultiblockPreviewDisplay`（同 JEI 思路）。**字段显示（name/id 顶部、自动换行、示意图/页码下移）与 JEI 一致**，在 `rei/MultiblockPreviewCategory.java` 的匿名 Widget `render(5参)` 内（`Font.split`+`b.x/b.y` 相对坐标）。
- `integration/emi/`：`FkmyjcsEmiPlugin` + `MultiblockPreviewRecipe`。**字段显示与 JEI 一致**，在 `emi/MultiblockPreviewRecipe.java` 的匿名 Widget `render` 内（`Font.split`+绝对坐标，宽度用 `getDisplayWidth()`）。
- `integration/jade/`：`FkmyjcsJadePlugin` + `MultiblockFormationProvider`（控制器成形状态）。
- **约定**：三库（JEI/REI/EMI）预览布局保持一致，改动预览时三处需同步。

### 6.4 AE2 样板导出（2026-07-13 新增）
- **需求**：在 AE2 的 ME 样板编码终端中，按 JEI「+」按钮把多方块结构材料写入处理样板；候选为列表时选**第一个**。
- 注册：`FkmyjcsJeiPlugin.registerRecipeTransferHandlers:44` → `if (ModList.get().isLoaded("ae2")) Ae2JeiTransfer.register(registration)`（`FkmyjcsJeiPlugin.java:47`）。
- `Ae2JeiTransfer.register`（`Ae2JeiTransfer.java:19`）：`registration.addRecipeTransferHandler(new Ae2PatternTransfer(helper), MultiblockPreviewCategory.TYPE)`。所有 AE2 引用隔离于此与 `Ae2PatternTransfer`。
- `Ae2PatternTransfer`（`Ae2PatternTransfer.java:37`，实现 `IRecipeTransferHandler<PatternEncodingTermMenu, MultiblockPreviewRecipe>`）：`transferRecipe:65` 遍历 `IntegrationSupport.materials(pattern)`，每组取 `alternatives().get(0)`（第一个候选，数量已带）构造**单元素** `GenericStack` 输入列表（强制 AE2 不挑选）→ 控制器作输出 → `EncodingHelper.encodeProcessingRecipe(menu, inputs, outputs):105`。材料 >9 或为空返回对应提示。
- 依赖坐标：`appeng:appliedenergistics2-forge:15.4.10`（`gradle.properties:75`，仓库 `https://modmaven.dev` `build.gradle:155,242`）。

---

## 7. 配置项（`Config.java`）
| 路径 | 字段 | 默认 | 说明 |
|---|---|---|---|
| `autoBuild.triggerItem` | `autoBuildTriggerItem` | `minecraft:stick` | 触发自动建造的物品 |
| `autoBuild.survivalBlocksPerSecond` | `autoBuildSurvivalRate` | 4 | 小结构(<240块)生存模式每秒方块数 |
| `autoBuild.creativeDurationTicks` | `autoBuildCreativeTicks` | 10 (=0.5s) | 创造模式总 tick |
| `autoBuild.survivalTimeMultiplier` | `autoBuildSurvivalTimeMultiplier` | 1.0 | 大结构(>240)目标耗时=60s×此值 |
| `prospect.energyCapacity` | `prospectEnergyCapacity` | 1,000,000 | 探矿仪 FE 容量 |
| `prospect.energyPerUse` | `prospectEnergyPerUse` | 500 | 探矿仪每次耗 FE |
| `prospect.advancedEnergyCapacity` | `advancedProspectEnergyCapacity` | 100,000,000 | 高级探矿仪 FE 容量 |
| `prospect.advancedEnergyPerOpen` | `advancedProspectEnergyPerOpen` | 1000 | 开 7×7 地图固定耗 FE |
| `prospect.advancedEnergyPerChunk` | `advancedProspectEnergyPerChunk` | 800 | 每探明一新区块耗 FE |
| `vein.reservesBase` | `veinReservesBase` | 200000 | 储量基准 |
| `vein.reservesSpread` | `veinReservesSpread` | 50000 | 储量浮动范围 |
| `vein.endMinerals` | `veinEndMinerals` | false | 末地是否生成矿物（否则纯岩石） |
| `vein.overworldDims / netherDims / endDims` | `veinOverworldDims` 等 | — | 各维度矿石池维度列表 |

---

## 8. 关键引用表（速查，行号随重构漂移）

| 关注点 | 文件:行 |
|---|---|
| 主类装配 | `Fkmyjcs_miners.java:36` |
| 矿脉读取入口 | `vein/VeinManager.java:43` |
| 矿脉按坐标查询 | `vein/VeinManager.java:93,102,107` |
| 矿脉按坐标修改 | `vein/VeinManager.java:137,142`（实现 `:146`）、`applyEdit:176` |
| 修改参数容器 | `vein/VeinEdit.java:33`（builder `:70-160`） |
| 查询快照 | `vein/VeinReport.java:13` |
| 修改结果 | `vein/ModifyResult.java:9` |
| 区块矿脉权威数据 | `chunkdata/ChunkVeinData.java:30` |
| 维度级持久化 | `chunkdata/VeinSavedData.java:23`（`getOrCreate:68`） |
| 结构模型 / 候选组 / id·name | `multiblock/MultiBlockPattern.java:43`（`id/name:52-53`、`fromJson:68`、`parseExpected:103`、`matches:118`、`getId/getName/getDisplayName:292,297,305`） |
| 材料组 | `multiblock/MultiBlockPattern.java:199,232` |
| 自动建造入口 | `multiblock/AutoBuildManager.java:68`（tick `:95`、扣材料 `:208`） |
| 探矿存储 | `prospect/ProspectStore.java:24`（`prospect:32`、`sendAll:87`） |
| 网络通道 | `network/ModNetwork.java:20`（`register:29`） |
| JEI 插件 | `integration/jei/FkmyjcsJeiPlugin.java:23`（转移注册 `:44`） |
| 预览分类 | `integration/jei/MultiblockPreviewCategory.java:25,62` |
| 共享数据 | `integration/IntegrationSupport.java:23` |
| AE2 转移处理 | `integration/ae2/Ae2PatternTransfer.java:37,65,105` |
| AE2 转移注册 | `integration/ae2/Ae2JeiTransfer.java:19` |
| 构建依赖-AE2 | `build.gradle:155,242`；`gradle.properties:75`；`mods.toml:107` |
| 指令 | `command/VeinCommand.java` |

---

## 9. Forge / 工具链 已知坑（本映射实测）

- `ChunkPos` 在 `net.minecraft.world.level`（非 `core` 包）。
- `Item.use` 返回 `InteractionResultHolder<ItemStack>`（非 `InteractionResult`）。
- `LazyOptional` 在 `net.minecraftforge.common.util`（非 `capabilities` 子包）。
- `PacketDistributor.PLAYER` 无 `sole()`；用 `.with(() -> player)`。
- CurseMaven：裸 `maven { url = 'https://www.cursemaven.com' }` + `fg.deobf` + `compileOnly`/`runtimeOnly`。
- `Inventory.removeItem(ItemStack)` 删整个匹配堆 → 精确扣除用逐槽 `shrink`。
- 1.20.1 注册客户端快捷键：`modBus.addListener(EventPriority.NORMAL, false, RegisterKeyMappingsEvent.class, e -> e.register(KEY))`；`ClientRegistry` 已不存在。
- AE2 集成类必须**隔离**在 `ModList.isLoaded("ae2")` 守卫之后加载，否则缺类 `NoClassDefFoundError`。
- 编译用 **JDK 21**（系统 `JAVA_HOME` 易失效）；Glob 工具对绝对路径不生效，取目录用 `find`。
