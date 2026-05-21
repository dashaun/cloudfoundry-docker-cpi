package com.dashaun.cfdockercpi.commands;

import com.dashaun.cfdockercpi.docker.DockerTarget;
import com.dashaun.cfdockercpi.docker.DockerTargetResolver;
import com.dashaun.cfdockercpi.marketplace.BrokerDeployer;
import com.dashaun.cfdockercpi.setup.HostSlug;
import com.dashaun.cfdockercpi.setup.SetupContext;
import com.dashaun.cfdockercpi.setup.StepResult;
import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.CommandGroup;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

/**
 * Spring Shell commands for the optional Spring Cloud Open Service Broker app.
 *
 * <pre>
 *   broker deploy --host ssh://...
 *   broker remove --host ssh://...
 * </pre>
 *
 * Once {@code broker deploy} has succeeded, individual service plans are toggled with the
 * {@code service add|list|remove} commands in {@link MarketplaceCommands}.
 */
@Component
@CommandGroup(prefix = "broker", name = "Broker", description = "Optional marketplace broker (postgres / redis / rabbitmq / minio).")
public class BrokerCommands {

    private final DockerTargetResolver resolver;
    private final BrokerDeployer deployer;

    public BrokerCommands(DockerTargetResolver resolver, BrokerDeployer deployer) {
        this.resolver = resolver;
        this.deployer = deployer;
    }

    @Command(name = "deploy", description = "Build broker jar, scp, cf push, register with cf")
    public String deploy(
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket,
            @Option(longName = "broker-jar",
                    description = "Path to the broker jar (defaults to broker/target/cf-docker-cpi-broker-*.jar)")
            String brokerJar) throws Exception {

        SetupContext ctx = buildContext(host, remoteSocket);
        Optional<Path> override = brokerJar == null || brokerJar.isBlank()
                ? Optional.empty() : Optional.of(Paths.get(brokerJar));
        StepResult r = deployer.deploy(ctx, override);
        return renderResult(ctx, "broker deploy", r);
    }

    @Command(name = "remove", description = "Tear down the broker app, ASG, and service-broker registration")
    public String remove(
            @Option(longName = "host", shortName = 'H',
                    description = "Docker host URI; same syntax as `docker verify --host`")
            String host,
            @Option(longName = "remote-socket",
                    description = "Path to the Docker socket on the remote host (ssh:// only)",
                    defaultValue = "/var/run/docker.sock")
            String remoteSocket) throws Exception {

        SetupContext ctx = buildContext(host, remoteSocket);
        StepResult r = deployer.remove(ctx);
        return renderResult(ctx, "broker remove", r);
    }

    private SetupContext buildContext(String host, String remoteSocket) {
        DockerTarget target = resolver.resolve(host, remoteSocket);
        String slug = HostSlug.from(target);
        Path home = Paths.get(System.getProperty("user.home"));
        Path stateDir = home.resolve(".cf-docker-cpi").resolve("hosts").resolve(slug);
        Path binDir = home.resolve(".cf-docker-cpi").resolve("bin");
        return new SetupContext(target, slug, stateDir, binDir,
                /*verify*/ false, /*force*/ false,
                SetupContext.DEFAULT_DIRECTOR_IP, SetupContext.DEFAULT_INTERNAL_CIDR,
                SetupContext.DEFAULT_SYSTEM_DOMAIN, /*ignoreResources*/ false, /*writeHosts*/ false);
    }

    private String renderResult(SetupContext ctx, String name, StepResult r) {
        return "Command:     " + name + '\n'
             + "Target:      " + ctx.target().uri() + "  (from " + ctx.target().source().name().toLowerCase() + ")\n"
             + "Host slug:   " + ctx.hostSlug() + '\n'
             + "State dir:   " + ctx.stateDir() + '\n'
             + "Outcome:     " + r.outcome() + '\n'
             + "Detail:      " + r.detail() + '\n';
    }
}
