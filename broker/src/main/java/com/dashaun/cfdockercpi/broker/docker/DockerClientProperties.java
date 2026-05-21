package com.dashaun.cfdockercpi.broker.docker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the broker reaches dockerd. Phase 3 will arrange for these to land as CF app env vars
 * ({@code DOCKER_HOST}, {@code DOCKER_TLS_CA_B64}, etc.) at {@code cf push} time.
 *
 * <p>{@code host} is required (the broker can't run without dockerd). The TLS triple is
 * required when the host scheme is {@code tcp://} (the only supported runtime mode); they're
 * left optional in this record so a future "unix socket on the laptop" dev mode could omit
 * them. Validation lives in {@link DockerClientConfiguration} where the host scheme is known.
 *
 * <p>The TLS material is base64-encoded so it survives the {@code cf set-env} / CF
 * environment-variable pipeline cleanly. The decoded bytes are written to a freshly-created
 * temp directory at startup; that directory is what docker-java reads via {@code DOCKER_CERT_PATH}.
 *
 * @param host          docker daemon URL, e.g. {@code tcp://10.245.0.1:2376}
 * @param network       docker network the broker will attach service containers to.
 *                      Default {@code cf-docker-cpi-net} matches the CLI's bridge.
 * @param tlsCaB64      base64 of the PEM CA cert
 * @param tlsCertB64    base64 of the PEM client cert
 * @param tlsKeyB64     base64 of the PEM client private key
 */
@ConfigurationProperties(prefix = "docker")
public record DockerClientProperties(
        String host,
        String network,
        String tlsCaB64,
        String tlsCertB64,
        String tlsKeyB64) {

    public DockerClientProperties {
        if (host == null || host.isBlank()) {
            throw new IllegalStateException(
                    "docker.host is required (e.g. tcp://10.245.0.1:2376). " +
                    "Provide via DOCKER_HOST env var at cf push time.");
        }
        if (network == null || network.isBlank()) {
            network = "cf-docker-cpi-net";
        }
        if (host.startsWith("tcp://") &&
                (blank(tlsCaB64) || blank(tlsCertB64) || blank(tlsKeyB64))) {
            throw new IllegalStateException(
                    "docker.host=" + host + " requires TLS — set "
                    + "DOCKER_TLS_CA_B64 / DOCKER_TLS_CERT_B64 / DOCKER_TLS_KEY_B64 "
                    + "(base64-encoded PEM blocks).");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
