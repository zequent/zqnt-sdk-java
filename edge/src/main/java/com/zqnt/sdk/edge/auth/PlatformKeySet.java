package com.zqnt.sdk.edge.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The keys platform service tokens are verified with: the static key ({@code ZQNT_PLATFORM_PUBLIC_KEY})
 * and the platform's published key set ({@code ZQNT_PLATFORM_KEYS_URL}, admin-console's
 * {@code /api/admin-console/auth/service-keys}). A token's {@code kid} names a key of the set; a
 * token without {@code kid} is verified with the static key, or else with the set's key without
 * {@code kid}. The set is fetched in the background at {@link #start()}, refreshed every
 * {@code refreshInterval}, and on a token naming an unknown key, at most once per
 * {@code onDemandSpacing}.
 */
@Slf4j
public class PlatformKeySet implements AutoCloseable {

    public static final Duration REFRESH_INTERVAL = Duration.ofMinutes(5);
    public static final Duration ON_DEMAND_SPACING = Duration.ofSeconds(30);
    static final Duration FETCH_TIMEOUT = Duration.ofSeconds(5);
    static final Duration FIRST_RETRY = Duration.ofSeconds(1);
    static final Duration MAX_RETRY = Duration.ofSeconds(30);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] X509_ED25519_PREFIX = HexFormat.of().parseHex("302a300506032b6570032100");
    private static final int RAW_KEY_LENGTH = 32;

    private final PublicKey staticKey;
    private final URI keysUrl;
    private final Clock clock;
    private final Duration refreshInterval;
    private final Duration onDemandSpacing;
    private final HttpClient http;
    private ScheduledExecutorService scheduler;
    private Instant lastFetchAttempt;
    private volatile Published published = new Published(Map.of(), null);

    public PlatformKeySet(String staticKey, String keysUrl) {
        this(staticKey, keysUrl, Clock.systemUTC(), REFRESH_INTERVAL, ON_DEMAND_SPACING);
    }

    public PlatformKeySet(String staticKey, String keysUrl, Clock clock, Duration refreshInterval,
            Duration onDemandSpacing) {
        this.staticKey = isBlank(staticKey) ? null : parseStaticKey(staticKey);
        this.keysUrl = isBlank(keysUrl) ? null : parseUrl(keysUrl);
        this.clock = clock;
        this.refreshInterval = refreshInterval;
        this.onDemandSpacing = onDemandSpacing;
        this.http = this.keysUrl == null ? null : HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build();
    }

    public boolean hasStaticKey() {
        return staticKey != null;
    }

    public boolean hasKeysUrl() {
        return keysUrl != null;
    }

    public synchronized void start() {
        if (keysUrl == null || scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zqnt-platform-keys");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(() -> firstFetch(FIRST_RETRY));
    }

    @Override
    public synchronized void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public PublicKey forKeyId(Optional<String> keyId) throws PlatformTokenVerifier.EdgeAuthException {
        if (keyId.isEmpty()) {
            if (staticKey != null) {
                return staticKey;
            }
            PublicKey withoutKid = published.withoutKid();
            if (withoutKid == null) {
                throw new PlatformTokenVerifier.EdgeAuthException("token names no signing key and no key without kid is configured");
            }
            return withoutKid;
        }
        String kid = keyId.get();
        if (keysUrl == null) {
            throw new PlatformTokenVerifier.EdgeAuthException(
                    "token is signed with key " + kid + " but ZQNT_PLATFORM_KEYS_URL is not configured");
        }
        PublicKey key = published.byKid().get(kid);
        if (key == null) {
            refreshOnDemand();
            key = published.byKid().get(kid);
        }
        if (key == null) {
            throw new PlatformTokenVerifier.EdgeAuthException("token is signed with an unknown key " + kid);
        }
        return key;
    }

    synchronized void refreshOnDemand() {
        if (lastFetchAttempt == null || !clock.instant().isBefore(lastFetchAttempt.plus(onDemandSpacing))) {
            refresh();
        }
    }

    synchronized boolean refresh() {
        lastFetchAttempt = clock.instant();
        try {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(keysUrl).timeout(FETCH_TIMEOUT)
                    .header("Accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                log.warn("Could not refresh the platform keys from {}: HTTP {}", keysUrl, response.statusCode());
                return false;
            }
            published = parse(response.body());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Exception e) {
            log.warn("Could not refresh the platform keys from {}: {}", keysUrl, e.getMessage());
            return false;
        }
    }

    private void firstFetch(Duration retryIn) {
        if (refresh()) {
            log.info("Loaded the platform keys {} from {}", published.byKid().keySet(), keysUrl);
            schedule(this::periodicRefresh, refreshInterval);
            return;
        }
        Duration next = retryIn.multipliedBy(2).compareTo(MAX_RETRY) > 0 ? MAX_RETRY : retryIn.multipliedBy(2);
        schedule(() -> firstFetch(next), retryIn);
    }

    private void periodicRefresh() {
        refresh();
        schedule(this::periodicRefresh, refreshInterval);
    }

    private synchronized void schedule(Runnable task, Duration delay) {
        if (scheduler != null) {
            scheduler.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    static Published parse(String body) throws Exception {
        Map<String, PublicKey> byKid = new HashMap<>();
        PublicKey withoutKid = null;
        for (JsonNode jwk : MAPPER.readTree(body).path("keys")) {
            Optional<PublicKey> key = ed25519(jwk);
            if (key.isEmpty()) {
                continue;
            }
            if (jwk.hasNonNull("kid")) {
                byKid.put(jwk.get("kid").asText(), key.get());
            } else {
                withoutKid = key.get();
            }
        }
        return new Published(Map.copyOf(byKid), withoutKid);
    }

    private static Optional<PublicKey> ed25519(JsonNode jwk) {
        if (!"OKP".equals(jwk.path("kty").asText()) || !"Ed25519".equals(jwk.path("crv").asText())
                || jwk.hasNonNull("alg") && !"EdDSA".equals(jwk.get("alg").asText())
                || jwk.hasNonNull("use") && !"sig".equals(jwk.get("use").asText())) {
            return Optional.empty();
        }
        try {
            byte[] raw = Base64.getUrlDecoder().decode(jwk.path("x").asText().replace("=", ""));
            if (raw.length != RAW_KEY_LENGTH) {
                return Optional.empty();
            }
            byte[] encoded = Arrays.copyOf(X509_ED25519_PREFIX, X509_ED25519_PREFIX.length + RAW_KEY_LENGTH);
            System.arraycopy(raw, 0, encoded, X509_ED25519_PREFIX.length, RAW_KEY_LENGTH);
            return Optional.of(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static PublicKey parseStaticKey(String value) {
        try {
            String normalized = value.replaceAll("-----(BEGIN|END) [A-Z0-9 ]+-----", "").replaceAll("\\s", "");
            return KeyFactory.getInstance("Ed25519")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(normalized)));
        } catch (Exception e) {
            throw new IllegalArgumentException("ZQNT_PLATFORM_PUBLIC_KEY is not a valid Ed25519 public key", e);
        }
    }

    private static URI parseUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme())) {
                throw new IllegalArgumentException("not an http(s) URL");
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("ZQNT_PLATFORM_KEYS_URL is not a valid http(s) URL", e);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record Published(Map<String, PublicKey> byKid, PublicKey withoutKid) {
    }
}
