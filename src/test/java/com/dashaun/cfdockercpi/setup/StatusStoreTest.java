package com.dashaun.cfdockercpi.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class StatusStoreTest {

    @Test
    void loadReturnsEmptyWhenFileMissing(@TempDir Path tmp) throws Exception {
        StatusStore store = new StatusStore();
        StatusStore.StatusFile loaded = store.load(tmp.resolve("status.json"));
        assertThat(loaded.steps()).isEmpty();
    }

    @Test
    void roundTripPreservesStepStatus(@TempDir Path tmp) throws Exception {
        StatusStore store = new StatusStore();
        Path file = tmp.resolve("status.json");

        StepStatus original = StepStatus.pass("8 checks passed");
        store.put(file, "verify-docker", original);

        StepStatus reloaded = store.get(file, "verify-docker").orElseThrow();
        assertThat(reloaded.status()).isEqualTo(StepStatus.Status.PASS);
        assertThat(reloaded.detail()).isEqualTo("8 checks passed");
        assertThat(reloaded.ranAtInstant()).isNotNull();
    }

    @Test
    void putPreservesPriorSteps(@TempDir Path tmp) throws Exception {
        StatusStore store = new StatusStore();
        Path file = tmp.resolve("status.json");

        store.put(file, "verify-docker", StepStatus.pass("ok"));
        store.put(file, "install-tools", StepStatus.fail("download error"));

        assertThat(store.get(file, "verify-docker").orElseThrow().status())
                .isEqualTo(StepStatus.Status.PASS);
        assertThat(store.get(file, "install-tools").orElseThrow().status())
                .isEqualTo(StepStatus.Status.FAIL);
    }

    @Test
    void updateOverwritesSameStep(@TempDir Path tmp) throws Exception {
        StatusStore store = new StatusStore();
        Path file = tmp.resolve("status.json");

        store.put(file, "verify-docker", StepStatus.fail("first"));
        store.put(file, "verify-docker", StepStatus.pass("second"));

        StepStatus latest = store.get(file, "verify-docker").orElseThrow();
        assertThat(latest.status()).isEqualTo(StepStatus.Status.PASS);
        assertThat(latest.detail()).isEqualTo("second");
    }
}
