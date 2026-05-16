package com.dashaun.cfdockercpi.tooling;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HostPlatformTest {

    @Test
    void detectsMacAppleSilicon() {
        assertThat(HostPlatform.detect("Mac OS X", "aarch64")).isEqualTo(HostPlatform.DARWIN_ARM64);
    }

    @Test
    void detectsMacIntel() {
        assertThat(HostPlatform.detect("Mac OS X", "x86_64")).isEqualTo(HostPlatform.DARWIN_AMD64);
    }

    @Test
    void detectsLinuxAmd64() {
        assertThat(HostPlatform.detect("Linux", "amd64")).isEqualTo(HostPlatform.LINUX_AMD64);
    }

    @Test
    void detectsLinuxArm64() {
        assertThat(HostPlatform.detect("Linux", "aarch64")).isEqualTo(HostPlatform.LINUX_ARM64);
    }

    @Test
    void detectsWindowsAmd64() {
        assertThat(HostPlatform.detect("Windows 11", "amd64")).isEqualTo(HostPlatform.WINDOWS_AMD64);
    }

    @Test
    void unsupportedPlatformFailsClearly() {
        assertThatThrownBy(() -> HostPlatform.detect("Solaris", "sparc"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Solaris")
                .hasMessageContaining("sparc");
    }
}
