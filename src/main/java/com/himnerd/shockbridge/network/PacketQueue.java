package com.himnerd.shockbridge.network;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Lock-free Multi-Producer Single-Consumer queue bridging asynchronous
 * RakNet IO threads to Paper's main game loop with zero lock contention.
 * Backed by JDK's ConcurrentLinkedQueue (lock-free, wait-free MPMC queue).
 */
public class PacketQueue {

    private final Queue<TranslatedPacket> queue = new ConcurrentLinkedQueue<>();

    /**
     * Offers a packet to the queue. Always returns true (unbounded).
     */
    public boolean offer(TranslatedPacket packet) {
        return queue.offer(packet);
    }

    /**
     * Drains up to maxPerTick packets from the queue and feeds them to the consumer.
     */
    public void drain(Consumer<TranslatedPacket> consumer, int maxPerTick) {
        TranslatedPacket packet;
        int processed = 0;
        while (processed < maxPerTick && (packet = queue.poll()) != null) {
            consumer.accept(packet);
            processed++;
        }
    }

    public int size() {
        return queue.size();
    }

    public boolean isEmpty() {
        return queue.isEmpty();
    }
}