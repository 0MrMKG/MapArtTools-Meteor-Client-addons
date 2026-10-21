package com.buildertoolbox.utils;

import meteordevelopment.meteorclient.systems.modules.Module;

/** Shared lease for tasks that walk and own container sessions. */
public final class AutomationControl {
    private static final AutomationLease<Module> LEASE = new AutomationLease<>();
    private AutomationControl() { }
    public static boolean acquire(Module requester) {
        Module owner = LEASE.owner();
        if (owner != null && !owner.isActive()) LEASE.release(owner);
        if (LEASE.acquire(requester)) return true;
        requester.warning("自动任务 %s 正在运行；请先关闭它，再开启当前功能。", LEASE.owner().title);
        return false;
    }
    public static void release(Module requester) { LEASE.release(requester); }
}
