package com.dashaun.cfdockercpi.setup.steps;

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
public class LoginDirectorStep implements SetupStep {

    static final String NAME = "login-director";
    static final String REMOTE_WORK_DIR = ".cf-docker-cpi-work";
    static final int DIRECTOR_PORT = 25555;

    private final StatusStore statusStore;

    public LoginDirectorStep(StatusStore statusStore) {
        this.statusStore = statusStore;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Register a bosh alias-env on the docker host and verify the director is reachable and admin auth works.";
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
            // fall through to NEEDS_RUN
        }
        return StepCheck.NEEDS_RUN;
    }

    @Override
    public StepResult run(SetupContext ctx) throws IOException, InterruptedException {
        if (!ctx.target().isSsh()) {
            return StepResult.failed("login-director v1 supports ssh:// targets only; got " + ctx.target().uri());
        }
        Path credsLocal = ctx.stateDir().resolve("director-creds.yml");
        if (!Files.isRegularFile(credsLocal)) {
            return StepResult.failed("director-creds.yml not found in state dir — run deploy-director first");
        }

        String userHost = ctx.target().sshUserHost();
        int sshPort = ctx.target().sshPort();
        String alias = SetupContext.DEFAULT_DIRECTOR_NAME;
        Path logFile = ctx.newLogFile(NAME);
        Files.createDirectories(logFile.getParent());

        String bootstrap = bootstrapScript(ctx, alias);
        try (BufferedWriter logOut = Files.newBufferedWriter(logFile)) {
            header(logOut, ctx, userHost, sshPort, alias);
            int exit = streamRemote(userHost, sshPort, bootstrap, logOut);
            if (exit != 0) {
                String detail = "bosh alias-env / auth probe failed (ssh exit " + exit + ")";
                statusStore.put(ctx.statusFile(), NAME, StepStatus.fail(detail));
                return StepResult.failed(detail + " (log: " + logFile + ")");
            }
        }

        String summary = "alias " + alias + " -> https://" + ctx.directorIp() + ":" + DIRECTOR_PORT;
        statusStore.put(ctx.statusFile(), NAME, StepStatus.pass(summary));
        return StepResult.ran(summary + " (log: " + logFile + ")");
    }

    private String bootstrapScript(SetupContext ctx, String alias) {
        return "set -euo pipefail\n"
            + "cd ~/" + REMOTE_WORK_DIR + "\n"
            + "\n"
            + "if [ ! -f director-creds.yml ]; then\n"
            + "  echo \"ERROR: director-creds.yml not found in ~/" + REMOTE_WORK_DIR + "\"\n"
            + "  echo \"Re-run deploy-director first.\"\n"
            + "  exit 78\n"
            + "fi\n"
            + "\n"
            + "echo \"[login] extracting director CA cert\"\n"
            + "./bin/bosh interpolate director-creds.yml --path /director_ssl/ca > ca-cert.pem\n"
            + "\n"
            + "echo \"[login] registering alias " + alias + "\"\n"
            + "./bin/bosh alias-env " + alias + " \\\n"
            + "  -e https://" + ctx.directorIp() + ":" + DIRECTOR_PORT + " \\\n"
            + "  --ca-cert ca-cert.pem\n"
            + "\n"
            + "BOSH_CLIENT=admin\n"
            + "BOSH_CLIENT_SECRET=\"$(./bin/bosh interpolate director-creds.yml --path /admin_password)\"\n"
            + "export BOSH_CLIENT BOSH_CLIENT_SECRET\n"
            + "\n"
            + "echo \"[login] verifying reachability + TLS via bosh -e " + alias + " env\"\n"
            + "./bin/bosh -e " + alias + " env\n"
            + "\n"
            + "echo \"[login] verifying admin auth via bosh -e " + alias + " tasks --recent\"\n"
            + "./bin/bosh -e " + alias + " tasks --recent=5 --no-color\n";
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

    private void header(BufferedWriter logOut, SetupContext ctx, String userHost, int sshPort, String alias)
            throws IOException {
        logOut.write("Timestamp:   " + Instant.now() + "\n");
        logOut.write("Target:      ssh://" + userHost + ":" + sshPort + "\n");
        logOut.write("Remote dir:  ~/" + REMOTE_WORK_DIR + "\n");
        logOut.write("Director:    https://" + ctx.directorIp() + ":" + DIRECTOR_PORT + "\n");
        logOut.write("Alias:       " + alias + "\n");
        logOut.write("\n");
        logOut.flush();
    }
}
