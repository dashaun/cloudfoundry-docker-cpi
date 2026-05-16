package com.dashaun.cfdockercpi.commands;

import com.dashaun.cfdockercpi.docker.CheckResult;
import com.dashaun.cfdockercpi.docker.DockerTarget;
import com.dashaun.cfdockercpi.docker.DockerTargetResolver;
import com.dashaun.cfdockercpi.docker.VerificationReport;
import com.dashaun.cfdockercpi.docker.VerificationService;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
@CommandGroup(prefix = "docker", name = "Docker", description = "Operations against a Docker host")
public class DockerCommands {

    private final DockerTargetResolver resolver;
    private final VerificationService verifier;

    public DockerCommands(DockerTargetResolver resolver, VerificationService verifier) {
        this.resolver = resolver;
        this.verifier = verifier;
    }

    @Command(name = "verify", description = "Verify connection to a Docker host")
    public String verify(
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host (ssh://user@host, tcp://host:2375, unix:///path, or a bare hostname which defaults to ssh://)")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) {

        DockerTarget target = resolver.resolve(host, remoteSocket);
        VerificationReport report = verifier.verify(target);
        return render(report);
    }

    private String render(VerificationReport report) {
        int width = 14;
        StringBuilder sb = new StringBuilder();
        sb.append("Target:        ").append(report.target()).append('\n');
        sb.append("Effective URI: ").append(report.effectiveUri()).append('\n');
        sb.append('\n');
        for (CheckResult c : report.checks()) {
            sb.append(pad(c.name() + ":", width))
              .append(' ')
              .append(pad(c.status().name(), 6))
              .append("  ")
              .append(c.detail() == null ? "" : c.detail())
              .append('\n');
        }
        sb.append('\n');
        sb.append(report.ok()
                ? "Ready for CloudFoundry Docker CPI deployment."
                : "One or more checks failed - see details above.");
        return sb.toString();
    }

    private String pad(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(width);
        b.append(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }
}
