package com.dashaun.cfdockercpi.broker;

import com.dashaun.cfdockercpi.broker.docker.ContainerProvisioner;
import com.dashaun.cfdockercpi.broker.docker.DockerClientProperties;
import com.dashaun.cfdockercpi.broker.plans.ContainerPlan;
import com.dashaun.cfdockercpi.broker.plans.PlanRegistry;
import com.github.dockerjava.api.command.InspectContainerResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerException;
import org.springframework.cloud.servicebroker.exception.ServiceInstanceDoesNotExistException;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Real {@link ServiceInstanceBindingService}: looks up the provisioned container by its OSB
 * instance id, reads the stored credentials from labels + the container's IP from the
 * inspect response, and assembles the binding response per the per-plan
 * {@link ContainerPlan#bindingCredentials} mapping.
 *
 * <p>v1 returns the SAME admin credentials for every binding to a given instance.
 * Per-binding role/user creation (e.g. minting a postgres role per binding) is a planned
 * follow-up; the bookkeeping for it lives in this class once we implement it.
 */
@Service
@Primary
@ConditionalOnProperty("docker.host")
public class ContainerProvisionedBindingService implements ServiceInstanceBindingService {

    private static final Logger log = LoggerFactory.getLogger(ContainerProvisionedBindingService.class);

    private final ContainerProvisioner provisioner;
    private final PlanRegistry plans;
    private final DockerClientProperties dockerProps;

    public ContainerProvisionedBindingService(ContainerProvisioner provisioner,
                                              PlanRegistry plans,
                                              DockerClientProperties dockerProps) {
        this.provisioner = provisioner;
        this.plans = plans;
        this.dockerProps = dockerProps;
    }

    @Override
    public Mono<CreateServiceInstanceBindingResponse> createServiceInstanceBinding(
            CreateServiceInstanceBindingRequest req) {
        log.info("[bind] instance={} binding={}", req.getServiceInstanceId(), req.getBindingId());

        var existing = provisioner.findByInstanceId(req.getServiceInstanceId());
        if (existing.isEmpty()) {
            throw new ServiceInstanceDoesNotExistException(req.getServiceInstanceId());
        }
        ContainerPlan plan = plans.byServiceDefinitionId(req.getServiceDefinitionId())
                .orElseThrow(() -> new ServiceBrokerException(
                        "unknown service definition id: " + req.getServiceDefinitionId()));

        // Re-inspect to get the live network IP (the listContainers payload doesn't include it).
        InspectContainerResponse insp = provisioner.inspect(existing.get().getId());
        String ip = provisioner.containerIp(insp, dockerProps.network());
        ContainerPlan.Credentials creds = provisioner.credentialsFromLabels(insp);

        Map<String, Object> credentials = plan.bindingCredentials(ip, creds);

        return Mono.just(CreateServiceInstanceAppBindingResponse.builder()
                .async(false)
                .credentials(credentials)
                .bindingExisted(false)  // v1 stateless: every bind returns the same admin creds
                .build());
    }

    @Override
    public Mono<DeleteServiceInstanceBindingResponse> deleteServiceInstanceBinding(
            DeleteServiceInstanceBindingRequest req) {
        log.info("[unbind] instance={} binding={}", req.getServiceInstanceId(), req.getBindingId());
        // v1: nothing to revoke since every binding returned the same admin credentials. When
        // we add per-binding roles, this becomes "drop the role".
        return Mono.just(DeleteServiceInstanceBindingResponse.builder()
                .async(false)
                .build());
    }

}
