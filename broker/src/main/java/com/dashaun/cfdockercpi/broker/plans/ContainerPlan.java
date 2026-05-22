package com.dashaun.cfdockercpi.broker.plans;

import java.util.List;
import java.util.Map;

/**
 * One container-backed service plan: the docker image + how to launch it + how to extract
 * binding credentials from a running container.
 *
 * <p>Each implementation is a Spring {@code @Component} discovered via
 * {@link PlanRegistry}. The component's {@link #serviceName()} must match the offering name
 * declared in {@code BrokerCatalogConfiguration} (e.g. {@code "postgres-single"}); the
 * registry uses that key to dispatch provisioning + binding calls.
 */
public interface ContainerPlan {

    /**
     * The CF marketplace offering name this plan implements. Must equal exactly one of the
     * offering names registered in {@code BrokerCatalogConfiguration}.
     */
    String serviceName();

    /** Docker image (with tag) — pulled before container create if missing. */
    String image();

    /**
     * The TCP port the service listens on inside the container. Used to build the binding
     * URI ({@code scheme://user:pass@host:port}). Single-port services only in v1.
     */
    int port();

    /**
     * Optional extra ports to expose / surface in binding credentials. Empty for most;
     * RabbitMQ uses this for its management UI (15672). The values are surfaced as additional
     * keys in the binding credentials map.
     */
    default List<ExtraPort> extraPorts() { return List.of(); }

    /**
     * Environment variables to set on the container, given the per-instance credentials.
     * For example postgres sets {@code POSTGRES_PASSWORD=<password>} so the running container
     * accepts that password on the wire.
     */
    Map<String, String> environment(Credentials credentials);

    /**
     * Optional container CMD override. Some plans (redis, minio) bake their config into
     * args rather than env vars. Empty list means "use the image's default CMD".
     */
    default List<String> command(Credentials credentials) { return List.of(); }

    /**
     * Build the {@code VCAP_SERVICES.<offering>[0].credentials} map for a binding to a
     * running container, given the container's IP on the broker's docker network and the
     * stored credentials.
     */
    Map<String, Object> bindingCredentials(String containerIp, Credentials credentials);

    /**
     * Per-instance secret material. {@code username} is the plan's default admin user;
     * {@code password} is a fresh UUID-derived random string per provision.
     */
    record Credentials(String username, String password) {}

    /** Additional listener port exposed by the container (e.g. RabbitMQ management UI). */
    record ExtraPort(String name, int port) {}
}
