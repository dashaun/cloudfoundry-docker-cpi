package com.dashaun.cfdockercpi.setup;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class HostSlugTest {

    @Test
    void unixSocketIsLocal() {
        assertThat(HostSlug.from(URI.create("unix:///var/run/docker.sock")))
                .isEqualTo("unix-local");
    }

    @Test
    void sshSimpleHost() {
        assertThat(HostSlug.from(URI.create("ssh://zephyrus-2")))
                .isEqualTo("ssh-zephyrus-2");
    }

    @Test
    void sshUserAtHostStripsUser() {
        assertThat(HostSlug.from(URI.create("ssh://user@zephyrus-2")))
                .isEqualTo("ssh-zephyrus-2");
    }

    @Test
    void sshIncludesNonDefaultPort() {
        assertThat(HostSlug.from(URI.create("ssh://zephyrus-2:2222")))
                .isEqualTo("ssh-zephyrus-2-2222");
    }

    @Test
    void tcpWithIpAndPort() {
        assertThat(HostSlug.from(URI.create("tcp://192.168.1.10:2375")))
                .isEqualTo("tcp-192-168-1-10-2375");
    }

    @Test
    void uppercaseSchemeAndHost() {
        assertThat(HostSlug.from(URI.create("SSH://Zephyrus-2")))
                .isEqualTo("ssh-zephyrus-2");
    }
}
