package com.buildertoolbox.utils;

public final class AutomationLeaseTest {
    public static void main(String[] args) {
        AutomationLease<Object> lease = new AutomationLease<>();
        Object printing = new Object(), sorting = new Object();
        check(lease.acquire(printing), "initial task acquires control");
        check(lease.acquire(printing), "selection/resume by the owner is reentrant");
        check(!lease.acquire(sorting), "another module cannot take movement or containers");
        lease.release(sorting);
        check(lease.owner() == printing, "rejected module shutdown cannot release the running owner");
        lease.release(printing);
        check(lease.acquire(sorting), "next task may start after shutdown");
        lease.release(printing);
        check(lease.owner() == sorting, "late previous-owner cleanup cannot release a newer task");
        System.out.println("AutomationLease: ownership, reentry, conflicting activation and cleanup ordering passed.");
    }
    private static void check(boolean value,String message) { if (!value) throw new AssertionError(message); }
}
