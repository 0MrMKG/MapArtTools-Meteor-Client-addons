package com.buildertoolbox.utils;

/** Identity-based ownership: one running movement/container task at a time. */
public final class AutomationLease<T> {
    private T owner;
    public T owner() { return owner; }
    public boolean acquire(T requester) {
        if (requester == null) throw new IllegalArgumentException("Missing task owner");
        if (owner != null && owner != requester) return false;
        owner = requester;
        return true;
    }
    public void release(T requester) { if (owner == requester) owner = null; }
}
