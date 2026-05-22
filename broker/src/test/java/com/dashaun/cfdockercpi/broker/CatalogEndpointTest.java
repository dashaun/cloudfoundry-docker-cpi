package com.dashaun.cfdockercpi.broker;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.service.ServiceInstanceBindingService;
import org.springframework.cloud.servicebroker.service.ServiceInstanceService;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 1 sanity: the broker's Spring context loads, the {@link Catalog} bean has the four
 * expected offerings, and both {@link ServiceInstanceService} + {@link ServiceInstanceBindingService}
 * stubs are wired in.
 *
 * <p>The HTTP behavior of {@code /v2/catalog} (HTTP Basic + JSON catalog body) is exercised
 * separately via the manual smoke recipe in {@code README} / Phase 4 senshin validation. SB4
 * + Spring Cloud OSB 4.5.0 currently has a mismatch around {@code TestRestTemplateTestAutoConfiguration}
 * that makes a {@code RANDOM_PORT}-style test fail at context refresh; that's a Phase 4 follow-up,
 * not a Phase 1 blocker — the running app demonstrably serves the catalog (see curl output in PR).
 */
@SpringBootTest
@TestPropertySource(properties = {
        "broker.security.username=test-user",
        "broker.security.password=test-pw"
})
class CatalogEndpointTest {

    @Autowired
    private Catalog catalogBean;

    @Autowired
    private ServiceInstanceService instanceService;

    @Autowired
    private ServiceInstanceBindingService bindingService;

    @Test
    void catalogBeanIsPresentWithFourOfferings() {
        assertThat(catalogBean.getServiceDefinitions()).hasSize(4);
        assertThat(catalogBean.getServiceDefinitions())
                .extracting(s -> s.getName())
                .containsExactlyInAnyOrder(
                        "postgres-single", "redis-single",
                        "rabbitmq-single", "minio-single");
    }

    @Test
    void everyOfferingHasASinglePlan() {
        catalogBean.getServiceDefinitions().forEach(def -> {
            assertThat(def.getPlans())
                    .as("offering %s should have one 'single' plan", def.getName())
                    .hasSize(1);
            assertThat(def.getPlans().get(0).getName()).isEqualTo("single");
        });
    }

    @Test
    void offeringIdsAreDeterministicAcrossRebuilds() {
        // Lock the deterministic UUIDs — a regression here means CF would treat the same
        // offering as "new" after a rebuild, orphaning existing service instances.
        var byName = catalogBean.getServiceDefinitions().stream()
                .collect(java.util.stream.Collectors.toMap(d -> d.getName(), d -> d.getId()));
        assertThat(byName).containsExactlyInAnyOrderEntriesOf(java.util.Map.of(
                "postgres-single", "8ea96398-d90d-3896-a413-35b820c8dfdf",
                "redis-single",    "a32c56c3-1da6-3da7-926a-1942406b0e00",
                "rabbitmq-single", "85d0ee9e-0593-3a72-ae7e-2533407a65fe",
                "minio-single",    "77c4d75f-4e12-37a1-baa6-b88fc5bc1945"));
    }

    @Test
    void stubServicesAreWired() {
        assertThat(instanceService).isInstanceOf(StubServiceInstanceService.class);
        assertThat(bindingService).isInstanceOf(StubServiceInstanceBindingService.class);
    }
}
