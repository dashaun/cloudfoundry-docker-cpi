package com.dashaun.cfdockercpi.tooling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolingVersionsTest {

    @Test
    void boshUrlForDarwinArm64() {
        ToolSpec spec = ToolingVersions.bosh(HostPlatform.DARWIN_ARM64);
        assertThat(spec.url().toString())
                .isEqualTo("https://github.com/cloudfoundry/bosh-cli/releases/download/v"
                        + ToolingVersions.BOSH_VERSION
                        + "/bosh-cli-" + ToolingVersions.BOSH_VERSION + "-darwin-arm64");
        assertThat(spec.tarball()).isFalse();
        assertThat(spec.sha256()).hasSize(64);
    }

    @Test
    void boshUrlForLinuxAmd64() {
        ToolSpec spec = ToolingVersions.bosh(HostPlatform.LINUX_AMD64);
        assertThat(spec.url().toString()).endsWith("/bosh-cli-" + ToolingVersions.BOSH_VERSION + "-linux-amd64");
    }

    @Test
    void boshUrlForWindowsHasExeSuffix() {
        ToolSpec spec = ToolingVersions.bosh(HostPlatform.WINDOWS_AMD64);
        assertThat(spec.url().toString()).endsWith("-windows-amd64.exe");
    }

    @Test
    void cfUrlForDarwinArm64() {
        ToolSpec spec = ToolingVersions.cf(HostPlatform.DARWIN_ARM64);
        assertThat(spec.url().toString())
                .endsWith("/cf8-cli_" + ToolingVersions.CF_VERSION + "_macosarm.tgz");
        assertThat(spec.tarball()).isTrue();
        assertThat(spec.entryInTarball()).isEqualTo("cf8");
    }

    @Test
    void cfUrlForDarwinAmd64() {
        ToolSpec spec = ToolingVersions.cf(HostPlatform.DARWIN_AMD64);
        assertThat(spec.url().toString())
                .endsWith("/cf8-cli_" + ToolingVersions.CF_VERSION + "_osx.tgz");
    }

    @Test
    void cfUrlForLinuxAmd64() {
        ToolSpec spec = ToolingVersions.cf(HostPlatform.LINUX_AMD64);
        assertThat(spec.url().toString())
                .endsWith("/cf8-cli_" + ToolingVersions.CF_VERSION + "_linux_x86-64.tgz");
    }

    @Test
    void cfUrlForLinuxArm64() {
        ToolSpec spec = ToolingVersions.cf(HostPlatform.LINUX_ARM64);
        assertThat(spec.url().toString())
                .endsWith("/cf8-cli_" + ToolingVersions.CF_VERSION + "_linux_arm64.tgz");
    }

    @Test
    void cfUrlForWindowsUsesZipAndExeEntry() {
        ToolSpec spec = ToolingVersions.cf(HostPlatform.WINDOWS_AMD64);
        assertThat(spec.url().toString())
                .endsWith("/cf8-cli_" + ToolingVersions.CF_VERSION + "_winx64.zip");
        assertThat(spec.entryInTarball()).isEqualTo("cf8.exe");
    }
}
