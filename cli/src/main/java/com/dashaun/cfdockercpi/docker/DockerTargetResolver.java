package com.dashaun.cfdockercpi.docker;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;

@Component
public class DockerTargetResolver {

    private static final String DEFAULT_REMOTE_SOCKET = "/var/run/docker.sock";

    public DockerTarget resolve(String hostFlag, String remoteSocketFlag) {
        String remoteSocket = (remoteSocketFlag == null || remoteSocketFlag.isBlank())
                ? DEFAULT_REMOTE_SOCKET
                : remoteSocketFlag;

        if (hostFlag != null && !hostFlag.isBlank()) {
            return new DockerTarget(parse(hostFlag), DockerTarget.Source.FLAG, remoteSocket);
        }

        String env = System.getenv("DOCKER_HOST");
        if (env != null && !env.isBlank()) {
            return new DockerTarget(parse(env), DockerTarget.Source.ENV, remoteSocket);
        }

        return new DockerTarget(URI.create("unix://" + DEFAULT_REMOTE_SOCKET),
                DockerTarget.Source.DEFAULT, remoteSocket);
    }

    private URI parse(String raw) {
        String value = raw.trim();
        if (!value.contains("://")) {
            value = "ssh://" + value;
        }
        try {
            return new URI(value);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid Docker host URI: " + raw, e);
        }
    }
}
