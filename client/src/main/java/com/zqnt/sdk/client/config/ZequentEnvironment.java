package com.zqnt.sdk.client.config;

import java.util.Map;
import java.util.function.Function;

/**
 * The environment variables every Zequent client SDK (Java, Python, Go) reads, and the local
 * defaults a developer gets without setting any of them.
 *
 * <p><b>Local development</b> (nothing set): every service on {@code localhost} at the ports the
 * platform's services listen on in {@code quarkus:dev} and {@code docker-compose.local.yml}, in
 * plaintext. The credential still has to come from somewhere — issue a client credential in the
 * local console and export {@value com.zqnt.sdk.client.grpc.ClientCredentials#ENV_VAR}.</p>
 *
 * <p><b>Deployment</b>: per service {@code <PREFIX>_HOST}, {@code <PREFIX>_PORT} and
 * {@code <PREFIX>_USE_PLAINTEXT} ({@code false} = TLS against the system trust store), with the
 * prefixes {@value #CONNECTOR}, {@value #REMOTE_CONTROL}, {@value #LIVE_DATA} and
 * {@value #MISSION_AUTONOMY}; the credential in {@code ZQNT_CLIENT_TOKEN} (from a secret, never a
 * committed file). The Quarkus integration reads the same names (see the SDK's
 * {@code application.properties}), so one {@code .env} works for every language and framework.</p>
 *
 * <p><b>Inside an installation with internal TLS</b> (zqnt-core #371): {@code ZQNT_INTERNAL_TLS=true}
 * dials every service over TLS, verifying its certificate against the installation's CA in
 * {@code ZQNT_INTERNAL_TLS_CA} (default {@value #DEFAULT_INTERNAL_TLS_CA}) and its host name. A
 * service's own {@code <PREFIX>_USE_PLAINTEXT} still wins when set.</p>
 */
public final class ZequentEnvironment {

    public static final String CONNECTOR = "CONNECTOR_SERVICE";
    public static final String REMOTE_CONTROL = "REMOTE_CONTROL_SERVICE";
    public static final String LIVE_DATA = "LIVE_DATA_SERVICE";
    public static final String MISSION_AUTONOMY = "MISSION_AUTONOMY_SERVICE";

    /** Where each service listens locally ({@code quarkus:dev}, docker-compose.local.yml). */
    public static final Map<String, Integer> LOCAL_PORTS = Map.of(
            "connector", 8010,
            "remote-control", 8002,
            "live-data", 8003,
            "mission-autonomy", 8004);

    public static final String LOCAL_HOST = "localhost";

    public static final String INTERNAL_TLS = "ZQNT_INTERNAL_TLS";
    public static final String INTERNAL_TLS_CA = "ZQNT_INTERNAL_TLS_CA";
    public static final String DEFAULT_INTERNAL_TLS_CA = "/etc/zqnt/internal-tls/ca.crt";

    private ZequentEnvironment() {
    }

    /** The local default for {@code serviceName}: localhost, its local port, plaintext. */
    public static ServiceConfig local(String serviceName, ServiceConfig.LoadBalancerType loadBalancer) {
        return ServiceConfig.builder()
                .serviceName(serviceName)
                .host(LOCAL_HOST)
                .port(localPort(serviceName))
                .usePlaintext(true)
                .useStork(false)
                .storkServiceName(serviceName + "-service")
                .loadBalancerType(loadBalancer)
                .build();
    }

    /** {@code serviceName}'s configuration from {@code env}, falling back to {@link #local}. */
    public static ServiceConfig fromEnvironment(Function<String, String> env, String prefix, String serviceName,
                                                ServiceConfig.LoadBalancerType loadBalancer) {
        ServiceConfig local = local(serviceName, loadBalancer);
        String host = blankToNull(env.apply(prefix + "_HOST"));
        String port = blankToNull(env.apply(prefix + "_PORT"));
        String plaintext = blankToNull(env.apply(prefix + "_USE_PLAINTEXT"));
        String internalTls = blankToNull(env.apply(INTERNAL_TLS));
        boolean internalTlsOn = internalTls != null && parseBoolean(internalTls);
        boolean usePlaintext = plaintext != null ? parseBoolean(plaintext) : !internalTlsOn && local.isUsePlaintext();
        return local.toBuilder()
                .host(host != null ? host : local.getHost())
                .port(port != null ? parsePort(prefix + "_PORT", port) : local.getPort())
                .usePlaintext(usePlaintext)
                .tlsCaFile(internalTlsOn ? internalTlsCa(env) : null)
                .build();
    }

    /** The installation's CA file ({@code ZQNT_INTERNAL_TLS_CA}, or its default). */
    public static String internalTlsCa(Function<String, String> env) {
        String ca = blankToNull(env.apply(INTERNAL_TLS_CA));
        return ca != null ? ca : DEFAULT_INTERNAL_TLS_CA;
    }

    static int localPort(String serviceName) {
        Integer port = LOCAL_PORTS.get(serviceName);
        if (port == null) {
            throw new IllegalArgumentException("Unknown Zequent service: " + serviceName);
        }
        return port;
    }

    private static int parsePort(String name, String value) {
        try {
            int port = Integer.parseInt(value.strip());
            if (port < 1 || port > 65535) {
                throw new NumberFormatException();
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " must be a port number (1-65535), was '" + value + "'");
        }
    }

    private static boolean parseBoolean(String value) {
        return switch (value.strip().toLowerCase(java.util.Locale.ROOT)) {
            case "1", "true", "yes", "on" -> true;
            default -> false;
        };
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
