package com.dashaun.cfdockercpi.setup;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ManifestVersionsTest {

    @Test
    void boshDeploymentRepoIsHttps() {
        assertThat(ManifestVersions.BOSH_DEPLOYMENT_REPO)
                .isEqualTo("https://github.com/cloudfoundry/bosh-deployment.git");
    }

    @Test
    void cfDeploymentRepoIsHttps() {
        assertThat(ManifestVersions.CF_DEPLOYMENT_REPO)
                .isEqualTo("https://github.com/cloudfoundry/cf-deployment.git");
    }

    @Test
    void pinnedShasAreFortyHex() {
        assertThat(ManifestVersions.BOSH_DEPLOYMENT_SHA).matches("[0-9a-f]{40}");
        assertThat(ManifestVersions.CF_DEPLOYMENT_SHA).matches("[0-9a-f]{40}");
    }

    @Test
    void cfDeploymentTagIsSemverWithVPrefix() {
        assertThat(ManifestVersions.CF_DEPLOYMENT_TAG).matches("v[0-9]+\\.[0-9]+\\.[0-9]+");
    }
}
