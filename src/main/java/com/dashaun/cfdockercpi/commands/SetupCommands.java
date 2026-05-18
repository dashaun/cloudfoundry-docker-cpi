package com.dashaun.cfdockercpi.commands;

import com.dashaun.cfdockercpi.docker.DockerTarget;
import com.dashaun.cfdockercpi.docker.DockerTargetResolver;
import com.dashaun.cfdockercpi.setup.HostSlug;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.SetupOrchestrator;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

@Component
@CommandGroup(prefix = "setup", name = "Setup", description = "Cloud Foundry on Docker CPI setup pipeline")
public class SetupCommands {

    private final DockerTargetResolver resolver;
    private final SetupOrchestrator orchestrator;

    public SetupCommands(DockerTargetResolver resolver, SetupOrchestrator orchestrator) {
        this.resolver = resolver;
        this.orchestrator = orchestrator;
    }

    @Command(name = "step", description = "Run a single setup step by name")
    public String step(
            @Option(longName = "name", shortName = 'n',
                    description = "Step name (e.g. verify-docker)")
            String name,
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket,
            @Option(longName = "verify",
                    description = "Force a deep re-check instead of trusting cached status")
            boolean verify,
            @Option(longName = "force",
                    description = "Run the step even if check() says ALREADY_DONE")
            boolean force,
            @Option(longName = "system-domain",
                    description = "CF system domain (used by deploy-cf onward)",
                    defaultValue = SetupContext.DEFAULT_SYSTEM_DOMAIN)
            String systemDomain,
            @Option(longName = "ignore-resource-check",
                    description = "Bypass the deploy-cf 16 GiB RAM / 50 GiB disk precheck")
            boolean ignoreResourceCheck,
            @Option(longName = "write-hosts",
                    description = "configure-cf-cli: append missing api/login/uaa/cf-smoke "
                            + "hostnames to /etc/hosts (requires interactive sudo on macOS/Linux)")
            boolean writeHosts) throws Exception {

        if (name == null || name.isBlank()) {
            return "ERROR: --name is required.\nKnown steps: " + String.join(", ", orchestrator.stepNames());
        }
        SetupContext ctx = buildContext(host, remoteSocket, verify, force,
                systemDomain, ignoreResourceCheck, writeHosts);
        StepResult result = orchestrator.runStep(name, ctx);
        return renderResult(ctx, name, result);
    }

    @Command(name = "status", description = "Show per-step status for a Docker host")
    public String status(
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) throws Exception {

        SetupContext ctx = buildContext(host, remoteSocket, false, false,
                SetupContext.DEFAULT_SYSTEM_DOMAIN, false, false);
        Map<String, StepStatus> persisted = orchestrator.status(ctx);
        return renderStatus(ctx, orchestrator.stepNames(), persisted);
    }

    private SetupContext buildContext(String host, String remoteSocket, boolean verify, boolean force,
                                      String systemDomain, boolean ignoreResourceCheck,
                                      boolean writeHosts) {
        DockerTarget target = resolver.resolve(host, remoteSocket);
        String slug = HostSlug.from(target);
        Path home = Paths.get(System.getProperty("user.home"));
        Path stateDir = home.resolve(".cf-docker-cpi").resolve("hosts").resolve(slug);
        Path binDir = home.resolve(".cf-docker-cpi").resolve("bin");
        return new SetupContext(target, slug, stateDir, binDir, verify, force,
                SetupContext.DEFAULT_DIRECTOR_IP, SetupContext.DEFAULT_INTERNAL_CIDR,
                systemDomain == null || systemDomain.isBlank()
                        ? SetupContext.DEFAULT_SYSTEM_DOMAIN : systemDomain,
                ignoreResourceCheck, writeHosts);
    }

    private String renderResult(SetupContext ctx, String name, StepResult result) {
        return "Step:          " + name + '\n'
             + "Target:        " + ctx.target().uri() + "  (from " + ctx.target().source().name().toLowerCase() + ")\n"
             + "Host slug:     " + ctx.hostSlug() + '\n'
             + "State dir:     " + ctx.stateDir() + '\n'
             + "Outcome:       " + result.outcome() + '\n'
             + "Detail:        " + result.detail() + '\n';
    }

    private String renderStatus(SetupContext ctx, List<String> stepNames, Map<String, StepStatus> persisted) {
        StringBuilder sb = new StringBuilder();
        sb.append("Target:        ").append(ctx.target().uri())
          .append("  (from ").append(ctx.target().source().name().toLowerCase()).append(")\n");
        sb.append("Host slug:     ").append(ctx.hostSlug()).append('\n');
        sb.append("State dir:     ").append(ctx.stateDir()).append('\n');
        sb.append('\n');
        int width = 22;
        for (String name : stepNames) {
            StepStatus s = persisted.get(name);
            String status = s == null ? "UNRUN" : s.status().name();
            String when = s == null || s.ranAt() == null ? "" : "  " + s.ranAt();
            String detail = s == null || s.detail() == null ? "" : "  " + s.detail();
            sb.append(padRight(name + ":", width))
              .append(' ')
              .append(padRight(status, 6))
              .append(when)
              .append(detail)
              .append('\n');
        }
        return sb.toString();
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(width);
        b.append(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }
}
