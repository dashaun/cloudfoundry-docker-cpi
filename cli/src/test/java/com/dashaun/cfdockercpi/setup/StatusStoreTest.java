package com.dashaun.cfdockercpi.setup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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

    @Test
    void serviceMapIsIndependentOfStepMap(@TempDir Path tmp) throws Exception {
        StatusStore store = new StatusStore();
        Path file = tmp.resolve("status.json");

        store.put(file, "verify-docker", StepStatus.pass("ok"));
        store.putService(file, "postgres-single",
                StepStatus.pass("broker=https://cf-docker-cpi-broker.bosh-lite.com"));

        // The two maps don't bleed into each other.
        assertThat(store.get(file, "postgres-single")).isEmpty();
        assertThat(store.getService(file, "verify-docker")).isEmpty();
        assertThat(store.getService(file, "postgres-single").orElseThrow().status())
                .isEqualTo(StepStatus.Status.PASS);
        assertThat(store.get(file, "verify-docker").orElseThrow().status())
                .isEqualTo(StepStatus.Status.PASS);
    }

    @Test
    void loadsLegacyStatusFileWithoutServicesField(@TempDir Path tmp) throws Exception {
        // Files written before the services field existed had `{"steps": {...}}` only. They
        // must still deserialize cleanly with an empty services map.
        StatusStore store = new StatusStore();
        Path file = tmp.resolve("status.json");
        Files.writeString(file, """
                {
                  "steps" : {
                    "verify-docker" : {
                      "status" : "PASS",
                      "ranAt" : "2026-05-20T00:26:42.662187Z",
                      "detail" : "7 checks passed"
                    }
                  }
                }
                """);

        StatusStore.StatusFile loaded = store.load(file);
        assertThat(loaded.steps()).containsKey("verify-docker");
        assertThat(loaded.services()).isEmpty();

        // And a putService on the same file backfills the services map without losing steps.
        store.putService(file, "postgres-single", StepStatus.pass("broker registered"));
        assertThat(store.get(file, "verify-docker").orElseThrow().detail()).isEqualTo("7 checks passed");
        assertThat(store.getService(file, "postgres-single").orElseThrow().detail())
                .isEqualTo("broker registered");
    }
}
