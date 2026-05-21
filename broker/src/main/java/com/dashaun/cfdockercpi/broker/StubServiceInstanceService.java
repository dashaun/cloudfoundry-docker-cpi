package com.dashaun.cfdockercpi.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.CreateServiceInstanceResponse;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceRequest;
import org.springframework.cloud.servicebroker.model.instance.DeleteServiceInstanceResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Fallback used when {@link com.dashaun.cfdockercpi.broker.docker.ContainerProvisioner} isn't
 * available (no {@code docker.host} configured — e.g. running the broker on a laptop for
 * UI/contract checks). Logs the operation, returns success, doesn't actually provision
 * anything. When ContainerProvisioner IS available,
 * {@link ContainerProvisionedInstanceService} ({@code @Primary}) shadows this bean.
 */
@Service
public class StubServiceInstanceService implements ServiceInstanceService {

    private static final Logger log = LoggerFactory.getLogger(StubServiceInstanceService.class);

    @Override
    public Mono<CreateServiceInstanceResponse> createServiceInstance(CreateServiceInstanceRequest req) {
        log.info("[stub] createServiceInstance instance={} service={} plan={}",
                req.getServiceInstanceId(), req.getServiceDefinitionId(), req.getPlanId());
        return Mono.just(CreateServiceInstanceResponse.builder()
                .async(false)
                .instanceExisted(false)
                .build());
    }

    @Override
    public Mono<DeleteServiceInstanceResponse> deleteServiceInstance(DeleteServiceInstanceRequest req) {
        log.info("[stub] deleteServiceInstance instance={} service={} plan={}",
                req.getServiceInstanceId(), req.getServiceDefinitionId(), req.getPlanId());
        return Mono.just(DeleteServiceInstanceResponse.builder()
                .async(false)
                .build());
    }
}
