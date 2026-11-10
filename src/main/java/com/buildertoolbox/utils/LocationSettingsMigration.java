package com.buildertoolbox.utils;

import java.util.HashSet;
import java.util.Set;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;

/** Preserve registered coordinates when old Supplies/Batch/Storage settings move into Locations. */
public final class LocationSettingsMigration {
    private LocationSettingsMigration() { }

    public static NbtCompound apply(NbtCompound original, SettingGroup locations) {
        NbtCompound result = original.copy();
        if (!(result.get("settings") instanceof NbtCompound settings)) return result;
        NbtList groups = settings.getListOrEmpty("groups");
        NbtCompound destination = null;
        for (NbtElement element : groups) {
            if (element instanceof NbtCompound group && locations.name.equals(group.getString("name", ""))) {
                destination = group;
                break;
            }
        }
        if (destination == null) {
            destination = new NbtCompound();
            destination.putString("name", locations.name);
            destination.put("settings", new NbtList());
            groups.add(destination);
            settings.put("groups", groups);
        }
        NbtList entries = destination.getListOrEmpty("settings");
        Set<String> wanted = new HashSet<>(), present = new HashSet<>();
        for (Setting<?> setting : locations) wanted.add(setting.name);
        for (NbtElement element : entries) {
            if (element instanceof NbtCompound setting) present.add(setting.getString("name", ""));
        }
        for (NbtElement element : groups) {
            if (!(element instanceof NbtCompound group) || group == destination) continue;
            for (NbtElement entry : group.getListOrEmpty("settings")) {
                if (!(entry instanceof NbtCompound setting)) continue;
                String name = setting.getString("name", "");
                if (wanted.contains(name) && present.add(name)) entries.add(setting.copy());
            }
        }
        destination.put("settings", entries);
        return result;
    }
}
