package com.dashaun.cfdockercpi.docker;

import java.net.URI;

public record DockerTarget(URI uri, Source source, String remoteSocket) {

    public enum Source { FLAG, ENV, DEFAULT }

    public boolean isSsh() {
        return "ssh".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isTcp() {
        return "tcp".equalsIgnoreCase(uri.getScheme());
    }

    public boolean isUnix() {
        return "unix".equalsIgnoreCase(uri.getScheme());
    }

    public String sshUserHost() {
        if (!isSsh()) {
            throw new IllegalStateException("Not an ssh target: " + uri);
        }
        String userInfo = uri.getUserInfo();
        String host = uri.getHost();
        return userInfo == null ? host : userInfo + "@" + host;
    }

    public int sshPort() {
        return uri.getPort() == -1 ? 22 : uri.getPort();
    }
}
