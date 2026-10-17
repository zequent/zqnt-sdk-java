package com.zqnt.sdk.edge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Verifies the service token the platform puts on every call into the adapter. Contract (must
 * match core's {@code ServiceTokens}): compact JWS, {@code alg=EdDSA} (Ed25519),
 * {@code iss=zqnt-service}, {@code aud=zqnt-edge}, {@code scope} contains {@code platform},
 * {@code exp} in the future. The header's {@code kid} picks the key from {@link PlatformKeySet}.
 */
public class PlatformTokenVerifier {

    public static final String ISSUER = "zqnt-service";
    public static final String AUDIENCE_EDGE = "zqnt-edge";
    public static final String SCOPE_PLATFORM = "platform";
    static final Duration CLOCK_SKEW = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlatformKeySet keys;

    /** @param publicKey the platform's Ed25519 public key, base64 DER (SubjectPublicKeyInfo) or PEM */
    public PlatformTokenVerifier(String publicKey) {
        this(new PlatformKeySet(publicKey, null));
    }

    public PlatformTokenVerifier(PlatformKeySet keys) {
        this.keys = keys;
    }

    /** @return the token's subject (the calling service) */
    public String verify(String token) throws EdgeAuthException {
        try {
            String[] parts = token == null ? new String[0] : token.split("\\.", -1);
            if (parts.length != 3) {
                throw new EdgeAuthException("token must be a compact JWS");
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            JsonNode header = MAPPER.readTree(decoder.decode(parts[0]));
            if (!"EdDSA".equals(header.path("alg").asText())) {
                throw new EdgeAuthException("only EdDSA tokens are accepted");
            }
            Optional<String> keyId = header.hasNonNull("kid") ? Optional.of(header.get("kid").asText()) : Optional.empty();
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(keys.forKeyId(keyId));
            signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            if (!signature.verify(decoder.decode(parts[2]))) {
                throw new EdgeAuthException("token signature is invalid");
            }
            JsonNode payload = MAPPER.readTree(decoder.decode(parts[1]));
            if (!ISSUER.equals(payload.path("iss").asText())) {
                throw new EdgeAuthException("unexpected token issuer");
            }
            if (!AUDIENCE_EDGE.equals(payload.path("aud").asText())) {
                throw new EdgeAuthException("token is not meant for an edge adapter");
            }
            boolean platform = false;
            for (JsonNode scope : payload.path("scope")) {
                platform |= SCOPE_PLATFORM.equals(scope.asText());
            }
            if (!platform) {
                throw new EdgeAuthException("token is not a platform service token");
            }
            Instant now = Instant.now();
            if (!payload.path("exp").canConvertToLong()
                    || Instant.ofEpochSecond(payload.get("exp").asLong()).plus(CLOCK_SKEW).isBefore(now)) {
                throw new EdgeAuthException("token is expired");
            }
            if (payload.path("iat").canConvertToLong()
                    && Instant.ofEpochSecond(payload.get("iat").asLong()).minus(CLOCK_SKEW).isAfter(now)) {
                throw new EdgeAuthException("token is issued in the future");
            }
            return payload.path("sub").asText();
        } catch (EdgeAuthException e) {
            throw e;
        } catch (Exception e) {
            throw new EdgeAuthException("token is not well-formed");
        }
    }

    /** Why a platform token was refused. */
    public static class EdgeAuthException extends Exception {
        public EdgeAuthException(String message) {
            super(message);
        }
    }
}
