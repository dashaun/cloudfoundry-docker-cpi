package com.dashaun.cfdockercpi.setup;

import com.dashaun.cfdockercpi.docker.DockerTarget;

import java.net.URI;

public final class HostSlug {

    private HostSlug() {}

    public static String from(DockerTarget target) {
        return from(target.uri());
    }

    public static String from(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        if ("unix".equals(scheme)) {
            return "unix-local";
        }
        StringBuilder sb = new StringBuilder();
        if (!scheme.isEmpty()) {
            sb.append(scheme).append('-');
        }
        String host = uri.getHost();
        if (host != null) {
            sb.append(host);
        }
        int port = uri.getPort();
        if (port != -1) {
            sb.append('-').append(port);
        }
        return slugify(sb.toString());
    }

    private static String slugify(String s) {
        String lower = s.toLowerCase();
        String squeezed = lower.replaceAll("[^a-z0-9]+", "-");
        return squeezed.replaceAll("(^-|-$)", "");
    }
}
