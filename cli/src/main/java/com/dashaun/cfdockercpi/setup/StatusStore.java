package com.dashaun.cfdockercpi.setup;

import com.fasterxml.jackson.annotation.JsonCreator;
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

    private final ObjectMapper mapper;

    public StatusStore() {
        this.mapper = new ObjectMapper()
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public StatusFile load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new StatusFile(new LinkedHashMap<>());
        }
        StatusFile loaded = mapper.readValue(file.toFile(), StatusFile.class);
        if (loaded.steps() == null) {
            return new StatusFile(new LinkedHashMap<>());
        }
        return loaded;
    }

    public void save(Path file, StatusFile data) throws IOException {
        Files.createDirectories(file.getParent());
        mapper.writeValue(file.toFile(), data);
    }

    public Optional<StepStatus> get(Path file, String stepName) throws IOException {
        return Optional.ofNullable(load(file).steps().get(stepName));
    }

    public void put(Path file, String stepName, StepStatus status) throws IOException {
        StatusFile data = load(file);
        data.steps().put(stepName, status);
        save(file, data);
    }

    public record StatusFile(Map<String, StepStatus> steps) {
        @JsonCreator
        public StatusFile(@JsonProperty("steps") Map<String, StepStatus> steps) {
            this.steps = steps == null ? new LinkedHashMap<>() : new LinkedHashMap<>(steps);
        }
    }
}
