package com.dashaun.cfdockercpi.tooling;

import java.net.URI;
import java.util.EnumMap;
import java.util.Map;

public final class ToolingVersions {

    public static final String BOSH_VERSION = "7.10.5";
    public static final String CF_VERSION = "8.18.3";

    private static final Map<HostPlatform, String> BOSH_SHA = new EnumMap<>(HostPlatform.class);
    private static final Map<HostPlatform, String> CF_SHA = new EnumMap<>(HostPlatform.class);
    private static final Map<HostPlatform, String> CF_ARCHIVE_TAG = new EnumMap<>(HostPlatform.class);

    static {
        BOSH_SHA.put(HostPlatform.DARWIN_AMD64,
                "65679d3b5d47003fe1d7d959efa6ed033b1c8dea8fc2d550276ff624530dc4bf");
        BOSH_SHA.put(HostPlatform.DARWIN_ARM64,
                "0b031344a86628f4ccbbdd5f2a4b49cec747332349b863610b7e904536e70a83");
        BOSH_SHA.put(HostPlatform.LINUX_AMD64,
                "e9847375ba5397589e7b070305defc70321ad0e62d18b67a70a330efcab6e526");
        BOSH_SHA.put(HostPlatform.LINUX_ARM64,
                "2a16bf201ce979743c305a6a4f3616ce7e6bd683437084116a03263988afb0ad");
        BOSH_SHA.put(HostPlatform.WINDOWS_AMD64,
                "e38ccc6d7f1911bb390b677fac5f6f196207cd1c74eba0a261f23b36558bd691");

        CF_ARCHIVE_TAG.put(HostPlatform.DARWIN_AMD64, "osx");
        CF_ARCHIVE_TAG.put(HostPlatform.DARWIN_ARM64, "macosarm");
        CF_ARCHIVE_TAG.put(HostPlatform.LINUX_AMD64, "linux_x86-64");
        CF_ARCHIVE_TAG.put(HostPlatform.LINUX_ARM64, "linux_arm64");
        CF_ARCHIVE_TAG.put(HostPlatform.WINDOWS_AMD64, "winx64");

        CF_SHA.put(HostPlatform.DARWIN_AMD64,
                "f201a4cf8fbdb723f848ba95a1cb0c0e9f7484ca44fc98e5643a359f6db4e154");
        CF_SHA.put(HostPlatform.DARWIN_ARM64,
                "f4bd3664ac2f4884ef294d14f8ead9d002c012b580bf694c343f71156f1ee4c7");
        CF_SHA.put(HostPlatform.LINUX_AMD64,
                "8942e2c3c98e83c7e14edbce939876bba7ff12a26f0f722c5aa5b079d357d50b");
        CF_SHA.put(HostPlatform.LINUX_ARM64,
                "ffcd956cdb83356e15557a492c5b9e49a52ed0598bde8f2945054ff0933f7e12");
        CF_SHA.put(HostPlatform.WINDOWS_AMD64,
                "bdbe398ed290b197af48c1254cfcb4496afffe1ded23b46ede4a3cc23718ed3d");
    }

    private ToolingVersions() {}

    public static ToolSpec bosh(HostPlatform p) {
        String suffix = p.os() + "-" + p.arch() + (p.isWindows() ? ".exe" : "");
        URI url = URI.create("https://github.com/cloudfoundry/bosh-cli/releases/download/v"
                + BOSH_VERSION + "/bosh-cli-" + BOSH_VERSION + "-" + suffix);
        return ToolSpec.rawBinary("bosh", BOSH_VERSION, url, sha(BOSH_SHA, p));
    }

    public static ToolSpec cf(HostPlatform p) {
        String tag = CF_ARCHIVE_TAG.get(p);
        String ext = p.isWindows() ? "zip" : "tgz";
        URI url = URI.create("https://github.com/cloudfoundry/cli/releases/download/v"
                + CF_VERSION + "/cf8-cli_" + CF_VERSION + "_" + tag + "." + ext);
        String entry = p.isWindows() ? "cf8.exe" : "cf8";
        return ToolSpec.tarball("cf", CF_VERSION, url, sha(CF_SHA, p), entry);
    }

    private static String sha(Map<HostPlatform, String> map, HostPlatform p) {
        String s = map.get(p);
        if (s == null) {
            throw new IllegalStateException("No SHA-256 pin for platform " + p);
        }
        return s;
    }
}
