package com.dashaun.cfdockercpi.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.httpclient5.ApacheDockerHttpClient;
import com.github.dockerjava.transport.DockerHttpClient;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

@Component
public class DockerClientFactory {

    private static final Duration SSH_READY_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration HTTP_CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration HTTP_RESPONSE_TIMEOUT = Duration.ofSeconds(30);

    public DockerSession open(DockerTarget target) throws IOException {
        SshTunnel tunnel = null;
        URI effectiveUri;

        if (target.isSsh()) {
            tunnel = SshTunnel.open(target, SSH_READY_TIMEOUT);
            effectiveUri = URI.create("tcp://localhost:" + tunnel.localPort());
        } else {
            effectiveUri = target.uri();
        }

        DockerClientConfig config = DefaultDockerClientConfig.createDefaultConfigBuilder()
                .withDockerHost(effectiveUri.toString())
                .build();

        DockerHttpClient httpClient = new ApacheDockerHttpClient.Builder()
                .dockerHost(config.getDockerHost())
                .sslConfig(config.getSSLConfig())
                .connectionTimeout(HTTP_CONNECT_TIMEOUT)
                .responseTimeout(HTTP_RESPONSE_TIMEOUT)
                .build();

        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        return new DockerSession(client, tunnel, effectiveUri.toString());
    }
}
