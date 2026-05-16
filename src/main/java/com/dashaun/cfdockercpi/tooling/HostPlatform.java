package com.dashaun.cfdockercpi.tooling;

public enum HostPlatform {

    DARWIN_AMD64("darwin", "amd64"),
    DARWIN_ARM64("darwin", "arm64"),
    LINUX_AMD64("linux", "amd64"),
    LINUX_ARM64("linux", "arm64"),
    WINDOWS_AMD64("windows", "amd64");

    private final String os;
    private final String arch;

    HostPlatform(String os, String arch) {
        this.os = os;
        this.arch = arch;
    }

    public String os() {
        return os;
    }

    public String arch() {
        return arch;
    }

    public boolean isWindows() {
        return this == WINDOWS_AMD64;
    }

    public static HostPlatform detect() {
        return detect(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    public static HostPlatform detect(String rawOsName, String rawOsArch) {
        String osName = rawOsName.toLowerCase();
        String osArch = rawOsArch.toLowerCase();
        boolean mac = osName.contains("mac") || osName.contains("darwin");
        boolean linux = osName.contains("linux");
        boolean windows = osName.contains("win");
        boolean arm64 = osArch.equals("aarch64") || osArch.equals("arm64");
        boolean amd64 = osArch.equals("amd64") || osArch.equals("x86_64");

        if (mac && arm64) return DARWIN_ARM64;
        if (mac && amd64) return DARWIN_AMD64;
        if (linux && arm64) return LINUX_ARM64;
        if (linux && amd64) return LINUX_AMD64;
        if (windows && amd64) return WINDOWS_AMD64;
        throw new IllegalStateException(
                "Unsupported platform: os.name=" + rawOsName + " os.arch=" + rawOsArch
                + " (need darwin/linux/windows on amd64/arm64)");
    }
}
