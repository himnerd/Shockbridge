package com.himnerd.shockbridge.identity;

import com.himnerd.shockbridge.debug.AlphaDebugLogger;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.EllipticCurve;
import java.security.spec.ECFieldFp;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ForkJoinPool;

/**
 * Validates Bedrock client identity chains against Microsoft's Xbox Live PKI.
 * Each Bedrock Login packet contains a chain of JWTs (ES384 / P-384) that
 * cryptographically prove the player owns the claimed Xbox Live profile.
 *
 * Chain structure:
 *   JWT[0] — signed by Mojang root key, contains intermediate public key
 *   JWT[1] — signed by intermediate key, contains identity public key
 *   JWT[2] — signed by identity key, contains extraData {XUID, displayName, identity}
 *
 * If the first JWT's x5u header does NOT match the Mojang root key, the chain
 * is self-signed (offline mode) and authentication fails.
 */
public class MicrosoftAuthValidator {

    // Mojang/Xbox root signing key for Bedrock (P-384 / secp384r1)
    private static final String MOJANG_ROOT_KEY =
            "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAE8ELkixyLcwlZryUQcu1TvPOmI2B7vX83ndnWRUaXm74wFfa5f/lwQNTfrLVHa2PmenpGI6JhIMUJaWZrjmMj90NoKNFSNBuKdm8rYiXsfaz3K36x/1U26HpG0ZxK/V1V";

    private final AlphaDebugLogger debugLogger;

    public MicrosoftAuthValidator(AlphaDebugLogger debugLogger) {
        this.debugLogger = debugLogger;
    }

