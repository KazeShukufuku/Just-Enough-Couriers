# Just Enough Couriers

一个面向 Minecraft 1.20.1 Forge 的轻量兼容模组。

本模组的目标很单一：
- 让 JEI 的“配方转移/一键放入”能力可用于 CM Package Couriers 的便携式仓库管理器（Portable Stock Ticker）界面；
- 将 JEI 里选中的配方转成看板可下单的可合成请求，减少手动输入。

## 功能概览

- 在 JEI 中查看配方时，可通过通用转移流程把输入配方导入便携式仓库管理器逻辑。
- 自动检查当前库存快照是否缺料，并给出缺失提示。
- 避免重复添加同一配方请求。
- 支持普通转移与最大转移（按结果物品最大堆叠数下单）。

## 实现原理（核心流程）

本项目实现非常聚焦，主要由三个类构成：

1. `JustEnoughCouriers`
- Forge 入口类，仅负责注册模组主 ID。

2. `JecJeiPlugin`
- 通过 `@JeiPlugin` 暴露 JEI 插件。
- 在 `registerRecipeTransferHandlers` 中注册 `PortableStockTickerTransferHandler` 为通用配方转移处理器。

3. `PortableStockTickerTransferHandler`
- 对 JEI 发起的配方转移请求进行完整处理：
  1) 仅处理 `Recipe<?>`，且限制在客户端逻辑执行；
  2) 校验当前菜单/界面是否为便携式仓库管理器，限制最多 9 个原料槽；
  3) 校验是否已经在下单列表中，避免重复请求；
  4) 读取看板的库存快照，构造临时配方容器；
  5) 借助 Create Factory Abstractions 的 `IngredientTransfer` 计算需要的转移操作；
  6) 若缺料，返回 JEI 用户错误并展示缺料信息；
  7) 若是预检（`doTransfer = false`）则仅返回检查结果；
  8) 真正转移时，把配方结果封装为可合成请求，加入看板下单队列并触发请求提交。

简化后可理解为：
- JEI 负责“我想做什么配方”；
- 本模组负责“把这个配方变成 Portable Stock Ticker 可执行的下单请求，并进行库存与状态校验”。

## 依赖与环境

- Minecraft: 1.20.1
- Forge: 47.x
- Java: 17
- 关键依赖（编译期）：
  - Create
  - CM Package Couriers
  - JEI
  - Create Factory Abstractions

## 构建

在项目根目录执行：

```bash
./gradlew build
```

Windows 可使用：

```bat
gradlew.bat build
```

产物会输出到构建目录（由 Gradle/ForgeGradle 管理）。

## 许可证

本项目使用 MIT License，详见 `LICENSE`。
