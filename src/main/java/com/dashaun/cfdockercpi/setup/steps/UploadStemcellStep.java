package com.dashaun.cfdockercpi.setup.steps;

import com.dashaun.cfdockercpi.setup.ManifestVersions;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupStep;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepCheck;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

@Component
public class UploadStemcellStep implements SetupStep {

    static final String NAME = "upload-stemcell";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";

    private final StatusStore statusStore;

    public UploadStemcellStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Upload the pinned warden stemcell to the director (idempotent; skipped by bosh if already present).";
    }

    @Override
    public StepCheck check(SetupContext ctx) {
        if (ctx.verify()) return StepCheck.NEEDS_RUN;
        try {
            Optional<StepStatus> s = statusStore.get(ctx.statusFile(), NAME);
            if (s.isPresent() && s.get().status() == StepStatus.Status.PASS) {
                return StepCheck.ALREADY_DONE;
            }
        } catch (IOException e) {
            // fall through
        }
        return StepCheck.NEEDS_RUN;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("upload-stemcell v1 supports ssh:// targets only; got " + ctx.target().uri());
        }

        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        String bootstrap = bootstrapScript(alias);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, userHost, sshPort, alias);
            int exit = streamRemote(userHost, sshPort, bootstrap, logOut);
            if (exit != 0) {
                String detail = "bosh upload-stemcell failed (ssh exit " + exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String summary = ManifestVersions.STEMCELL_NAME + "@" + ManifestVersions.STEMCELL_VERSION + " present on " + alias;
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String bootstrapScript(String alias) {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "if [ ! -f director-creds.yml ]; then\n"
            + "  echo \"ERROR: director-creds.yml missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "if ! [ -x ./bin/bosh ]; then\n"
            + "  echo \"ERROR: ./bin/bosh missing — run deploy-director\"; exit 78\n"
            + "fi\n"
            + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "\n"
            + "echo \"[stemcell] uploading " + ManifestVersions.STEMCELL_NAME
                    + "/" + ManifestVersions.STEMCELL_VERSION + " (bosh skips if already present)\"\n"
            + "./bin/bosh -e " + alias + " upload-stemcell '" + ManifestVersions.STEMCELL_URL + "' --no-color\n"
            + "\n"
            + "echo \"[stemcell] listing director stemcells\"\n"
            + "./bin/bosh -e " + alias + " stemcells --no-color\n";
    }

    private int streamRemote(String userHost, int sshPort, String bootstrap, BufferedWriter logOut)
            throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
                "ssh",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=10",
                "-o", "ServerAliveInterval=30",
                "-p", String.valueOf(sshPort),
                userHost,
                "bash -s");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try (var stdin = p.getOutputStream()) {
            stdin.write(bootstrap.getBytes(StandardCharsets.UTF_8));
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                System.out.println(line);
                logOut.write(line);
                logOut.newLine();
                logOut.flush();
            }
        }
        return p.waitFor();
    }

    private void header(BufferedWriter logOut, String userHost, int sshPort, String alias) throws IOException {
        logOut.write("Timestamp:   " + Instant.now() + "\n");
        logOut.write("Target:      ssh://" + userHost + ":" + sshPort + "\n");
        logOut.write("Alias:       " + alias + "\n");
        logOut.write("Stemcell:    " + ManifestVersions.STEMCELL_NAME + "@" + ManifestVersions.STEMCELL_VERSION + "\n");
        logOut.write("URL:         " + ManifestVersions.STEMCELL_URL + "\n");
        logOut.write("\n");
        logOut.flush();
    }
}
