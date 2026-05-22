package com.dashaun.cfdockercpi.broker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Spring Cloud Open Service Broker app for cf-docker-cpi.
 *
 * <p>In Phase 1 the broker exposes a static catalog at {@code /v2/catalog} and stub
 * {@code ServiceInstanceService} / {@code ServiceInstanceBindingService} implementations that
 * return success without actually provisioning anything. Phase 2 replaces those stubs with
 * docker-java calls that {@code docker run} the right image on the host's dockerd.
 *
 * <p>The app is intended to be {@code cf push}'d into the CF deployed by {@code deploy-cf};
 * registered with cloud_controller via {@code cf create-service-broker}; talks back to the
 * host dockerd at {@code tcp://10.245.0.1:2376} over TLS, with the client certificate triple
 * delivered to it as base64-encoded env vars at push time. See {@code docs/marketplace.md}
 * (added in Phase 5) for the full architecture and the security model.
 */
@SpringBootApplication
public class BrokerApplication {

    public static void main(String[] args) {
        SpringApplication.run(BrokerApplication.class, args);
    }
}
