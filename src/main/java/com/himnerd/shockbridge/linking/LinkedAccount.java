package com.himnerd.shockbridge.linking;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Instant;
import java.util.UUID;

/**
 * Represents a verified link between a Bedrock XUID and a premium Java account.
 * Persisted to YAML in the plugin data folder.
 */
@Getter
@AllArgsConstructor
public class LinkedAccount {

    private final String xuid;
    private final UUID javaUuid;
    private final String javaName;
    private final Instant linkedAt;
}