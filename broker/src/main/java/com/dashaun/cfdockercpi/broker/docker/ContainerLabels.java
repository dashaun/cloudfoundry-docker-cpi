package com.dashaun.cfdockercpi.broker.docker;

/**
 * Label keys used to track service-instance state directly on the provisioned containers.
 * The broker is {@code cf push}'d and effectively stateless; persistent state lives on the
 * containers themselves, queryable via {@code docker inspect} / a label filter.
 *
 * <p>Storing the admin password as a container label is acceptable here: dockerd is TLS-
 * protected and only the broker has the client cert. Anyone with that cert can already
 * inspect / kill containers and steal data, so an extra encryption hop wouldn't add
 * meaningful security.
 */
public final class ContainerLabels {
    private ContainerLabels() {}

    /** Marks a container as managed by the cf-docker-cpi broker. Value is always {@code "true"}. */
    public static final String MANAGED = "cf-docker-cpi.broker.managed";

    /** OSB service-instance UUID. The label filter for "find this instance's container". */
    public static final String INSTANCE_ID = "cf-docker-cpi.broker.instance-id";

    /** OSB service-definition UUID. Useful for diagnostics ({@code docker ps --filter}). */
    public static final String SERVICE_DEFINITION_ID = "cf-docker-cpi.broker.service-definition-id";

    /** Offering name from the catalog, e.g. {@code postgres-single}. Human-readable. */
    public static final String SERVICE_NAME = "cf-docker-cpi.broker.service-name";

    /** Admin user injected into the container's env. */
    public static final String CREDS_USERNAME = "cf-docker-cpi.broker.creds.username";

    /** Admin password (UUID-style random) injected into the container's env. */
    public static final String CREDS_PASSWORD = "cf-docker-cpi.broker.creds.password";
}
