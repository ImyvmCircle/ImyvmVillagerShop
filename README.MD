# Imyvm Villager Shop

[![Lisense](https://img.shields.io/github/license/Dream-Rainy/ImyvmVillagerShop?style=for-the-badge)](https://github.com/Dream-Rainy/ImyvmVillagerShop/blob/1.19/LICENSE)
![Build](https://img.shields.io/github/actions/workflow/status/Dream-Rainy/ImyvmVillagerShop/build.yml?style=for-the-badge)

一个基于 Fabric 的村民商店模组（Minecraft 1.21，JDK 21）。

已知不兼容模组: [OffersHUD](https://github.com/naari3/offers-hud)

> 所有命令均支持短别名 `/vlsp`，例如 `/vlsp create`。

## 快速开始

### 1) 构建与打包

```bash
./gradlew build
./gradlew shadowJar
```

- 构建产物在 `build/libs/`。

### 2) 本地运行服务端

```bash
./gradlew runServer
```

### 3) 进入游戏后最常用操作

```text
/villagerShop
/villagerShop create
/villagerShop manager
/villagerShop admin
```

- `villagerShop` 无参数会直接打开创建 GUI。
- `create` 打开商店创建 GUI。
- `manager` 打开你自己的商店列表与管理 GUI。
- `admin` 为仅 CLI 的管理员命令分组。

## 玩家使用指南

### 创建商店（推荐 GUI）

使用 `/villagerShop create` 后，按界面流程操作：

1. 选择商店类型（玩家商店/管理员商店）。
2. 输入商店名。
3. 调整坐标（支持 X/Y/Z 轴 +/-1、+/-5）。
4. 添加商品（背包、潜影箱、潜影贝、末影箱）。
5. 提交创建。

> 每个商店最多 7 个商品，同一玩家下商店名不能重复。

### 管理商店（推荐 GUI）

```text
/villagerShop manager
/villagerShop manager <shopName>
```

- 支持改名、移动位置、商品增删改、删除商店。
- 店名包含空格或中文时可直接输入；也可继续使用双引号习惯写法。

### 命令速查（直观版）

> 无参数打开 GUI：`/villagerShop`、`/villagerShop create`、`/villagerShop manager`、`/villagerShop manager stock`、`/villagerShop manager item`、`/villagerShop manager rename`、`/villagerShop manager move`、`/villagerShop manager info`、`/villagerShop manager delete`

> 直觉分组用法：`/villagerShop manager <...>`（GUI/商店管理），`/villagerShop admin <...>`（仅 CLI 管理）。

#### 补货

| 你想做什么 | 推荐命令（店名放最后） | 兼容旧命令 |
|------|------|------|
| 用主手补满 | `/villagerShop manager stock hand <shopName>` | `/villagerShop manager stock <shopName>` |
| 用主手补指定数量 | `/villagerShop manager stock hand <amount> <shopName>` | `/villagerShop manager stock <shopName> <amount>` |
| 用指定物品补满 | `/villagerShop manager stock item <item> <shopName>` | `/villagerShop manager stock <shopName> <item>` |
| 用指定物品补指定数量 | `/villagerShop manager stock item <item> <amount> <shopName>` | `/villagerShop manager stock <shopName> <item> <amount>` |

补货税计算: `价格 * 数量 * 税率`（税率默认 `0.01`）。

#### 商品管理（命令）

| 你想做什么 | 推荐命令（店名放最后） | 兼容旧命令 |
|------|------|------|
| 添加商品 | `/villagerShop manager item add <item> <qty> <price> to <shopName>` | `/villagerShop manager item <shopName> add <item> <qty> <price>` |
| 修改数量/价格 | `/villagerShop manager item change <item> <qty> <price> in <shopName>` | `/villagerShop manager item <shopName> change <item> <qty> <price>` |
| 删除主手商品 | `/villagerShop manager item remove from <shopName>` | `/villagerShop manager item <shopName> remove`[^1] |
| 删除指定商品 | `/villagerShop manager item remove <item> from <shopName>` | `/villagerShop manager item <shopName> remove <item>`[^1] |

#### 商店设置（命令）

| 你想做什么 | 推荐命令（店名放最后） | 兼容旧命令 |
|------|------|------|
| 查看商店信息 | `/villagerShop manager info <shopName或id>` | 同左 |
| 重命名商店 | `/villagerShop manager rename <newName> of <shopName>` | `/villagerShop manager rename <shopName> <newName>` |
| 移动商店 | `/villagerShop manager move <x y z> of <shopName>` | `/villagerShop manager move <shopName> <x y z>` |
| 删除自己商店 | `/villagerShop manager delete <shopName>` | 同左[^1] |

## 管理员命令

| 你想做什么 | 命令 |
|------|------|
| 调整补货税率 | `/villagerShop admin tax <rate>` |
| 重载配置 | `/villagerShop admin reload` |
| 重新生成商店实体 | `/villagerShop admin respawn <id>` |
| 搜索商店 | `/villagerShop admin search <条件>` |
| 设置为管理员商店 | `/villagerShop admin setAdmin <id>`[^1] |
| 通过 ID 删除任意商店 | `/villagerShop manager delete <id>`[^1] |

> 管理员删除商店或执行 `setAdmin` 不会返还库存给玩家。

## 配置文件

配置文件名: `config/Imyvm_VillagerShop.conf`

```yaml
core {
  language="zh_cn"
  tax {
    restock=0.01
  }
  database {
    database=H2
    url="localhost:5432/imyvmvillagershop"
    username=root
    password=""
    maximumPoolSize=10
  }
}
```

### 配置项说明

| 配置项 | 说明 |
|------|------|
| `core.language` | 语言（如 `zh_cn`、`en_us`） |
| `core.tax.restock` | 玩家补货税率 |
| `core.database.database` | `H2` / `MYSQL` / `POSTGRESQL` |
| `core.database.url` | 数据库连接地址 |
| `core.database.username` | 数据库用户名 |
| `core.database.password` | 数据库密码 |
| `core.database.maximumPoolSize` | 连接池大小（当前仅 MySQL 生效） |

## 高风险操作确认机制

以下操作执行前需要确认：命令行 `create submit`、删除商店、删除商品、`setAdmin`。

```text
/villagerShop confirm
/villagerShop cancel
```

- 触发危险操作后会自动弹出确认 GUI（可直接点按钮确认/取消）。
- 只有在存在待确认操作时，`confirm` / `cancel` 才会出现在命令补全中。
- 待确认操作 60 秒后自动取消。
- 在确认/取消前，不能发起新的待确认操作。

## 备注

- 创建费用由代码按“当前拥有商店数量”动态计算（见 `ShopEntity.checkPlayerMoney`）。
- 推荐优先使用 GUI，命令主要用于批量或高级管理。

## TODO

- [x] 商店创建或物品添加时即消耗背包内的物品补货
- [x] 删除商店或商品时返还库存
- [x] 补货时可以从玩家身上的潜影箱及末影箱中获取所需物品
- [x] 系统商店限制单个玩家购买数量
- [x] 支持范围搜索商店，以及通过玩家名称搜索商店，不输出重复结果
- [x] 完成商店的实体创建
- [x] 完成 GUI 呈现以及操作监听
- [x] 支持保存物品 NBT / 组件数据
- [x] GUI 可视化创建流程（含坐标微调、容器物品选取）
- [x] GUI 可视化管理流程（改名、移位、商品增删改、删除商店）
- [x] 右键自己的商店村民直接打开管理界面
- [x] 在 `<item>` 参数为空的情况下直接使用主手物品
- [ ] 将创建费用计算公式独立进配置文件

[^1]: 该操作需要使用 `/villagerShop confirm` 确认执行，或使用 `/villagerShop cancel` 取消。所有操作在 60 秒后自动取消，且在确认已有操作之前无法发起新的需确认操作。
