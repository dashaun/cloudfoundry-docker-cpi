package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.ManifestVersions;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

@Component
public class FetchManifestsStep implements SetupStep {

    static final String NAME = "fetch-manifests";

    private final StatusStore statusStore;

    public FetchManifestsStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Clone bosh-deployment and cf-deployment at pinned commits into the host state dir.";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        Path bosh = ctx.stateDir().resolve("bosh-deployment");
        Path cf = ctx.stateDir().resolve("cf-deployment");
        if (!isGitRepo(bosh)) return StepCheck.NEEDS_RUN;
        if (!isGitRepo(cf)) return StepCheck.NEEDS_RUN;
        if (!ManifestVersions.BOSH_DEPLOYMENT_SHA.equalsIgnoreCase(headSha(bosh))) return StepCheck.NEEDS_RUN;
        if (!ManifestVersions.CF_DEPLOYMENT_SHA.equalsIgnoreCase(headSha(cf))) return StepCheck.NEEDS_RUN;
        return StepCheck.ALREADY_DONE;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        StringBuilder log = new StringBuilder();
        log.append("Timestamp: ").append(Instant.now()).append('\n');
        log.append("State dir: ").append(ctx.stateDir()).append('\n').append('\n');

        Path bosh = ctx.stateDir().resolve("bosh-deployment");
        Path cf = ctx.stateDir().resolve("cf-deployment");

        try {
            cloneOrUpdate(ManifestVersions.BOSH_DEPLOYMENT_REPO, ManifestVersions.BOSH_DEPLOYMENT_SHA, bosh, log);
            cloneOrUpdate(ManifestVersions.CF_DEPLOYMENT_REPO, ManifestVersions.CF_DEPLOYMENT_SHA, cf, log);
        } catch (IOException | InterruptedException e) {
            writeLog(ctx, log);
            String detail = "git operation failed: " + e.getMessage();
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + ctx.logsDir() + ")");
        }

        String boshHead = headSha(bosh);
        String cfHead = headSha(cf);
        log.append('\n');
        log.append("bosh-deployment HEAD: ").append(boshHead).append('\n');
        log.append("cf-deployment  HEAD: ").append(cfHead).append('\n');
        Path logFile = writeLog(ctx, log);

        if (!ManifestVersions.BOSH_DEPLOYMENT_SHA.equalsIgnoreCase(boshHead)
                || !ManifestVersions.CF_DEPLOYMENT_SHA.equalsIgnoreCase(cfHead)) {
            String detail = "HEAD did not match pin after checkout (bosh=" + boshHead + ", cf=" + cfHead + ")";
            statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
            return StepResult.failed(detail + " (log: " + logFile + ")");
        }

        String summary = "bosh-deployment @ " + shortSha(boshHead) + " + cf-deployment @ " + shortSha(cfHead)
                + " (" + ManifestVersions.CF_DEPLOYMENT_TAG + ")";
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private void cloneOrUpdate(String repoUrl, String pinnedSha, Path dest, StringBuilder log)
            throws IOException, InterruptedException {
        Files.createDirectories(dest.getParent());
        if (isGitRepo(dest)) {
            log.append("[update] ").append(dest.getFileName()).append(" already cloned; fetching")
               .append('\n');
            runGit(dest, log, "git", "-C", dest.toString(), "fetch", "--all", "--quiet");
            log.append("[checkout] ").append(shortSha(pinnedSha)).append('\n');
            runGit(dest, log, "git", "-C", dest.toString(), "checkout", "--quiet", pinnedSha);
        } else {
            if (Files.exists(dest)) deleteRecursively(dest);
            log.append("[clone] ").append(repoUrl).append(" -> ").append(dest).append('\n');
            runGit(dest.getParent(), log, "git", "clone", "--quiet", repoUrl, dest.toString());
            log.append("[checkout] ").append(shortSha(pinnedSha)).append('\n');
            runGit(dest, log, "git", "-C", dest.toString(), "checkout", "--quiet", pinnedSha);
        }
    }

    private void runGit(Path cwd, StringBuilder log, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.directory(cwd.toFile());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        byte[] out;
        try (InputStream in = p.getInputStream()) {
            out = in.readAllBytes();
        }
        int exit = p.waitFor();
        if (out.length > 0) {
            log.append(new String(out, StandardCharsets.UTF_8));
        }
        if (exit != 0) {
            throw new IOException(String.join(" ", cmd) + " exited " + exit
                    + (out.length > 0 ? ": " + new String(out, StandardCharsets.UTF_8).trim() : ""));
        }
    }

    private boolean isGitRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"));
    }

    private String headSha(Path repo) {
        if (!isGitRepo(repo)) return "";
        try {
            Process p = new ProcessBuilder("git", "-C", repo.toString(), "rev-parse", "HEAD")
                    .redirectErrorStream(true)
                    .start();
            byte[] out;
            try (InputStream in = p.getInputStream()) {
                out = in.readAllBytes();
            }
            p.waitFor();
            return new String(out, StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    private String shortSha(String sha) {
        return sha == null || sha.length() < 8 ? sha : sha.substring(0, 8);
    }

    private Path writeLog(SetupContext ctx, StringBuilder log) throws IOException {
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());
        Files.writeString(logFile, log.toString());
        return logFile;
    }

    private void deleteRecursively(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        }
    }
}
