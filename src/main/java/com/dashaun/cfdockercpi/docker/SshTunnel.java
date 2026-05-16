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

public final class SshTunnel implements AutoCloseable {

    private final Process process;
    private final int localPort;
    private final String userHost;
    private final String remoteSocket;
    private final Thread stderrPump;
    private final StringBuilder stderrBuffer = new StringBuilder();

    private SshTunnel(Process process, int localPort, String userHost, String remoteSocket) {
        this.process = process;
        this.localPort = localPort;
        this.userHost = userHost;
        this.remoteSocket = remoteSocket;
        this.stderrPump = new Thread(this::drainStderr, "ssh-tunnel-stderr");
        this.stderrPump.setDaemon(true);
        this.stderrPump.start();
    }

    public static SshTunnel open(DockerTarget target, Duration readyTimeout) throws IOException {
        if (!target.isSsh()) {
            throw new IllegalArgumentException("Target is not ssh: " + target.uri());
        }
        int port = freeLocalPort();
        String forward = port + ":" + target.remoteSocket();

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
        SshTunnel tunnel = new SshTunnel(p, port, target.sshUserHost(), target.remoteSocket());
        try {
            tunnel.waitUntilReady(readyTimeout);
        } catch (IOException e) {
            tunnel.close();
            throw e;
        }
        return tunnel;
    }

    public int localPort() {
        return localPort;
    }

    public String description() {
        return "ssh tunnel localhost:" + localPort + " -> " + userHost + ":" + remoteSocket;
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
                throw new IOException("ssh exited before tunnel was ready (exit "
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
                    throw new IOException("Interrupted waiting for ssh tunnel", e);
                }
            }
        }
        throw new IOException("Timed out waiting for ssh tunnel on localhost:" + localPort
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

    private static int freeLocalPort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        }
    }
}
