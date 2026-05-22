package com.dashaun.cfdockercpi.tooling;

import java.net.URI;

public record ToolSpec(
        String name,
        String version,
        URI url,
        String sha256,
        boolean tarball,
        String entryInTarball) {

    public static ToolSpec rawBinary(String name, String version, URI url, String sha256) {
        return new ToolSpec(name, version, url, sha256, false, null);
    }

    public static ToolSpec tarball(String name, String version, URI url, String sha256, String entry) {
        return new ToolSpec(name, version, url, sha256, true, entry);
    }
}
