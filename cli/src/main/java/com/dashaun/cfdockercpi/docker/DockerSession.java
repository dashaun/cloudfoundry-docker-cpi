package com.dashaun.cfdockercpi.docker;

import com.github.dockerjava.api.DockerClient;

public final class DockerSession implements AutoCloseable {

    private final DockerClient client;
    private final SshTunnel tunnel;
    private final String effectiveUri;

    public DockerSession(DockerClient client, SshTunnel tunnel, String effectiveUri) {
        this.client = client;
        this.tunnel = tunnel;
        this.effectiveUri = effectiveUri;
    }

    public DockerClient client() {
        return client;
    }

    public String effectiveUri() {
        return effectiveUri;
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
        if (tunnel != null) {
            tunnel.close();
        }
    }
}
