package com.himnerd.shockbridge.identity;

import lombok.Builder;
import lombok.Getter;

/**
 * Result of Microsoft identity chain validation. Contains the extracted player
 * identity from the Xbox Live JWT chain, plus cryptographic verification status.
 */
@Getter
@Builder
public class IdentityChainResult {

    private final boolean authenticated;
    private final String xuid;
    private final String displayName;
    private final String identityUuid;
    private final String clientPublicKey;
    private final String failureReason;

    // Client device info extracted from client data JWT
    private final String deviceOs;
    private final int deviceOsId;
    private final String deviceModel;
    private final String gameVersion;
}