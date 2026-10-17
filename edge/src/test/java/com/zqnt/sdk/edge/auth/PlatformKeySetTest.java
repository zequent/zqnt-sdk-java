package com.zqnt.sdk.edge.auth;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class PlatformKeySetTest {

    static final KeyPair V1 = PlatformAuthTest.generate();
    static final KeyPair V2 = PlatformAuthTest.generate();
    static final KeyPair STATIC = PlatformAuthTest.generate();

    HttpServer server;
    String url;
    final AtomicReference<String> body = new AtomicReference<>("{\"keys\":[]}");
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicInteger fetches = new AtomicInteger();
    final MutableClock clock = new MutableClock();

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/admin-console/auth/service-keys", exchange -> {
            fetches.incrementAndGet();
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/api/admin-console/auth/service-keys";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void aRotatedKeyIsFetchedOnFirstUseAndTheOldOneStaysValid() throws Exception {
        PlatformTokenVerifier verifier = verifier(null);
        serve(jwk("service-token:v1", V1));
        assertEquals("svc:remote-control-service", verifier.verify(token(V1, "service-token:v1")));

        clock.advance(Duration.ofSeconds(31));
        serve(jwk("service-token:v1", V1), jwk("service-token:v2", V2));
        assertEquals("svc:remote-control-service", verifier.verify(token(V2, "service-token:v2")));
        assertEquals("svc:remote-control-service", verifier.verify(token(V1, "service-token:v1")));
        assertEquals(2, fetches.get());
    }

    @Test
    void anUnknownKeyIsRefusedAfterARefresh() {
        PlatformTokenVerifier verifier = verifier(null);
        serve(jwk("service-token:v1", V1));
        PlatformTokenVerifier.EdgeAuthException refused = assertThrows(PlatformTokenVerifier.EdgeAuthException.class,
                () -> verifier.verify(token(V2, "service-token:v9")));
        assertEquals("token is signed with an unknown key service-token:v9", refused.getMessage());
        assertEquals(1, fetches.get());
    }

    @Test
    void aKeySignedByAnotherKeyUnderAKnownKidIsRefused() {
        PlatformTokenVerifier verifier = verifier(null);
        serve(jwk("service-token:v1", V1));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(V2, "service-token:v1")));
    }

    @Test
    void unknownKeysRefreshAtMostOncePerSpacing() {
        PlatformTokenVerifier verifier = verifier(null);
        serve(jwk("service-token:v1", V1));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(V2, "service-token:v2")));
        clock.advance(Duration.ofSeconds(29));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(V2, "service-token:v3")));
        assertEquals(1, fetches.get());

        clock.advance(Duration.ofSeconds(1));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(V2, "service-token:v4")));
        assertEquals(2, fetches.get());
    }

    @Test
    void aFailedRefreshKeepsTheCachedKeys() throws Exception {
        PlatformKeySet keys = keys(null);
        serve(jwk("service-token:v1", V1));
        assertTrue(keys.refresh());
        status.set(503);
        assertFalse(keys.refresh());
        status.set(200);
        body.set("not json");
        assertFalse(keys.refresh());
        assertEquals("svc:remote-control-service", new PlatformTokenVerifier(keys).verify(token(V1, "service-token:v1")));
    }

    @Test
    void aTokenWithoutKidUsesTheStaticKey() throws Exception {
        PlatformTokenVerifier verifier = verifier(publicKey(STATIC));
        serve(jwk(null, V1));
        assertEquals("svc:remote-control-service", verifier.verify(token(STATIC, null)));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(V1, null)));
        assertEquals(0, fetches.get());
    }

    @Test
    void aTokenWithoutKidUsesTheSetsKeyWithoutKidWhenNoStaticKeyIsConfigured() throws Exception {
        PlatformKeySet keys = keys(null);
        serve(jwk(null, V1), jwk("service-token:v2", V2));
        keys.refresh();
        assertEquals("svc:remote-control-service", new PlatformTokenVerifier(keys).verify(token(V1, null)));
    }

    @Test
    void aTokenWithoutKidIsRefusedWithoutAKeyForIt() {
        PlatformKeySet keys = keys(null);
        serve(jwk("service-token:v1", V1));
        keys.refresh();
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> new PlatformTokenVerifier(keys).verify(token(V1, null)));
    }

    @Test
    void aTokenWithKidIsRefusedWithoutAKeysUrl() {
        PlatformTokenVerifier verifier = new PlatformTokenVerifier(publicKey(STATIC));
        assertThrows(PlatformTokenVerifier.EdgeAuthException.class, () -> verifier.verify(token(STATIC, "service-token:v1")));
    }

    @Test
    void entriesThatAreNotEd25519SigningKeysAreSkipped() throws Exception {
        PlatformKeySet.Published published = PlatformKeySet.parse("{\"keys\":["
                + "{\"kty\":\"RSA\",\"kid\":\"rsa:v1\",\"n\":\"x\",\"e\":\"AQAB\"},"
                + "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"use\":\"enc\",\"kid\":\"enc:v1\",\"x\":\"" + x(V1) + "\"},"
                + "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"RS256\",\"kid\":\"alg:v1\",\"x\":\"" + x(V1) + "\"},"
                + "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"kid\":\"short:v1\",\"x\":\"AAAA\"},"
                + "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"kid\":\"padded:v1\",\"x\":\"" + x(V1) + "=\"},"
                + jwk("service-token:v1", V1) + "]}");
        assertEquals(Set.of("padded:v1", "service-token:v1"), published.byKid().keySet());
    }

    @Test
    void startFetchesInTheBackground() throws Exception {
        serve(jwk("service-token:v1", V1));
        try (PlatformKeySet keys = new PlatformKeySet(null, url)) {
            keys.start();
            for (int i = 0; i < 100 && fetches.get() == 0; i++) {
                Thread.sleep(20);
            }
            assertEquals("svc:remote-control-service", new PlatformTokenVerifier(keys).verify(token(V1, "service-token:v1")));
        }
    }

    PlatformKeySet keys(String staticKey) {
        return new PlatformKeySet(staticKey, url, clock, PlatformKeySet.REFRESH_INTERVAL, PlatformKeySet.ON_DEMAND_SPACING);
    }

    PlatformTokenVerifier verifier(String staticKey) {
        return new PlatformTokenVerifier(keys(staticKey));
    }

    void serve(String... jwks) {
        body.set("{\"keys\":[" + String.join(",", Arrays.asList(jwks)) + "]}");
    }

    static String jwk(String kid, KeyPair keys) {
        return "{\"kty\":\"OKP\",\"crv\":\"Ed25519\",\"alg\":\"EdDSA\",\"use\":\"sig\","
                + (kid == null ? "" : "\"kid\":\"" + kid + "\",") + "\"x\":\"" + x(keys) + "\"}";
    }

    static String publicKey(KeyPair keys) {
        return Base64.getEncoder().encodeToString(keys.getPublic().getEncoded());
    }

    static String x(KeyPair keys) {
        byte[] encoded = keys.getPublic().getEncoded();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length));
    }

    static String token(KeyPair keys, String kid) throws Exception {
        long now = Instant.now().getEpochSecond();
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString(("{\"alg\":\"EdDSA\",\"typ\":\"JWT\"" + (kid == null ? "" : ",\"kid\":\"" + kid + "\"")
                + "}").getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"iss\":\"zqnt-service\",\"sub\":\"svc:remote-control-service\","
                + "\"aud\":\"zqnt-edge\",\"scope\":[\"platform\"],\"iat\":" + now + ",\"exp\":" + (now + 300)
                + ",\"jti\":\"j\"}").getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keys.getPrivate());
        signer.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + enc.encodeToString(signer.sign());
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-17T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
