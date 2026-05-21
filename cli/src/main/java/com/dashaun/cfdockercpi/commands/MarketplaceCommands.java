package com.dashaun.cfdockercpi.commands;

import com.dashaun.cfdockercpi.docker.DockerTarget;
import com.dashaun.cfdockercpi.docker.DockerTargetResolver;
import com.dashaun.cfdockercpi.marketplace.ServiceManager;
import com.dashaun.cfdockercpi.setup.HostSlug;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.StatusStore;
import com.dashaun.cfdockercpi.setup.StepResult;
import com.dashaun.cfdockercpi.setup.StepStatus;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * Spring Shell commands for managing which service plans show up in the marketplace.
 *
 * <pre>
 *   service add    --name postgres --host ssh://...
 *   service list   --host ssh://...
 *   service remove --name postgres --host ssh://...
 * </pre>
 *
 * The broker must be deployed first ({@code broker deploy}); each {@code service add} just
 * toggles cf's {@code enable-service-access} for that offering. {@code service list} reads the
 * tool's {@code status.json} {@code services} map.
 */
@Component
@CommandGroup(prefix = "service", name = "Service",
        description = "Toggle marketplace plans (postgres / redis / rabbitmq / minio) once the broker is deployed.")
public class MarketplaceCommands {

    private final DockerTargetResolver resolver;
    private final ServiceManager services;

    public MarketplaceCommands(DockerTargetResolver resolver, ServiceManager services) {
        this.resolver = resolver;
        this.services = services;
    }

    @Command(name = "add", description = "Enable a service plan in the marketplace")
    public String add(
            @Option(longName = "name", shortName = 'n',
                    description = "Service to enable: postgres, redis, rabbitmq, minio (alias for the *-single plans)")
            String name,
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) throws Exception {

        if (name == null || name.isBlank()) {
            return "ERROR: --name is required. Known: " + services.knownServices();
        }
        SetupContext ctx = buildContext(host, remoteSocket);
        StepResult r = services.add(ctx, name);
        return renderResult(ctx, "service add " + name, r);
    }

    @Command(name = "remove", description = "Disable a service plan in the marketplace (broker app stays deployed)")
    public String remove(
            @Option(longName = "name", shortName = 'n',
                    description = "Service to disable")
            String name,
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) throws Exception {

        if (name == null || name.isBlank()) {
            return "ERROR: --name is required. Known: " + services.knownServices();
        }
        SetupContext ctx = buildContext(host, remoteSocket);
        StepResult r = services.remove(ctx, name);
        return renderResult(ctx, "service remove " + name, r);
    }

    @Command(name = "list", description = "Show per-service status from the local status.json")
    public String list(
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) throws Exception {

        SetupContext ctx = buildContext(host, remoteSocket);
        Map<String, StepStatus> svcs = services.list(ctx);

        StringBuilder sb = new StringBuilder();
        sb.append("Target:        ").append(ctx.target().uri()).append('\n');
        sb.append("Host slug:     ").append(ctx.hostSlug()).append('\n');
        sb.append("State dir:     ").append(ctx.stateDir()).append('\n');
        sb.append('\n');
        sb.append(padRight("Broker app:", 22)).append(' ')
                .append(formatStatus(svcs.get(StatusStore.BROKER_KEY)))
                .append('\n');
        sb.append('\n');
        for (String s : services.knownServices()) {
            String offering = s + "-single";
            sb.append(padRight(s + " (" + offering + "):", 32)).append(' ')
                    .append(formatStatus(svcs.get(offering)))
                    .append('\n');
        }
        return sb.toString();
    }

    private SetupContext buildContext(String host, String remoteSocket) {
        DockerTarget target = resolver.resolve(host, remoteSocket);
        String slug = HostSlug.from(target);
        Path home = Paths.get(System.getProperty("user.home"));
        Path stateDir = home.resolve(".cf-docker-cpi").resolve("hosts").resolve(slug);
        Path binDir = home.resolve(".cf-docker-cpi").resolve("bin");
        return new SetupContext(target, slug, stateDir, binDir,
                false, false, SetupContext.DEFAULT_DIRECTOR_IP, SetupContext.DEFAULT_INTERNAL_CIDR,
                SetupContext.DEFAULT_SYSTEM_DOMAIN, false, false);
    }

    private static String formatStatus(StepStatus s) {
        if (s == null) return "UNRUN";
        StringBuilder b = new StringBuilder(s.status().name());
        if (s.ranAt() != null) b.append("  ").append(s.ranAt());
        if (s.detail() != null) b.append("  ").append(s.detail());
        return b.toString();
    }

    private static String padRight(String s, int width) {
        if (s.length() >= width) return s;
        StringBuilder b = new StringBuilder(width);
        b.append(s);
        while (b.length() < width) b.append(' ');
        return b.toString();
    }

    private String renderResult(SetupContext ctx, String name, StepResult r) {
        return "Command:     " + name + '\n'
             + "Target:      " + ctx.target().uri() + '\n'
             + "Outcome:     " + r.outcome() + '\n'
             + "Detail:      " + r.detail() + '\n';
    }
}
