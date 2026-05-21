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
 * Phase 1 stub: log the request, return success, do nothing else. Phase 2 will replace this
 * with a real implementation that translates each {@code create / delete} into a
 * {@code docker run} / {@code docker rm -f} against the host dockerd.
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
