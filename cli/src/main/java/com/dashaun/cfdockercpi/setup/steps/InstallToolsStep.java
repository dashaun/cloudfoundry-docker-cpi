package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import com.dashaun.cfdockercpi.tooling.HostPlatform;
import com.dashaun.cfdockercpi.tooling.ToolDownloader;
import com.dashaun.cfdockercpi.tooling.ToolSpec;
import com.dashaun.cfdockercpi.tooling.ToolingVersions;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

@Component
public class InstallToolsStep implements SetupStep {

    static final String NAME = "install-tools";

    private final ToolDownloader downloader;
    private final StatusStore statusStore;

    public InstallToolsStep(ToolDownloader downloader, StatusStore statusStore) {
        this.downloader = downloader;
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Download pinned bosh + cf CLIs into ~/.cf-docker-cpi/bin/.";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        HostPlatform platform;
        try {
            platform = HostPlatform.detect();
        } catch (RuntimeException e) {
            return StepCheck.NEEDS_RUN;
        }
        Path bosh = ctx.binDir().resolve(binaryName("bosh", platform));
        Path cf = ctx.binDir().resolve(binaryName("cf", platform));
        if (!Files.exists(bosh) || !Files.exists(cf)) return StepCheck.NEEDS_RUN;
        if (!versionMatches(bosh, ToolingVersions.BOSH_VERSION)) return StepCheck.NEEDS_RUN;
        if (!versionMatches(cf, ToolingVersions.CF_VERSION)) return StepCheck.NEEDS_RUN;
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        HostPlatform platform = HostPlatform.detect();
        StringBuilder log = new StringBuilder();
        log.append("Timestamp: ").append(Instant.now()).append('\n');
        log.append("Platform:  ").append(platform).append('\n');
        log.append("Bin dir:   ").append(ctx.binDir()).append('\n');
        log.append('\n');

        ToolSpec boshSpec = ToolingVersions.bosh(platform);
        ToolSpec cfSpec = ToolingVersions.cf(platform);

        Path boshDest = ctx.binDir().resolve(binaryName("bosh", platform));
        Path cfDest = ctx.binDir().resolve(binaryName("cf", platform));

        log.append("Installing bosh ").append(boshSpec.version())
           .append(" from ").append(boshSpec.url()).append('\n');
        downloader.install(boshSpec, boshDest);
        String boshVersion = runVersion(boshDest);
        log.append("  -> ").append(boshDest).append('\n');
        log.append("  --version: ").append(boshVersion).append('\n');
        log.append('\n');

        log.append("Installing cf ").append(cfSpec.version())
           .append(" from ").append(cfSpec.url()).append('\n');
        downloader.install(cfSpec, cfDest);
        String cfVersion = runVersion(cfDest);
        log.append("  -> ").append(cfDest).append('\n');
        log.append("  --version: ").append(cfVersion).append('\n');

        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, log.toString());

        if (!boshVersion.contains(ToolingVersions.BOSH_VERSION)) {
            String detail = "bosh installed but --version output did not contain " + ToolingVersions.BOSH_VERSION
                    + " (got: " + boshVersion + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }
        if (!cfVersion.contains(ToolingVersions.CF_VERSION)) {
            String detail = "cf installed but --version output did not contain " + ToolingVersions.CF_VERSION
                    + " (got: " + cfVersion + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        String summary = "bosh " + boshSpec.version() + " + cf " + cfSpec.version() + " installed";
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String binaryName(String tool, HostPlatform platform) {
        return platform.isWindows() ? tool + ".exe" : tool;
    }

    private boolean versionMatches(Path tool, String expectedVersion) {
        try {
            return runVersion(tool).contains(expectedVersion);
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String runVersion(Path tool) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(tool.toString(), "--version");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        p.waitFor();
        return new String(out, StandardCharsets.UTF_8).trim();
    }
}
