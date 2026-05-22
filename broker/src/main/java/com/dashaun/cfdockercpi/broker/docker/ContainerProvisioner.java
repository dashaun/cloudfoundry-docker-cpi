package com.dashaun.cfdockercpi.broker.docker;

import com.dashaun.cfdockercpi.broker.plans.ContainerPlan;
import com.dashaun.cfdockercpi.broker.plans.ContainerPlan.Credentials;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.Network;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.core.command.PullImageResultCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Thin wrapper over docker-java for the operations the broker needs:
 * find-or-pull image, create-and-start container, find-by-instance-id, remove.
 *
 * <p>State is held on the containers themselves via labels (see {@link ContainerLabels}); the
 * broker itself is stateless across restarts.
 */
@Component
@ConditionalOnProperty("docker.host")
public class ContainerProvisioner {

    private static final Logger log = LoggerFactory.getLogger(ContainerProvisioner.class);
    /** Container names must be ≤ 63 chars; we cap our prefix + the short instance id well below. */
    private static final int SHORT_ID_LEN = 12;

    private final DockerClient docker;
    private final DockerClientProperties props;

    public ContainerProvisioner(DockerClient docker, DockerClientProperties props) {
        this.docker = docker;
        this.props = props;
    }

    /**
     * Look for an existing container managed by us for the given OSB instance id.
     * Includes both running and stopped containers (we manage the full lifecycle).
     */
    public Optional<Container> findByInstanceId(String instanceId) {
        List<Container> matches = docker.listContainersCmd()
                .withShowAll(true)
                .withLabelFilter(Map.of(
                        ContainerLabels.MANAGED, "true",
                        ContainerLabels.INSTANCE_ID, instanceId))
                .exec();
        return matches.stream().findFirst();
    }

    /**
     * Create and start the container for this OSB instance. Idempotent: if a container with
     * the instance id already exists, returns it (with whatever state it has — usually
     * already running). Returns the freshly inspected container so callers can read its
     * network IP + stored credentials.
     */
    public InspectContainerResponse provision(String instanceId,
                                              String serviceDefinitionId,
                                              ContainerPlan plan) {
        Optional<Container> existing = findByInstanceId(instanceId);
        if (existing.isPresent()) {
            log.info("[provision] instance={} already exists ({}); reusing",
                    instanceId, existing.get().getId().substring(0, 12));
            return docker.inspectContainerCmd(existing.get().getId()).exec();
        }

        Credentials creds = new Credentials(defaultUsername(plan),
                UUID.randomUUID().toString().replace("-", ""));

        pullIfMissing(plan.image());
        ensureNetworkExists(props.network());

        String containerName = "cf-svc-" + plan.serviceName() + "-"
                + instanceId.substring(0, SHORT_ID_LEN);

        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(ContainerLabels.MANAGED, "true");
        labels.put(ContainerLabels.INSTANCE_ID, instanceId);
        labels.put(ContainerLabels.SERVICE_DEFINITION_ID, serviceDefinitionId);
        labels.put(ContainerLabels.SERVICE_NAME, plan.serviceName());
        labels.put(ContainerLabels.CREDS_USERNAME, creds.username());
        labels.put(ContainerLabels.CREDS_PASSWORD, creds.password());

        Map<String, String> env = plan.environment(creds);
        List<String> envList = env.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .toList();

        List<ExposedPort> exposed = new java.util.ArrayList<>();
        exposed.add(ExposedPort.tcp(plan.port()));
        plan.extraPorts().forEach(p -> exposed.add(ExposedPort.tcp(p.port())));

        var create = docker.createContainerCmd(plan.image())
                .withName(containerName)
                .withLabels(labels)
                .withEnv(envList)
                .withExposedPorts(exposed)
                .withHostConfig(HostConfig.newHostConfig()
                        .withNetworkMode(props.network())
                        .withRestartPolicy(com.github.dockerjava.api.model.RestartPolicy.unlessStoppedRestart()));

        List<String> cmd = plan.command(creds);
        if (!cmd.isEmpty()) {
            create.withCmd(cmd);
        }

        var created = create.exec();
        log.info("[provision] instance={} service={} container={} ({}); starting",
                instanceId, plan.serviceName(), containerName,
                created.getId().substring(0, 12));
        docker.startContainerCmd(created.getId()).exec();
        return docker.inspectContainerCmd(created.getId()).exec();
    }

    /** {@code docker rm -f} the container for this instance. No-op if it's already gone. */
    public boolean deprovision(String instanceId) {
        Optional<Container> existing = findByInstanceId(instanceId);
        if (existing.isEmpty()) {
            log.info("[deprovision] instance={} container already absent; no-op", instanceId);
            return false;
        }
        try {
            docker.removeContainerCmd(existing.get().getId())
                    .withForce(true)
                    .withRemoveVolumes(true)
                    .exec();
            log.info("[deprovision] instance={} removed container {}",
                    instanceId, existing.get().getId().substring(0, 12));
            return true;
        } catch (NotFoundException e) {
            log.info("[deprovision] instance={} container vanished between list+remove; ignoring",
                    instanceId);
            return false;
        }
    }

    /** Inspect a container by id. Thin pass-through so callers don't need {@link DockerClient}. */
    public InspectContainerResponse inspect(String containerId) {
        return docker.inspectContainerCmd(containerId).exec();
    }

    /** Per-network IP of the container after start, used to build binding URIs. */
    public String containerIp(InspectContainerResponse container, String network) {
        ContainerNetwork net = container.getNetworkSettings().getNetworks().get(network);
        if (net == null || net.getIpAddress() == null || net.getIpAddress().isBlank()) {
            throw new IllegalStateException("container " + container.getId().substring(0, 12)
                    + " has no IP on network '" + network + "'; got: "
                    + container.getNetworkSettings().getNetworks().keySet());
        }
        return net.getIpAddress();
    }

    /** Reconstitute the stored credentials from the container's labels. */
    public Credentials credentialsFromLabels(InspectContainerResponse container) {
        Map<String, String> labels = container.getConfig().getLabels();
        String u = labels == null ? null : labels.get(ContainerLabels.CREDS_USERNAME);
        String p = labels == null ? null : labels.get(ContainerLabels.CREDS_PASSWORD);
        if (u == null || p == null) {
            throw new IllegalStateException("container missing credential labels");
        }
        return new Credentials(u, p);
    }

    private void pullIfMissing(String image) {
        try {
            docker.inspectImageCmd(image).exec();
            return;  // already pulled
        } catch (NotFoundException ignored) {
            // fall through and pull
        }
        log.info("[provision] pulling image {}", image);
        try {
            docker.pullImageCmd(image)
                    .exec(new PullImageResultCallback() {
                        @Override
                        public void onNext(PullResponseItem item) {
                            super.onNext(item);
                        }
                    })
                    .awaitCompletion();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted pulling " + image, e);
        }
    }

    /** Defensive: cf-docker-cpi-net normally already exists (created by deploy-director). */
    private void ensureNetworkExists(String name) {
        try {
            docker.inspectNetworkCmd().withNetworkId(name).exec();
        } catch (NotFoundException e) {
            log.warn("[provision] docker network '{}' missing; creating one", name);
            docker.createNetworkCmd()
                    .withName(name)
                    .withDriver("bridge")
                    .exec();
        }
    }

    /** Default admin user per plan. Most use {@code admin}; postgres uses {@code postgres}. */
    private static String defaultUsername(ContainerPlan plan) {
        return switch (plan.serviceName()) {
            case "postgres-single" -> "postgres";
            default -> "admin";
        };
    }
}
