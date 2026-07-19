package com.himnerd.shockbridge.api;

import lombok.Builder;
import lombok.Getter;

import java.util.UUID;

/**
 * Read-only snapshot of a Bedrock player's session information.
 * Returned by API query methods — safe to hold references to.
 */
@Getter
@Builder
public class BedrockPlayer {

    /** Unique session ID assigned by Shockbridge for this connection */
    private final UUID sessionId;

    /** Xbox Live gamertag (display name) */
    private final String gamertag;

    /** Xbox User ID — the permanent Bedrock account identifier */
    private final String xuid;

    /** Bedrock protocol version the client connected with */
    private final int protocolVersion;

    /** Whether the client passed Xbox Live authentication */
    private final boolean authenticated;

    /** The linked Java UUID, or null if the account is not linked */
    private final UUID linkedJavaUuid;

    /** The linked Java account name, or null if not linked */
    private final String linkedJavaName;

    /** Whether this session is currently connected */
    private final boolean connected;
}