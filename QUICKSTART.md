# Builder Toolbox 快速上手

## 1. 安装

使用 Minecraft **1.21.11 + Java 21 + Fabric**，在当前游戏实例的 `mods` 文件夹放入本插件及对应版本的 **Meteor Client、Fabric API、Litematica、MaLiLib**。

进入游戏，打开 Meteor 菜单 → **Builder Toolbox**。右键模块打开设置，左键开启或关闭；两个地图画模式任选一个使用。

## 2. Map Art Maker（Once）：铺一张投影

1. 用 Litematica 加载并启用平面投影，准备好可行走的底板。
2. 在材料箱上挂物品展示框，放入该箱供应的材料，例如红色地毯。
3. 设置中开启 **Select Supply Chests**，右键逐个登记材料箱，按 **Esc** 返回；核对 **Supply Chest List**。
4. 站到施工高度：**脚所在的那一格就是铺设层**。开启模块，即可自动蛇形铺设、补货和补漏。

## 3. Map Art Maker（Full Mode）：批量全自动

1. 把单层 **128×128、相对原点 Y=0** 的 `.litematic` 文件放入 `schematics` 下的同一文件夹。
2. 在 **Schematic Folder** 输入文件夹相对路径，支持中文和粘贴。例如 `schematics/地图画/风景` 对应填写 `地图画/风景`。
3. 按上述方法登记颜色材料箱，并依次设置以下位置。每次点击选择按钮后，**右键目标方块，Esc 返回**。

| 选择按钮 | 点击什么 |
| --- | --- |
| Set Canvas Origin | 画布原点下方的底板；记录点击位置的 Y+1，X/Z 不变 |
| Set Clear Lever | 控制放水、收水的拉杆 |
| Set Map Supply | 混放空地图和普通玻璃板的原料箱，无需标签 |
| Set Cartography Table | 制图台 |
| Set Product Storage | 存放锁定地图的成品箱，无需标签 |

4. 画布须与原版地图网格对齐，并留出通往各工作点的道路；程序不会自动移动画布。
5. 首次设置完成，点击 **Validate / Reinitialize** 检查目录和文件数量，然后开启模块。

自动流程：**按文件名顺序读图 → 拉杆清理画布 → 备货、蛇形铺设 → 补漏达标 → 中央激活地图 → 制图台锁定 → 成品入箱 → 下一张**。

## 4. 常用设置与续作

- **垃圾箱（可选）**：用 **Select Overflow Chests** 登记，可添加多个，无需展示框。不设置就跳过回存；设置后每张开工先回存多余材料，之后按 **Restock Trips Per Cleanup** 清理，默认每完成 3 趟补货，在下一趟前清理。
- **Allowed Missing Blocks**：Full Mode 允许的漏放数，默认 **0**；错放只标红，不自动拆除。
- **Reverse Snake**：从另一端反向蛇形施工。
- **Color Filter Enabled**：开启后，在颜色表勾选不想铺设的颜色。
- **Auto Eat**：默认开启，请在背包放食物，并为地图和玻璃板留出空间。
- **暂停/恢复**：关闭模块停止。Full Mode 保存批次，重进游戏后重新开启即可续作；支持 Esc 菜单及失焦后台运行。
- **重新开始**：设置 **Reset Start Map**（从 1 开始，包含该张），再使用 **Reset Batch** 或 **Validate / Reinitialize**。这会重建批次，选中的地图会重新清理、制作；正常续作不要点它们。

其它工具：**脚下搭路、整理箱子、自动合成**。自动合成目前支持骨头 → 骨粉 → 骨块。
