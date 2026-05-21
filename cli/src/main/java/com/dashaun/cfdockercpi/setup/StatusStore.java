package com.dashaun.cfdockercpi.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class StatusStore {

    /** Synthetic key under {@code services} that tracks broker-app deploy state. */
    public static final String BROKER_KEY = "_broker";

    private final ObjectMapper mapper;

    public StatusStore() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public StatusFile load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new StatusFile(new LinkedHashMap<>(), new LinkedHashMap<>());
        }
        return mapper.readValue(file.toFile(), StatusFile.class);
    }

    public void save(Path file, StatusFile data) throws IOException {
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), data);
    }

    // ---------- pipeline steps ----------

    public Optional<StepStatus> get(Path file, String stepName) throws IOException {
        return Optional.ofNullable(load(file).steps().get(stepName));
    }

    public void put(Path file, String stepName, StepStatus status) throws IOException {
        StatusFile data = load(file);
        data.steps().put(stepName, status);
        save(file, data);
    }

    // ---------- marketplace services + broker app ----------

    public Optional<StepStatus> getService(Path file, String serviceName) throws IOException {
        return Optional.ofNullable(load(file).services().get(serviceName));
    }

    public void putService(Path file, String serviceName, StepStatus status) throws IOException {
        StatusFile data = load(file);
        data.services().put(serviceName, status);
        save(file, data);
    }

    /**
     * Combined status for a host. {@code steps} is the pipeline (verify-docker → smoke-push);
     * {@code services} is the optional marketplace state — the {@link #BROKER_KEY}
     * pseudo-entry tracks the broker app deployment; the rest are per-plan service offerings
     * (postgres-single, redis-single, …). Both maps default to empty so older status.json
     * files written before the services field existed continue to load cleanly.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record StatusFile(
            Map<String, StepStatus> steps,
            Map<String, StepStatus> services) {
        @JsonCreator
        public StatusFile(
                @JsonProperty("steps") Map<String, StepStatus> steps,
                @JsonProperty("services") Map<String, StepStatus> services) {
            this.steps = steps == null ? new LinkedHashMap<>() : new LinkedHashMap<>(steps);
            this.services = services == null ? new LinkedHashMap<>() : new LinkedHashMap<>(services);
        }
    }
}
