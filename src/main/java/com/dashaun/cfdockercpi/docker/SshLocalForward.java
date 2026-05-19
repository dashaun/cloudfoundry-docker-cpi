package com.dashaun.cfdockercpi.docker;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

// Like SshTunnel, but forwards localhost:<port> to a TCP <remoteHost>:<remotePort> on the SSH
// target instead of to a unix socket path. configure-cf-cli and smoke-push use this to reach
// the cf haproxy router (10.245.0.34:443) on the cf-docker-cpi-net bridge, which is only
// routable from the docker host itself.
public final class SshLocalForward implements AutoCloseable {

    private final Process process;
    private final int localPort;
    private final String userHost;
    private final String remoteHost;
    private final int remotePort;
    private final Thread stderrPump;
    private final StringBuilder stderrBuffer = new StringBuilder();

    private SshLocalForward(Process process, int localPort, String userHost,
                            String remoteHost, int remotePort) {
        this.process = process;
        this.localPort = localPort;
        this.userHost = userHost;
        this.remoteHost = remoteHost;
        this.remotePort = remotePort;
        this.stderrPump = new Thread(this::drainStderr, "ssh-forward-stderr");
        this.stderrPump.setDaemon(true);
        this.stderrPump.start();
    }

    public static SshLocalForward open(DockerTarget target, String remoteHost, int remotePort,
                                       int preferredLocalPort, Duration readyTimeout) throws IOException {
        if (!target.isSsh()) {
            throw new IllegalArgumentException("Target is not ssh: " + target.uri());
        }
        int port = pickLocalPort(preferredLocalPort);
        String forward = port + ":" + remoteHost + ":" + remotePort;

        List<String> cmd = new ArrayList<>(List.of(
                "ssh",
                "-N",
                "-o", "ExitOnForwardFailure=yes",
                "-o", "ServerAliveInterval=30",
                "-o", "ConnectTimeout=10",
                "-L", forward
        ));
        if (target.sshPort() != 22) {
            cmd.add("-p");
            cmd.add(Integer.toString(target.sshPort()));
        }
        cmd.add(target.sshUserHost());

        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        Process p = pb.start();
        SshLocalForward fwd = new SshLocalForward(p, port, target.sshUserHost(), remoteHost, remotePort);
        try {
            fwd.waitUntilReady(readyTimeout);
        } catch (IOException e) {
            fwd.close();
            throw e;
        }
        return fwd;
    }

    public int localPort() {
        return localPort;
    }

    public String description() {
        return "ssh -L localhost:" + localPort + " -> " + userHost + " -> "
                + remoteHost + ":" + remotePort;
    }

    public String stderrTail() {
        synchronized (stderrBuffer) {
            return stderrBuffer.toString();
        }
    }

    @Override
    public void close() {
        if (process.isAlive()) {
            process.destroy();
            try {
                if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void waitUntilReady(Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new IOException("ssh exited before forward was ready (exit "
                        + process.exitValue() + "): " + stderrTail().trim());
            }
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", localPort), 250);
                return;
            } catch (IOException ignored) {
                try {
                    Thread.sleep(150);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted waiting for ssh forward", e);
                }
            }
        }
        throw new IOException("Timed out waiting for ssh forward on localhost:" + localPort
                + "; ssh stderr: " + stderrTail().trim());
    }

    private void drainStderr() {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                synchronized (stderrBuffer) {
                    stderrBuffer.append(line).append('\n');
                    if (stderrBuffer.length() > 4096) {
                        stderrBuffer.delete(0, stderrBuffer.length() - 4096);
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    // Try preferredLocalPort first (so the cf api URL has a stable port across runs and the
    // user can `export CF_HOME=... ; cf <whatever>` later); fall back to any free port if it's
    // already bound (likely a stale tunnel from a prior run still listening).
    private static int pickLocalPort(int preferred) throws IOException {
        if (preferred > 0) {
            try (ServerSocket s = new ServerSocket(preferred)) {
                s.setReuseAddress(true);
                return s.getLocalPort();
            } catch (IOException ignored) {
            }
        }
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
