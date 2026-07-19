package com.himnerd.shockbridge.pool;

import java.util.function.Supplier;

/**
 * Thread-local, lock-free object pool that eliminates GC pressure for rapidly
 * recycled math objects (vectors, raycasts, hitboxes). Each thread maintains its
 * own ring buffer of pooled instances — zero contention under high player loads.
 */
public class ObjectPool<T> {

    private final ThreadLocal<Object[]> pool;
    private final ThreadLocal<int[]> index;
    private final Supplier<T> factory;
    private final int capacity;

    public ObjectPool(Supplier<T> factory, int capacityPerThread) {
        this.factory = factory;
        this.capacity = capacityPerThread;
        this.pool = ThreadLocal.withInitial(() -> {
            Object[] arr = new Object[capacityPerThread];
            for (int i = 0; i < capacityPerThread; i++) {
                arr[i] = factory.get();
            }
            return arr;
        });
        this.index = ThreadLocal.withInitial(() -> new int[]{0});
    }

    @SuppressWarnings("unchecked")
    public T borrow() {
        Object[] arr = pool.get();
        int[] idx = index.get();
        T obj = (T) arr[idx[0]];
        if (obj == null) {
            obj = factory.get();
        }
        arr[idx[0]] = null;
        idx[0] = (idx[0] + 1) % capacity;
        return obj;
    }

    public void release(T obj) {
        if (obj == null) return;
        Object[] arr = pool.get();
        int[] idx = index.get();
        int slot = (idx[0] - 1 + capacity) % capacity;
        if (arr[slot] == null) {
            arr[slot] = obj;
        }
    }

    public int getCapacity() {
        return capacity;
    }
}