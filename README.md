# fkmyjcs_miners

> Minecraft 1.20.1 (Forge 47.4.21) 模组 —— 以「矿脉」为核心的探矿与多方块采矿体系。

[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-blue.svg)](LICENSE)

## 简介

fkmyjcs_miners 围绕"矿脉"概念，提供从探矿到采矿的一整套玩法：

- **矿脉系统**：每一区块确定性生成一条矿脉（主矿 / 次矿 / 岩石，带权重与储量），支持按坐标查询与修改。
- **探矿工具**：探矿杖（耐久消耗）、探矿仪 / 高级探矿仪（FE/RF 供电）探针区块矿脉，右侧 HUD 常驻显示当前区块信息。
- **多方块结构**：支持带 NBT、候选组（任一可互换方块）、方向绑定 / 四向遍历的结构；手持物品右键控制器即可自动建造。
- **物品管理器集成**：在 JEI / REI / EMI 中分层预览多方块结构（尺寸、材料清单、名称与 id），Jade 显示控制器成形状态，AE2 支持一键将结构材料导出为样板。
- **（规划中）** 按矿脉挖掘、虚空采矿、地心采矿、多种矿机、多样化插件升级。

## 环境

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.4.21 |
| Java | 17（构建工具链）/ 21（开发运行已验证） |
| 映射 | official 1.20.1 |

## 构建

```bash
# 克隆后，使用 Gradle 包装器构建
./gradlew build -x test
# 产物位于 build/libs/*.jar
```

> 首次构建会下载 Minecraft 与依赖并解映射，耗时数分钟。需要联网。

## 可选集成（软依赖，缺失仍可运行）

以下模组为**可选**集成，缺失时本模组仍可正常运行：

- **JEI** (15.20.0+) · **REI** (12.1.0+) · **EMI** (1.1.0+) —— 多方块结构预览
- **Jade** (11.0.0+) —— 看向控制器显示「已成形 / 未成形」
- **AE2** (15.0.0+) —— JEI 样板编辑器「+」键一键导出多方块材料

## 数据结构与配置

- **矿脉数据**：服务端持久化（`VeinSavedData`），按区块确定性生成。
- **多方块结构**：`data/<modid>/multiblock/*.json`，支持 `bindDirection`、`nbt`、`writeNbt`、候选组（数组写法）。
- **探矿配置**：`prospect.energyCapacity`、`prospect.energyPerUse` 等（见 `Config`）。
- **自动建造**：`creativeDurationTicks`、`survivalBlocksPerSecond`、`survivalTimeMultiplier` 等。

## 开发与署名

- **项目主导**：用户 **fkmyjc** —— 负责需求定义、方向决策与最终验收，并持有本项目版权。
- **辅助开发**：**workbuddy-Hy3** —— 负责代码生成、构建验证与文档辅助。
- **workbuddy-Hy3 的性质**：workbuddy-Hy3 是用户 fkmyjc 使用的 AI 辅助开发工具（WorkBuddy 平台智能体）。它在本项目中仅承担代码编写、编译验证与文档生成等辅助性工作，**不构成法律意义上的作者或版权人**，项目版权完全归 fkmyjc 所有。

## 开源协议

本项目以 [CC BY-NC 4.0](LICENSE)（署名—非商业性使用 4.0 国际）许可发布。**未经版权人书面授权，不得将本项目及其任何衍生作品用于商业用途**（包括但不限于售卖、付费分发、商业性服务、嵌入商业产品等）。商业使用须事先获得版权人 fkmyjc 的许可。集成依赖（JEI / REI / EMI / Jade / AE2）均为可选，其各自协议不约束本仓库代码。


