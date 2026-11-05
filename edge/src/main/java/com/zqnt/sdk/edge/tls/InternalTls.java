package com.zqnt.sdk.edge.tls;

import io.grpc.ChannelCredentials;
import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannelBuilder;
import io.grpc.TlsChannelCredentials;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;

/**
 * How an adapter reaches the platform inside an installation whose traffic is encrypted
 * (zqnt-core #371). With {@code ZQNT_INTERNAL_TLS=true} every channel into a core service uses TLS,
 * verifying the service's certificate against the installation's CA ({@code ZQNT_INTERNAL_TLS_CA},
 * default {@code /etc/zqnt/internal-tls/ca.crt}) and the host name dialled; the platform key set
 * ({@code ZQNT_PLATFORM_KEYS_URL}) fetched over https is verified the same way. Without the switch
 * the channels are plaintext, as before. The edge credential keeps authenticating the adapter; no
 * client certificate is involved.
 * <pre>{@code
 * ManagedChannel channel = InternalTls.fromEnv().channelBuilder(host, port)
 *         .intercept(new EdgeCredentialsClientInterceptor(token))
 *         .build();
 * }</pre>
 */
public record InternalTls(boolean enabled, Path caFile) {

    public static final String ENABLED_ENV = "ZQNT_INTERNAL_TLS";
    public static final String CA_ENV = "ZQNT_INTERNAL_TLS_CA";
    public static final Path DEFAULT_CA = Path.of("/etc/zqnt/internal-tls/ca.crt");

    private static final Set<String> TRUE = Set.of("1", "true", "yes", "on");

    public static InternalTls fromEnv() {
        return fromEnv(System::getenv);
    }

    /** {@link #fromEnv()} against another source of variables (tests, a loaded .env). */
    public static InternalTls fromEnv(Function<String, String> env) {
        String enabled = env.apply(ENABLED_ENV);
        String ca = env.apply(CA_ENV);
        return new InternalTls(enabled != null && TRUE.contains(enabled.trim().toLowerCase(Locale.ROOT)),
                ca == null || ca.isBlank() ? DEFAULT_CA : Path.of(ca.trim()));
    }

    public static InternalTls off() {
        return new InternalTls(false, DEFAULT_CA);
    }

    /** TLS with the installation's CA at {@code caFile}. */
    public static InternalTls withCa(Path caFile) {
        return new InternalTls(true, caFile);
    }

    /** The transport of a channel into the platform: TLS trusting the CA, or plaintext. */
    public ChannelCredentials channelCredentials() {
        if (!enabled) {
            return InsecureChannelCredentials.create();
        }
        try {
            return TlsChannelCredentials.newBuilder().trustManager(caFile.toFile()).build();
        } catch (IOException e) {
            throw unreadableCa(e);
        }
    }

    /**
     * A channel builder for {@code host:port} with this transport; keep-alive, retries and
     * interceptors go on the returned builder as before.
     */
    public ManagedChannelBuilder<?> channelBuilder(String host, int port) {
        return Grpc.newChannelBuilder(host + ":" + port, channelCredentials());
    }

    /** For HTTP calls into the platform: a context trusting the CA, or the JDK default when off. */
    public SSLContext sslContext() {
        try {
            if (!enabled) {
                return SSLContext.getDefault();
            }
            KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
            trust.load(null, null);
            int i = 0;
            for (Certificate certificate : readCertificates()) {
                trust.setCertificateEntry("zqnt-internal-ca-" + i++, certificate);
            }
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init(trust);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, factory.getTrustManagers(), null);
            return context;
        } catch (IOException e) {
            throw unreadableCa(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Internal TLS: cannot trust the CA " + caFile + ": " + e.getMessage(), e);
        }
    }

    private Collection<? extends Certificate> readCertificates() throws IOException, GeneralSecurityException {
        try (InputStream in = Files.newInputStream(caFile)) {
            Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509").generateCertificates(in);
            if (certificates.isEmpty()) {
                throw new IllegalStateException("Internal TLS: no certificate in the CA file " + caFile);
            }
            return certificates;
        }
    }

    private IllegalStateException unreadableCa(IOException e) {
        return new IllegalStateException("Internal TLS is on (" + ENABLED_ENV + ") but the CA file " + caFile
                + " cannot be read (" + CA_ENV + "): " + e.getMessage(), e);
    }
}
