package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.docker.CheckResult;
import com.dashaun.cfdockercpi.docker.VerificationReport;
import com.dashaun.cfdockercpi.docker.VerificationService;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
public class VerifyDockerStep implements SetupStep {

    static final String NAME = "verify-docker";
    private static final Duration FRESHNESS = Duration.ofHours(24);

    private final VerificationService verifier;
    private final StatusStore statusStore;

    public VerifyDockerStep(VerificationService verifier, StatusStore statusStore) {
        this.verifier = verifier;
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Probe the Docker host and confirm CPI suitability.";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) {
            return StepCheck.NEEDS_RUN;
        }
        try {
            Optional<StepStatus> persisted = statusStore.get(ctx.statusFile(), NAME);
            if (persisted.isEmpty()) return StepCheck.NEEDS_RUN;
            StepStatus s = persisted.get();
            if (s.status() != StepStatus.Status.PASS) return StepCheck.NEEDS_RUN;
            Instant ranAt = s.ranAtInstant();
            if (ranAt == null) return StepCheck.NEEDS_RUN;
            if (Duration.between(ranAt, Instant.now()).compareTo(FRESHNESS) > 0) {
                return StepCheck.NEEDS_RUN;
            }
            return StepCheck.ALREADY_DONE;
        } catch (IOException e) {
            return StepCheck.NEEDS_RUN;
        }
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException {
        VerificationReport report = verifier.verify(ctx.target());
        Path log = ctx.newLogFile(NAME);
        writeLog(log, report);

        String summary = summarize(report);
        StepStatus status = report.ok() ? StepStatus.pass(summary) : StepStatus.fail(summary);
        statusStore.put(ctx.statusFile(), NAME, status);

        if (!report.ok()) {
            return StepResult.failed(summary + " (log: " + log + ")");
        }
        return StepResult.ran(summary + " (log: " + log + ")");
    }

    private String summarize(VerificationReport report) {
        long fails = report.checks().stream()
                .filter(c -> c.status() == CheckResult.Status.FAIL).count();
        long warns = report.checks().stream()
                .filter(c -> c.status() == CheckResult.Status.WARN).count();
        long passes = report.checks().stream()
                .filter(c -> c.status() == CheckResult.Status.PASS).count();
        if (report.ok()) {
            return passes + " checks passed"
                    + (warns > 0 ? " (" + warns + " warn)" : "");
        }
        return fails + " check" + (fails == 1 ? "" : "s") + " failed, "
                + passes + " passed"
                + (warns > 0 ? ", " + warns + " warn" : "");
    }

    private void writeLog(Path log, VerificationReport report) throws IOException {
        Files.createDirectories(log.getParent());
        StringBuilder sb = new StringBuilder();
        sb.append("Target:        ").append(report.target()).append('\n');
        sb.append("Effective URI: ").append(report.effectiveUri()).append('\n');
        sb.append("Timestamp:     ").append(Instant.now()).append('\n');
        sb.append('\n');
        for (CheckResult c : report.checks()) {
            sb.append(padRight(c.name() + ":", 18))
              .append(' ')
              .append(padRight(c.status().name(), 6))
              .append("  ")
              .append(c.detail() == null ? "" : c.detail())
              .append('\n');
        }
        Files.writeString(log, sb.toString());
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(width);
        b.append(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }
}
