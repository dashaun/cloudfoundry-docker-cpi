package com.dashaun.cfdockercpi.docker;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.api.model.Version;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Service
public class VerificationService {

    private static final Set<String> SUPPORTED_ARCHITECTURES = Set.of("x86_64", "amd64");

    private final DockerClientFactory clientFactory;

    public VerificationService(DockerClientFactory clientFactory) {
        this.clientFactory = clientFactory;
    }

    public VerificationReport verify(DockerTarget target) {
        List<CheckResult> checks = new ArrayList<>();
        String effectiveUri = "(not connected)";

        try (DockerSession session = clientFactory.open(target)) {
            effectiveUri = session.effectiveUri();
            DockerClient client = session.client();

            Duration ping = ping(client, checks);
            if (ping == null) {
                return new VerificationReport(describe(target), effectiveUri, checks);
            }

            Version version = serverVersion(client, checks);
            Info info = info(client, checks);

            if (info != null) {
                hostOs(info, checks);
                architecture(info, checks);
                resources(info, checks);
                cpiPrereqs(info, checks);
            }
            if (version != null) {
                apiVersion(version, checks);
            }
        } catch (IOException e) {
            checks.add(CheckResult.fail("Reachability",
                    "Could not open connection: " + e.getMessage()));
        } catch (RuntimeException e) {
            checks.add(CheckResult.fail("Reachability",
                    "Unexpected error: " + e.getClass().getSimpleName() + ": " + e.getMessage()));
        }

        return new VerificationReport(describe(target), effectiveUri, checks);
    }

    private Duration ping(DockerClient client, List<CheckResult> checks) {
        Instant start = Instant.now();
        try {
            client.pingCmd().exec();
            Duration elapsed = Duration.between(start, Instant.now());
            checks.add(CheckResult.pass("Reachability", "ping " + elapsed.toMillis() + "ms"));
            return elapsed;
        } catch (Exception e) {
            checks.add(CheckResult.fail("Reachability",
                    "Daemon did not respond to ping: " + e.getMessage()));
            return null;
        }
    }

    private Version serverVersion(DockerClient client, List<CheckResult> checks) {
        try {
            Version v = client.versionCmd().exec();
            checks.add(CheckResult.pass("Server",
                    "Docker " + v.getVersion() + ", API " + v.getApiVersion()));
            return v;
        } catch (Exception e) {
            checks.add(CheckResult.fail("Server", "version query failed: " + e.getMessage()));
            return null;
        }
    }

    private Info info(DockerClient client, List<CheckResult> checks) {
        try {
            return client.infoCmd().exec();
        } catch (Exception e) {
            checks.add(CheckResult.fail("Daemon info", "info query failed: " + e.getMessage()));
            return null;
        }
    }

    private void hostOs(Info info, List<CheckResult> checks) {
        String os = info.getOsType();
        String kernel = info.getKernelVersion();
        String operatingSystem = info.getOperatingSystem();
        if (os == null) {
            checks.add(CheckResult.warn("Host OS", "OS type not reported"));
            return;
        }
        if (!"linux".equalsIgnoreCase(os)) {
            checks.add(CheckResult.fail("Host OS",
                    os + " (Docker CPI requires Linux)"));
            return;
        }
        String detail = operatingSystem != null ? operatingSystem : "Linux";
        if (kernel != null) {
            detail += " (kernel " + kernel + ")";
        }
        checks.add(CheckResult.pass("Host OS", detail));
    }

    private void architecture(Info info, List<CheckResult> checks) {
        String arch = info.getArchitecture();
        if (arch == null) {
            checks.add(CheckResult.warn("Architecture", "architecture not reported"));
            return;
        }
        if (SUPPORTED_ARCHITECTURES.contains(arch.toLowerCase())) {
            checks.add(CheckResult.pass("Architecture", arch));
        } else {
            checks.add(CheckResult.fail("Architecture",
                    arch + " (Docker CPI requires x86_64/amd64)"));
        }
    }

    private void resources(Info info, List<CheckResult> checks) {
        Integer cpus = info.getNCPU();
        Long memBytes = info.getMemTotal();
        StringBuilder sb = new StringBuilder();
        if (cpus != null) {
            sb.append(cpus).append(" CPUs");
        }
        if (memBytes != null) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(formatBytes(memBytes)).append(" RAM");
        }
        if (sb.length() == 0) {
            checks.add(CheckResult.warn("Resources", "no resource info reported"));
            return;
        }
        checks.add(CheckResult.pass("Resources", sb.toString()));
    }

    private void cpiPrereqs(Info info, List<CheckResult> checks) {
        String driver = info.getDriver();
        String cgroupDriver = info.getCGroupDriver();
        String cgroupVersion = info.getCGroupVersion();

        List<String> notes = new ArrayList<>();
        if (driver != null) notes.add("storage=" + driver);
        if (cgroupDriver != null) notes.add("cgroup-driver=" + cgroupDriver);
        if (cgroupVersion != null) notes.add("cgroup-v" + cgroupVersion);

        if (notes.isEmpty()) {
            checks.add(CheckResult.warn("CPI prereqs", "no driver info reported"));
            return;
        }
        checks.add(CheckResult.pass("CPI prereqs", String.join(", ", notes)));
    }

    private void apiVersion(Version version, List<CheckResult> checks) {
        String api = version.getApiVersion();
        if (api == null) return;
        if (compareApi(api, "1.41") >= 0) {
            checks.add(CheckResult.pass("API version", api + " (>= 1.41 required)"));
        } else {
            checks.add(CheckResult.fail("API version", api + " is too old (need >= 1.41)"));
        }
    }

    private int compareApi(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        for (int i = 0; i < Math.max(as.length, bs.length); i++) {
            int av = i < as.length ? safeParse(as[i]) : 0;
            int bv = i < bs.length ? safeParse(bs[i]) : 0;
            if (av != bv) return Integer.compare(av, bv);
        }
        return 0;
    }

    private int safeParse(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; }
    }

    private String formatBytes(long bytes) {
        double gib = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format("%.1f GiB", gib);
    }

    private String describe(DockerTarget target) {
        return target.uri() + "  (from " + target.source().name().toLowerCase() + ")";
    }
}
