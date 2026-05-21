package com.dashaun.cfdockercpi.broker.catalog;

import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.Plan;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.UUID;

/**
 * Static OSB catalog with four "container-backed" service offerings: postgres-single,
 * redis-single, rabbitmq-single, minio-single. Each is bindable, has a single plan, and is
 * keyed by a deterministic UUID derived from its name so the ids stay stable across rebuilds
 * (cf cloud_controller persists them).
 *
 * <p>Phase 1 ships the catalog with stub instance/binding services so the broker can be
 * {@code cf push}'d and registered without actually provisioning anything. Phase 2 wires the
 * docker-provisioning logic that turns each offering into a real docker container.
 */
@Configuration
public class BrokerCatalogConfiguration {

    /** Namespace UUID for deterministic offering/plan ids. Stable across this codebase. */
    private static final UUID NAMESPACE =
            UUID.fromString("a4d6e3f8-1c1f-4b1e-9aab-bd7e63c9c4a1");

    @Bean
    public Catalog serviceBrokerCatalog() {
        return Catalog.builder()
                .serviceDefinitions(
                        offering("postgres-single", "postgres",
                                "PostgreSQL 16 — single container per service instance.",
                                List.of("postgresql", "relational", "sql")),
                        offering("redis-single", "redis",
                                "Redis 7 — single container per service instance.",
                                List.of("redis", "cache", "key-value")),
                        offering("rabbitmq-single", "rabbitmq",
                                "RabbitMQ 3 — single container per service instance, with the management UI.",
                                List.of("rabbitmq", "amqp", "message-broker")),
                        offering("minio-single", "minio",
                                "MinIO — S3-compatible object storage, single container per service instance.",
                                List.of("minio", "s3", "object-storage")))
                .build();
    }

    private static ServiceDefinition offering(String name, String shortLabel,
                                              String description, List<String> tags) {
        Plan plan = Plan.builder()
                .id(deterministicId(name + "/plan/single"))
                .name("single")
                .description("Single container, default settings. One container per service instance.")
                .free(true)
                .build();

        return ServiceDefinition.builder()
                .id(deterministicId(name))
                .name(name)
                .description(description)
                .bindable(true)
                .planUpdateable(false)
                .tags(tags.toArray(new String[0]))
                .plans(plan)
                .metadata("displayName", shortLabel)
                .metadata("longDescription", description)
                .build();
    }

    /**
     * Stable per-name UUID. Identical to {@link UUID#nameUUIDFromBytes(byte[])} (RFC-4122
     * v3 / MD5) but with a project-specific namespace mixed in so unrelated projects don't
     * collide if they use the same offering name.
     */
    private static String deterministicId(String key) {
        return UUID.nameUUIDFromBytes((NAMESPACE + ":" + key).getBytes()).toString();
    }
}
