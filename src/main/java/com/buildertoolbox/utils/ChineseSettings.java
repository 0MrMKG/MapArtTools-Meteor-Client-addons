package com.buildertoolbox.utils;

import com.buildertoolbox.BuilderToolboxAddon;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import com.buildertoolbox.client.settings.Setting;
import com.buildertoolbox.client.settings.SettingGroup;
import com.buildertoolbox.client.systems.modules.Module;

/** Chinese display labels with the original Meteor profile keys preserved. */
public final class ChineseSettings {
    private static final Map<SettingGroup, String> GROUPS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<String, String> TITLES = Map.ofEntries(
        Map.entry("Auto Eat", "自动进食"),
        Map.entry("Color Filter Enabled", "启用颜色筛选"),
        Map.entry("Excluded Colors", "排除的颜色"),
        Map.entry("Select Supply Chests", "选择补给箱"),
        Map.entry("Supply Chest List", "补给箱列表"),
        Map.entry("Auto Band Width", "自动设置条带宽度"),
        Map.entry("Scan Band Width", "条带宽度"),
        Map.entry("Reverse Snake", "反向蛇形铺设"),
        Map.entry("Place Range", "放置距离"),
        Map.entry("Placement Speed", "放置速度"),
        Map.entry("Status Messages", "显示进度消息"),
        Map.entry("Route Retries", "寻路重试次数"),
        Map.entry("Walk Speed (%)", "行走速度（百分比）"),
        Map.entry("Sprint To Supply", "补货途中疾跑"),
        Map.entry("Path Search Limit", "寻路搜索上限"),
        Map.entry("Stuck Timeout", "卡住判定时间"),
        Map.entry("Schematic Folder", "蓝图文件夹"),
        Map.entry("Allowed Missing Blocks", "允许漏放格数"),
        Map.entry("Canvas Origin", "画布原点"),
        Map.entry("Set Canvas Origin", "选择画布原点"),
        Map.entry("Canvas X Direction", "X 轴扩展方向"),
        Map.entry("Canvas Z Direction", "Z 轴扩展方向"),
        Map.entry("Clear Lever", "清理拉杆位置"),
        Map.entry("Set Clear Lever", "选择清理拉杆"),
        Map.entry("Map Supply", "地图原料箱位置"),
        Map.entry("Set Map Supply", "选择地图原料箱"),
        Map.entry("Cartography Table", "制图台位置"),
        Map.entry("Set Cartography Table", "选择制图台"),
        Map.entry("Product Storage", "成品箱位置"),
        Map.entry("Set Product Storage", "选择成品箱"),
        Map.entry("Reset Start Map", "重建起始地图序号"),
        Map.entry("Reset Batch", "重建批次"),
        Map.entry("Select Storage Chests", "选择分类箱"),
        Map.entry("Storage Chest List", "分类箱列表"),
        Map.entry("Select Overflow Chest", "选择杂物箱"),
        Map.entry("Overflow Chest", "杂物箱位置"),
        Map.entry("Resume Sorting", "继续整理"),
        Map.entry("Select Storage Blocks", "选择原料容器"),
        Map.entry("Storage Block List", "原料容器列表"),
        Map.entry("Select Crafting Table", "选择工作台"),
        Map.entry("Crafting Table Position", "工作台位置"),
        Map.entry("Work Dimension", "工作维度"),
        Map.entry("Start / Resume", "开始或继续")
    );

    private ChineseSettings() { }

    public static void apply(Module module) {
        try {
            Field title = Setting.class.getDeclaredField("title");
            title.setAccessible(true);
            for (SettingGroup group : module.settings) {
                GROUPS.put(group, switch (group.name) {
                    case "General" -> "常规设置";
                    case "Supplies" -> "补货设置";
                    case "Navigation" -> "行走与寻路";
                    case "Full Auto Batch" -> "全自动批次";
                    case "Storage" -> "箱子设置";
                    case "Locations" -> "位置设置";
                    default -> group.name;
                });
                for (Setting<?> setting : group) {
                    String translated = TITLES.get(setting.name);
                    if (translated != null) title.set(setting, translated);
                }
            }
        } catch (ReflectiveOperationException exception) {
            BuilderToolboxAddon.LOG.error("Could not localize settings for {}", module.name, exception);
        }
    }

    public static String groupTitle(SettingGroup group) {
        return GROUPS.getOrDefault(group, group.name);
    }

    public static String colorTitle(String id) {
        return switch (id) {
            case "white" -> "白色";
            case "orange" -> "橙色";
            case "magenta" -> "品红色";
            case "light_blue" -> "淡蓝色";
            case "yellow" -> "黄色";
            case "lime" -> "黄绿色";
            case "pink" -> "粉红色";
            case "gray" -> "灰色";
            case "light_gray" -> "淡灰色";
            case "cyan" -> "青色";
            case "purple" -> "紫色";
            case "blue" -> "蓝色";
            case "brown" -> "棕色";
            case "green" -> "绿色";
            case "red" -> "红色";
            case "black" -> "黑色";
            default -> id;
        };
    }
}
