package com.dashaun.cfdockercpi.broker.plans;

import com.dashaun.cfdockercpi.broker.catalog.BrokerCatalogConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlanRegistryTest {

    private final Catalog catalog = new BrokerCatalogConfiguration().serviceBrokerCatalog();

    @Test
    void mapsEveryOfferingToItsContainerPlan() {
        PlanRegistry reg = new PlanRegistry(catalog, List.of(
                new PostgresPlan(), new RedisPlan(),
                new RabbitMqPlan(), new MinioPlan()));

        for (ServiceDefinition def : catalog.getServiceDefinitions()) {
            assertThat(reg.byServiceDefinitionId(def.getId()))
                    .as("plan for catalog offering %s (id=%s)", def.getName(), def.getId())
                    .isPresent();
            assertThat(reg.byServiceDefinitionId(def.getId()).get().serviceName())
                    .isEqualTo(def.getName());
        }
    }

    @Test
    void failsFastWhenAnOfferingHasNoPlanImplementation() {
        assertThatThrownBy(() -> new PlanRegistry(catalog, List.of(
                new PostgresPlan(), new RedisPlan(), new RabbitMqPlan()
                // MinioPlan deliberately omitted
        ))).hasMessageContaining("minio-single");
    }

    @Test
    void postgresCredentialsShapeIsStable() {
        var creds = new PostgresPlan().bindingCredentials("10.245.0.250",
                new ContainerPlan.Credentials("postgres", "s3cret"));
        assertThat(creds).containsKeys("uri", "jdbcUrl", "hostname", "port", "username", "password", "database");
        assertThat(creds.get("uri")).isEqualTo("postgres://postgres:s3cret@10.245.0.250:5432/postgres");
        assertThat(creds.get("port")).isEqualTo(5432);
    }

    @Test
    void redisCredentialsShapeIsStable() {
        var creds = new RedisPlan().bindingCredentials("10.245.0.251",
                new ContainerPlan.Credentials("admin", "p4ssw0rd"));
        // Redis has no notion of username in OSS edition.
        assertThat(creds).containsOnlyKeys("uri", "hostname", "port", "password");
        assertThat(creds.get("uri")).isEqualTo("redis://:p4ssw0rd@10.245.0.251:6379");
    }

    @Test
    void rabbitmqCredentialsShapeIncludesManagementUri() {
        var creds = new RabbitMqPlan().bindingCredentials("10.245.0.252",
                new ContainerPlan.Credentials("admin", "p4ss"));
        assertThat(creds).containsKeys("uri", "hostname", "port", "username", "password", "managementUri");
        assertThat(creds.get("uri")).isEqualTo("amqp://admin:p4ss@10.245.0.252:5672");
        assertThat(creds.get("managementUri")).isEqualTo("http://admin:p4ss@10.245.0.252:15672");
    }

    @Test
    void minioCredentialsShapeMatchesAwsConventions() {
        var creds = new MinioPlan().bindingCredentials("10.245.0.253",
                new ContainerPlan.Credentials("admin", "secretkey"));
        assertThat(creds).containsKeys("endpoint", "consoleEndpoint", "accessKeyId", "secretAccessKey", "region");
        assertThat(creds.get("endpoint")).isEqualTo("http://10.245.0.253:9000");
        assertThat(creds.get("region")).isEqualTo("us-east-1");
    }

    @Test
    void rabbitmqExposesAnExtraManagementPort() {
        assertThat(new RabbitMqPlan().extraPorts())
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.name()).isEqualTo("management");
                    assertThat(p.port()).isEqualTo(15672);
                });
    }

    @Test
    void redisUsesACommandOverrideAndNoEnv() {
        var creds = new ContainerPlan.Credentials("admin", "p4ss");
        assertThat(new RedisPlan().environment(creds)).isEqualTo(Map.of());
        assertThat(new RedisPlan().command(creds))
                .containsExactly("redis-server", "--requirepass", "p4ss");
    }
}
