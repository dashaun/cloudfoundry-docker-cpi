package com.dashaun.cfdockercpi.broker;

import com.dashaun.cfdockercpi.broker.docker.ContainerProvisioner;
import com.dashaun.cfdockercpi.broker.plans.ContainerPlan;
import com.dashaun.cfdockercpi.broker.plans.PlanRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.servicebroker.exception.ServiceBrokerException;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Real {@link ServiceInstanceService}: each {@code create} → {@code docker run} a per-instance
 * service container on the broker's network ({@code cf-docker-cpi-net} by default); each
 * {@code delete} → {@code docker rm -f} that container.
 *
 * <p>Only active when a {@link ContainerProvisioner} bean exists (i.e. when {@code docker.host}
 * is configured). When it isn't, the stub from Phase 1 takes over so the broker can still
 * boot in test environments / on a laptop.
 */
@Service
@Primary
@ConditionalOnProperty("docker.host")
public class ContainerProvisionedInstanceService implements ServiceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(ContainerProvisionedInstanceService.class);

    private final ContainerProvisioner provisioner;
    private final PlanRegistry plans;

    public ContainerProvisionedInstanceService(ContainerProvisioner provisioner, PlanRegistry plans) {
        this.provisioner = provisioner;
        this.plans = plans;
    }

    @Override
    public Mono<CreateServiceInstanceResponse> createServiceInstance(CreateServiceInstanceRequest req) {
        ContainerPlan plan = plans.byServiceDefinitionId(req.getServiceDefinitionId())
                .orElseThrow(() -> new ServiceBrokerException(
                        "unknown service definition id: " + req.getServiceDefinitionId()));

        log.info("[create] instance={} service={} plan={}",
                req.getServiceInstanceId(), plan.serviceName(), req.getPlanId());

        boolean existedBefore = provisioner.findByInstanceId(req.getServiceInstanceId()).isPresent();
        provisioner.provision(req.getServiceInstanceId(), req.getServiceDefinitionId(), plan);

        return Mono.just(CreateServiceInstanceResponse.builder()
                .async(false)
                .instanceExisted(existedBefore)
                .build());
    }

    @Override
    public Mono<DeleteServiceInstanceResponse> deleteServiceInstance(DeleteServiceInstanceRequest req) {
        log.info("[delete] instance={}", req.getServiceInstanceId());
        provisioner.deprovision(req.getServiceInstanceId());
        return Mono.just(DeleteServiceInstanceResponse.builder()
                .async(false)
                .build());
    }
}
