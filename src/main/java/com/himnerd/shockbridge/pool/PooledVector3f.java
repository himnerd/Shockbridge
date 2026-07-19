package com.himnerd.shockbridge.pool;

/**
 * Mutable 3-component float vector designed for pool reuse. All fields are public
 * for zero-overhead access in hot paths (trajectory, raycast, hitbox calculations).
 * Callers MUST return instances to the pool after use — never hold references.
 */
public class PooledVector3f {

    private static final ObjectPool<PooledVector3f> POOL =
            new ObjectPool<>(PooledVector3f::new, 256);

    public float x;
    public float y;
    public float z;

    public PooledVector3f() {}

    public static PooledVector3f borrow() {
        return POOL.borrow();
    }

    public static void release(PooledVector3f v) {
        if (v != null) {
            v.x = 0;
            v.y = 0;
            v.z = 0;
            POOL.release(v);
        }
    }

    public PooledVector3f set(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public PooledVector3f set(double x, double y, double z) {
        this.x = (float) x;
        this.y = (float) y;
        this.z = (float) z;
        return this;
    }

    public PooledVector3f add(PooledVector3f other) {
        this.x += other.x;
        this.y += other.y;
        this.z += other.z;
        return this;
    }

    public PooledVector3f subtract(PooledVector3f other) {
        this.x -= other.x;
        this.y -= other.y;
        this.z -= other.z;
        return this;
    }

    public PooledVector3f scale(float s) {
        this.x *= s;
        this.y *= s;
        this.z *= s;
        return this;
    }

    public float length() {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    public float lengthSquared() {
        return x * x + y * y + z * z;
    }

    public PooledVector3f normalize() {
        float len = length();
        if (len > 0.0001f) {
            x /= len;
            y /= len;
            z /= len;
        }
        return this;
    }

    public float dot(PooledVector3f other) {
        return x * other.x + y * other.y + z * other.z;
    }

    public float distanceTo(PooledVector3f other) {
        float dx = x - other.x;
        float dy = y - other.y;
        float dz = z - other.z;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}