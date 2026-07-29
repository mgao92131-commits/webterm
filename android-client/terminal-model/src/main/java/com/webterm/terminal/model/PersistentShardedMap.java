package com.webterm.terminal.model;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** 固定分片的不可变 Map；Editor 只复制发生变化的 shard。 */
final class PersistentShardedMap<K, V> {
  private static final int SHARD_COUNT = 64;
  private final Map<K, V>[] shards;
  private final int size;

  @SuppressWarnings("unchecked")
  PersistentShardedMap() {
    shards = (Map<K, V>[]) new Map<?, ?>[SHARD_COUNT];
    for (int i = 0; i < shards.length; i++) shards[i] = Collections.emptyMap();
    size = 0;
  }

  private PersistentShardedMap(Map<K, V>[] shards, int size) {
    this.shards = shards;
    this.size = size;
  }

  V get(K key) { return shards[shard(key)].get(key); }
  boolean containsKey(K key) { return shards[shard(key)].containsKey(key); }
  int size() { return size; }

  Set<K> keySet() {
    Set<K> result = new HashSet<>(size);
    for (Map<K, V> shard : shards) result.addAll(shard.keySet());
    return Collections.unmodifiableSet(result);
  }

  Editor<K, V> edit() { return new Editor<>(this); }

  private static int shard(Object key) {
    int hash = key == null ? 0 : key.hashCode();
    hash ^= hash >>> 16;
    return hash & (SHARD_COUNT - 1);
  }

  static final class Editor<K, V> {
    private final Map<K, V>[] shards;
    private final boolean[] copied = new boolean[SHARD_COUNT];
    private int size;

    private Editor(PersistentShardedMap<K, V> source) {
      shards = source.shards.clone();
      size = source.size;
    }

    V get(K key) { return shards[shard(key)].get(key); }
    boolean containsKey(K key) { return shards[shard(key)].containsKey(key); }

    V put(K key, V value) {
      Map<K, V> shard = mutableShard(shard(key));
      V previous = shard.put(key, value);
      if (previous == null) size++;
      return previous;
    }

    V putIfAbsent(K key, V value) {
      V previous = get(key);
      if (previous == null) put(key, value);
      return previous;
    }

    V remove(K key) {
      int index = shard(key);
      if (!shards[index].containsKey(key)) return null;
      V previous = mutableShard(index).remove(key);
      size--;
      return previous;
    }

    PersistentShardedMap<K, V> commit() {
      Map<K, V>[] next = shards.clone();
      for (int i = 0; i < next.length; i++) {
        if (copied[i]) next[i] = Collections.unmodifiableMap(next[i]);
      }
      return new PersistentShardedMap<>(next, size);
    }

    private Map<K, V> mutableShard(int index) {
      if (!copied[index]) {
        shards[index] = new HashMap<>(shards[index]);
        copied[index] = true;
      }
      return shards[index];
    }
  }
}