    public CompletableFuture<IdentityChainResult> validate(String chainJson, String clientDataJwt) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return performValidation(chainJson, clientDataJwt);
            } catch (Exception e) {
                debugLogger.logException("MicrosoftAuth", e);
                return IdentityChainResult.builder()
                        .authenticated(false)
                        .failureReason("Validation error: " + e.getMessage())
                        .build();
            }
        }, ForkJoinPool.commonPool());
    }

    private IdentityChainResult performValidation(String chainJson, String clientDataJwt) throws Exception {
        // Parse the chain array from {"chain":["jwt1","jwt2","jwt3"]}
        String[] tokens = extractChainTokens(chainJson);
        if (tokens == null || tokens.length == 0) {
            return IdentityChainResult.builder()
                    .authenticated(false)
                    .failureReason("Empty or malformed identity chain")
                    .build();
        }

        boolean chainAuthenticated = true;
        String lastIdentityPublicKey = null;
        String xuid = null;
        String displayName = null;
        String identityUuid = null;
        String clientPublicKey = null;

        for (int i = 0; i < tokens.length; i++) {
            String[] parts = tokens[i].split("\\.");
            if (parts.length != 3) {
                return IdentityChainResult.builder()
                        .authenticated(false)
                        .failureReason("Malformed JWT at index " + i)
                        .build();
            }

            String headerJson = decodeBase64Url(parts[0]);
            String payloadJson = decodeBase64Url(parts[1]);
            byte[] signature = Base64.getUrlDecoder().decode(padBase64(parts[2]));

            // Extract x5u (signer public key) from header
            String x5u = extractJsonString(headerJson, "x5u");
            if (x5u == null) {
                chainAuthenticated = false;
                continue;
            }

            // First token: x5u must match Mojang root key for authenticated chain
            if (i == 0) {
                if (!MOJANG_ROOT_KEY.equals(x5u)) {
                    chainAuthenticated = false;
                    debugLogger.log("Auth: first JWT x5u does not match Mojang root key — offline chain");
                }
            } else if (lastIdentityPublicKey != null) {
                // Subsequent tokens must be signed by previous token's identityPublicKey
                if (!lastIdentityPublicKey.equals(x5u)) {
                    chainAuthenticated = false;
                    debugLogger.log("Auth: chain break at JWT " + i + " — key mismatch");
                }
            }

            // Verify signature
            if (chainAuthenticated) {
                try {
                    PublicKey signerKey = decodeECPublicKey(x5u);
                    String signedData = parts[0] + "." + parts[1];
                    if (!verifyES384(signerKey, signedData.getBytes(), signature)) {
                        chainAuthenticated = false;
                        debugLogger.log("Auth: signature verification failed at JWT " + i);
                    }
                } catch (Exception e) {
                    chainAuthenticated = false;
                    debugLogger.log("Auth: crypto error at JWT " + i + ": " + e.getMessage());
                }
            }

            // Extract identityPublicKey for chaining
            String identityPk = extractJsonString(payloadJson, "identityPublicKey");
            if (identityPk != null) {
                lastIdentityPublicKey = identityPk;
                clientPublicKey = identityPk;
            }

            // Extract identity claims from extraData in the last token
            String extraData = extractJsonObject(payloadJson, "extraData");
            if (extraData != null) {
                xuid = extractJsonString(extraData, "XUID");
                displayName = extractJsonString(extraData, "displayName");
                identityUuid = extractJsonString(extraData, "identity");
            }
        }

        // Parse client data JWT for device info (no signature verification needed)
        String deviceOs = "Unknown";
        int deviceOsId = 0;
        String deviceModel = "Unknown";
        String gameVersion = "Unknown";
        if (clientDataJwt != null) {
            try {
                String[] cdParts = clientDataJwt.split("\\.");
                if (cdParts.length >= 2) {
                    String cdPayload = decodeBase64Url(cdParts[1]);
                    deviceOs = extractJsonString(cdPayload, "DeviceOS");
                    String osIdStr = extractJsonString(cdPayload, "DeviceOS");
                    deviceOsId = parseDeviceOsId(cdPayload);
                    deviceModel = extractJsonString(cdPayload, "DeviceModel");
                    gameVersion = extractJsonString(cdPayload, "GameVersion");
                    deviceOs = mapDeviceOs(deviceOsId);
                }
            } catch (Exception e) {
                debugLogger.log("Auth: failed to parse client data JWT: " + e.getMessage());
            }
        }

        return IdentityChainResult.builder()
                .authenticated(chainAuthenticated)
                .xuid(xuid)
                .displayName(displayName)
                .identityUuid(identityUuid)
                .clientPublicKey(clientPublicKey)
                .deviceOs(deviceOs)
                .deviceOsId(deviceOsId)
                .deviceModel(deviceModel != null ? deviceModel : "Unknown")
                .gameVersion(gameVersion != null ? gameVersion : "Unknown")
                .failureReason(chainAuthenticated ? null : "Chain signature validation failed")
                .build();
    }

    // ── JWT parsing helpers ─────────────────────────────────

    private String[] extractChainTokens(String chainJson) {
        // Parse {"chain":["jwt1","jwt2","jwt3"]}
        int idx = chainJson.indexOf("\"chain\"");
        if (idx < 0) return null;

        int arrStart = chainJson.indexOf('[', idx);
        int arrEnd = chainJson.lastIndexOf(']');
        if (arrStart < 0 || arrEnd <= arrStart) return null;

        String arrContent = chainJson.substring(arrStart + 1, arrEnd);
        // Split by "," being careful about JWT content (JWTs don't contain quotes)
        String[] raw = arrContent.split(",");
        String[] tokens = new String[raw.length];
        for (int i = 0; i < raw.length; i++) {
            tokens[i] = raw[i].trim();
            if (tokens[i].startsWith("\"")) tokens[i] = tokens[i].substring(1);
            if (tokens[i].endsWith("\"")) tokens[i] = tokens[i].substring(0, tokens[i].length() - 1);
        }
        return tokens;
    }

    private static String decodeBase64Url(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(padBase64(base64Url)));
    }

    private static String padBase64(String s) {
        int pad = (4 - s.length() % 4) % 4;
        return s + "=".repeat(pad);
    }

    private static String extractJsonString(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;

        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        int end = json.indexOf('"', start + 1);
        if (end < 0) return null;

        return json.substring(start + 1, end);
    }

    private static String extractJsonObject(String json, String key) {
        String search = "\"" + key + "\"";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        int braceStart = json.indexOf('{', idx + search.length());
        if (braceStart < 0) return null;

        int depth = 0;
        for (int i = braceStart; i < json.length(); i++) {
            if (json.charAt(i) == '{') depth++;
            else if (json.charAt(i) == '}') {
                depth--;
                if (depth == 0) return json.substring(braceStart, i + 1);
            }
        }
        return null;
    }

    private static int parseDeviceOsId(String json) {
        String search = "\"DeviceOS\"";
        int idx = json.indexOf(search);
        if (idx < 0) return 0;
        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return 0;
        StringBuilder num = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isDigit(c)) num.append(c);
            else if (num.length() > 0) break;
        }
        return num.length() > 0 ? Integer.parseInt(num.toString()) : 0;
    }

    private static String mapDeviceOs(int id) {
        return switch (id) {
            case 1 -> "Android";
            case 2 -> "iOS";
            case 3 -> "macOS";
            case 4 -> "FireOS";
            case 5 -> "GearVR";
            case 6 -> "HoloLens";
            case 7 -> "Windows 10";
            case 8 -> "Windows 32";
            case 9 -> "Dedicated";
            case 10 -> "tvOS";
            case 11 -> "PlayStation";
            case 12 -> "Nintendo Switch";
            case 13 -> "Xbox One";
            case 14 -> "Windows Phone";
            case 15 -> "Linux";
            default -> "Unknown (" + id + ")";
        };
    }

    // ── ECDSA P-384 verification ────────────────────────────

    private static PublicKey decodeECPublicKey(String base64Key) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(padBase64(base64Key));

        // Parse SubjectPublicKeyInfo DER structure for EC P-384
        // The key bytes are a DER-encoded SubjectPublicKeyInfo
        KeyFactory kf = KeyFactory.getInstance("EC");

        // Use X.509 encoded key spec directly
        java.security.spec.X509EncodedKeySpec spec =
                new java.security.spec.X509EncodedKeySpec(keyBytes);
        return kf.generatePublic(spec);
    }

    private static boolean verifyES384(PublicKey publicKey, byte[] data, byte[] rawSignature) throws Exception {
        // Convert raw R||S signature (96 bytes) to DER format
        byte[] derSignature = rawToDer(rawSignature);

        Signature sig = Signature.getInstance("SHA384withECDSA");
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(derSignature);
    }

    private static byte[] rawToDer(byte[] raw) {
        int half = raw.length / 2;
        byte[] r = trimLeadingZeros(Arrays.copyOfRange(raw, 0, half));
        byte[] s = trimLeadingZeros(Arrays.copyOfRange(raw, half, raw.length));

        // Add leading zero if high bit set (DER integer encoding)
        if (r.length > 0 && (r[0] & 0x80) != 0) {
            byte[] tmp = new byte[r.length + 1];
            System.arraycopy(r, 0, tmp, 1, r.length);
            r = tmp;
        }
        if (s.length > 0 && (s[0] & 0x80) != 0) {
            byte[] tmp = new byte[s.length + 1];
            System.arraycopy(s, 0, tmp, 1, s.length);
            s = tmp;
        }

        // SEQUENCE { INTEGER r, INTEGER s }
        int seqLen = 2 + r.length + 2 + s.length;
        byte[] der = new byte[2 + seqLen];
        int idx = 0;
        der[idx++] = 0x30; // SEQUENCE
        der[idx++] = (byte) seqLen;
        der[idx++] = 0x02; // INTEGER
        der[idx++] = (byte) r.length;
        System.arraycopy(r, 0, der, idx, r.length);
        idx += r.length;
        der[idx++] = 0x02; // INTEGER
        der[idx++] = (byte) s.length;
        System.arraycopy(s, 0, der, idx, s.length);
        return der;
    }

    private static byte[] trimLeadingZeros(byte[] bytes) {
        int start = 0;
        while (start < bytes.length - 1 && bytes[start] == 0) start++;
        return start == 0 ? bytes : Arrays.copyOfRange(bytes, start, bytes.length);
    }
}