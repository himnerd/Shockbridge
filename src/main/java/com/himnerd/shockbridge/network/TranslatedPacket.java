package com.himnerd.shockbridge.network;

import java.util.UUID;

/**
 * Immutable carrier passed through the MPSC queue from IO threads to the main game loop.
 * Contains the pre-translated Java protocol data ready for injection.
 */
public record TranslatedPacket(UUID sessionId, int javaPacketId, byte[] payload, long translationNanos) {}