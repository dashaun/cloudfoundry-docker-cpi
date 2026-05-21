package com.dashaun.cfdockercpi.broker.plans;

import org.springframework.cloud.servicebroker.model.catalog.Catalog;
import org.springframework.cloud.servicebroker.model.catalog.ServiceDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Maps each catalog offering's id (the UUID the broker advertises to cloud_controller) to the
 * {@link ContainerPlan} that knows how to provision it. Validates at startup that every
 * offering in {@link Catalog} has a matching {@link ContainerPlan}, so the broker fails fast
 * if a plan implementation is missing.
 */
@Component
public class PlanRegistry {

    private final Map<String, ContainerPlan> byServiceDefinitionId;

    public PlanRegistry(Catalog catalog, List<ContainerPlan> plans) {
        Map<String, ContainerPlan> byName = new LinkedHashMap<>();
        for (ContainerPlan p : plans) {
            if (byName.putIfAbsent(p.serviceName(), p) != null) {
                throw new IllegalStateException(
                        "Duplicate ContainerPlan for offering '" + p.serviceName() + "'");
            }
        }
        Map<String, ContainerPlan> byId = new LinkedHashMap<>();
        for (ServiceDefinition def : catalog.getServiceDefinitions()) {
            ContainerPlan plan = byName.remove(def.getName());
            if (plan == null) {
                throw new IllegalStateException(
                        "No ContainerPlan implementation for catalog offering '"
                        + def.getName() + "' (id=" + def.getId() + ")");
            }
            byId.put(def.getId(), plan);
        }
        if (!byName.isEmpty()) {
            throw new IllegalStateException(
                    "ContainerPlan(s) without a matching catalog offering: " + byName.keySet());
        }
        this.byServiceDefinitionId = byId;
    }

    /** Look up the plan by the OSB service-definition id (UUID from the catalog). */
    public Optional<ContainerPlan> byServiceDefinitionId(String id) {
        return Optional.ofNullable(byServiceDefinitionId.get(id));
    }
}
