# Builder Toolbox

这是一个专门做地图画绘制的项目，提供 Meteor 插件版和独立 Fabric 版。其中 Meteor 插件版已在 3c3u 服务器内完成测试。

使用方法：[快速上手](QUICKSTART.md)。

## 分支区别

| 分支 | 类型 | 运行依赖与界面 | 适用场景 |
| --- | --- | --- | --- |
| [MapArtTools-Satelles](https://github.com/0MrMKG/MapArtTools-Meteor-Client-addons/tree/MapArtTools-Satelles) | Meteor 插件版主线，原 `master` | 需要安装 Meteor Client，功能集成在 Meteor 的 GUI 中。 | 使用 Meteor 的用户日常使用与主线开发。 |
| [MapArtTools-Satelles-testbuild](https://github.com/0MrMKG/MapArtTools-Meteor-Client-addons/tree/MapArtTools-Satelles-testbuild) | Meteor 插件版实验分支，原 `test-build` | 同样依赖 Meteor Client，用于验证放置、行走等实验性改动。 | 测试新策略、反馈问题；功能和实现可能与主线不同。 |
| [MapArtTools-Solus](https://github.com/0MrMKG/MapArtTools-Meteor-Client-addons/tree/MapArtTools-Solus) | 独立 Fabric 模组，原“无反作弊版本” | 无须安装完整 Meteor Client，内置裁剪后的 GUI 控件，按右 Ctrl 打开小窗口。 | 只需要地图画工具和独立界面的用户。 |

三个分支当前均面向 **Minecraft 1.21.11、Java 21**，需要 Fabric 环境、Fabric API、Litematica 和 MaLiLib。它们分别维护，更新进度和构建产物以对应分支为准。

目前市面上的大部分地图画绘制插件有很多不是自定义的，在建造地图画机的过程中出的一些偏移会使得使用不顺，而且隐藏实现无法定位问题。

本插件的目的是融合这些地图画插件的优点，并且能给用户提供更多的自定义；另外如果玩家有更多定制化的需求，也可以拿这个工程里的skill和源码去喂给ai去实现。（也可以验证没有后门）

**欢迎提PR和提bug。**

2026/11/13 更新的留言：优化铺设速度中


## 0.8.0

本次统一独立版与 Meteor 插件版的版本号，加入深色绿色 UI、X/Z 画布扩展方向按钮，并以补货时直接丢弃多余颜色替代地图画余料箱回存。

完整修改记录：[0.8.0 发布说明](release-notes/0.8.0.txt)。
