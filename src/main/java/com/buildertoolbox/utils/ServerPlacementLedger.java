package com.buildertoolbox.utils;

import java.util.concurrent.ConcurrentHashMap;

/** Keeps server observations separate from the client's predicted block placements. */
public final class ServerPlacementLedger<K,V> {
    private final ConcurrentHashMap<K,V> states = new ConcurrentHashMap<>();
    public void watch(K key,V initialState) { states.putIfAbsent(key,initialState); }
    public void acknowledge(K key,V serverState) { states.computeIfPresent(key,(ignored,old)->serverState); }
    public V observedOr(K key,V worldState) { return states.getOrDefault(key,worldState); }
    public void invalidateWhere(java.util.function.Predicate<K> predicate) { states.keySet().removeIf(predicate); }
    public void clear() { states.clear(); }
}
