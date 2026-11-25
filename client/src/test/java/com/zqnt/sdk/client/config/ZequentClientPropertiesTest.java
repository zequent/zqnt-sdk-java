package com.zqnt.sdk.client.config;

import com.zqnt.sdk.client.config.properties.ZequentClientProperties;
import io.smallrye.config.PropertiesConfigSource;
import io.smallrye.config.SmallRyeConfig;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Quarkus integration maps the same variables as {@link ZequentEnvironment}. */
class ZequentClientPropertiesTest {

    @Test
    void nothingSetIsPlaintext() throws IOException {
        ZequentClientProperties properties = properties(Map.of());
        assertFalse(properties.internalTls());
        assertTrue(properties.connectorService().usePlaintext().isEmpty(), "unset, decided by internal-tls");
        ServiceConfig config = factory(properties).createConfig().getConnectorConfig();
        assertTrue(config.isUsePlaintext());
        assertNull(config.getTlsCaFile());
    }

    @Test
    void internalTlsSwitchesEveryServiceToTlsAgainstTheCa() throws IOException {
        ZequentClientProperties properties = properties(Map.of(
                "ZQNT_INTERNAL_TLS", "true",
                "ZQNT_INTERNAL_TLS_CA", "/run/ca.crt",
                "LIVE_DATA_SERVICE_USE_PLAINTEXT", "true"));
        GrpcClientConfig config = factory(properties).createConfig();
        assertFalse(config.getConnectorConfig().isUsePlaintext());
        assertEquals("/run/ca.crt", config.getConnectorConfig().getTlsCaFile());
        assertFalse(config.getRemoteControlConfig().isUsePlaintext());
        assertTrue(config.getLiveDataConfig().isUsePlaintext(), "a service's own use-plaintext wins");
        assertNull(config.getLiveDataConfig().getTlsCaFile());
    }

    private static ZequentClientProperties properties(Map<String, String> env) throws IOException {
        SmallRyeConfig config = new SmallRyeConfigBuilder()
                .addDefaultInterceptors()
                .withSources(new PropertiesConfigSource(env, "env", 300))
                .withSources(new PropertiesConfigSource(
                        Path.of("src/main/resources/application.properties").toUri().toURL(), 250))
                .withMapping(ZequentClientProperties.class)
                .build();
        return config.getConfigMapping(ZequentClientProperties.class);
    }

    private static ZequentClientConfigFactory factory(ZequentClientProperties properties) {
        ZequentClientConfigFactory factory = new ZequentClientConfigFactory();
        factory.properties = properties;
        return factory;
    }
}
