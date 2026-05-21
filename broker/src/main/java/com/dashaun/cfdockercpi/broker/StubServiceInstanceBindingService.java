package com.dashaun.cfdockercpi.broker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceAppBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.CreateServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingRequest;
import org.springframework.cloud.servicebroker.model.binding.DeleteServiceInstanceBindingResponse;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Phase 1 stub: log the request, return a placeholder credential set. Phase 2 will replace
 * this with real per-binding credentials minted against the underlying docker container
 * (e.g. a new postgres role + password, scoped to this binding).
 */
@Service
public class StubServiceInstanceBindingService implements ServiceInstanceBindingService {

    private static final Logger log = LoggerFactory.getLogger(StubServiceInstanceBindingService.class);

    @Override
    public Mono<CreateServiceInstanceBindingResponse> createServiceInstanceBinding(
            CreateServiceInstanceBindingRequest req) {
        log.info("[stub] createServiceInstanceBinding instance={} binding={} app={}",
                req.getServiceInstanceId(), req.getBindingId(),
                req.getBindResource() != null ? req.getBindResource().getAppGuid() : "<none>");
        // Placeholder credentials so apps can at least bind. Real shape (uri, hostname,
        // port, username, password) comes in Phase 2 once we know the actual container's
        // IP + creds.
        return Mono.just(CreateServiceInstanceAppBindingResponse.builder()
                .async(false)
                .credentials(Map.of(
                        "stub", "true",
                        "note", "this is a Phase 1 placeholder; no real backing container exists yet"))
                .bindingExisted(false)
                .build());
    }

    @Override
    public Mono<DeleteServiceInstanceBindingResponse> deleteServiceInstanceBinding(
            DeleteServiceInstanceBindingRequest req) {
        log.info("[stub] deleteServiceInstanceBinding instance={} binding={}",
                req.getServiceInstanceId(), req.getBindingId());
        return Mono.just(DeleteServiceInstanceBindingResponse.builder()
                .async(false)
                .build());
    }
}
