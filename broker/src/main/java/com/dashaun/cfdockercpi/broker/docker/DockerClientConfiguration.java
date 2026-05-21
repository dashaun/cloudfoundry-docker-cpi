package com.dashaun.cfdockercpi.broker.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/**
 * Builds the {@link DockerClient} bean that the provisioning services use to spin up service
 * containers. Only active when {@code docker.host} is set — tests without that property
 * don't pay the cost of the temp-dir + TLS dance.
 *
 * <p>docker-java's {@code DefaultDockerClientConfig} reads TLS material from a single
 * directory containing {@code ca.pem}, {@code cert.pem}, {@code key.pem}. The base64 env
 * vars get decoded into a freshly-created temp dir on the broker's filesystem at startup;
 * the dir is best-effort cleaned on JVM exit.
 */
@Configuration
@ConditionalOnProperty("docker.host")
@EnableConfigurationProperties(DockerClientProperties.class)
public class DockerClientConfiguration {

    private static final Logger log = LoggerFactory.getLogger(DockerClientConfiguration.class);

    @Bean
    public DockerClient dockerClient(DockerClientProperties props) throws IOException {
        Path certDir = unpackTlsToTempDir(props);
        log.info("docker-java client: host={} network={} certPath={}",
                props.host(), props.network(), certDir);

        DefaultDockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(props.host())
                .withDockerTlsVerify(true)
                .withDockerCertPath(certDir.toString())
                .build();

        ApacheDockerHttpClient http = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .build();

        return DockerClientImpl.getInstance(config, http);
    }

    private Path unpackTlsToTempDir(DockerClientProperties props) throws IOException {
        Path dir = Files.createTempDirectory("cf-docker-cpi-broker-tls-",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        dir.toFile().deleteOnExit();
        writeDecoded(dir.resolve("ca.pem"), props.tlsCaB64(), "rw-------");
        writeDecoded(dir.resolve("cert.pem"), props.tlsCertB64(), "rw-------");
        writeDecoded(dir.resolve("key.pem"), props.tlsKeyB64(), "rw-------");
        return dir;
    }

    private static void writeDecoded(Path file, String b64, String perms) throws IOException {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            throw new IOException("could not base64-decode " + file.getFileName()
                    + " env var; expected a PEM block encoded with `base64 -w0`", e);
        }
        Files.write(file, decoded);
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(perms));
        file.toFile().deleteOnExit();
    }
}
