package com.dashaun.cfdockercpi.setup;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class SetupOrchestrator {

    private final Map<String, SetupStep> steps;
    private final StatusStore statusStore;

    public SetupOrchestrator(List<SetupStep> stepBeans, StatusStore statusStore) {
        Map<String, SetupStep> ordered = new LinkedHashMap<>();
        for (SetupStep s : stepBeans) {
            if (ordered.putIfAbsent(s.name(), s) != null) {
                throw new IllegalStateException("Duplicate setup step name: " + s.name());
            }
        }
        this.steps = ordered;
        this.statusStore = statusStore;
    }

    public List<String> stepNames() {
        return new ArrayList<>(steps.keySet());
    }

    public Optional<SetupStep> step(String name) {
        return Optional.ofNullable(steps.get(name));
    }

    public StepResult runStep(String stepName, SetupContext ctx) throws Exception {
        SetupStep step = steps.get(stepName);
        if (step == null) {
            return StepResult.failed("unknown step: " + stepName
                    + " (known: " + String.join(", ", stepNames()) + ")");
        }
        return runStep(step, ctx);
    }

    public StepResult runStep(SetupStep step, SetupContext ctx) throws Exception {
        StepCheck check = step.check(ctx);
        if (check == StepCheck.ALREADY_DONE && !ctx.force()) {
            return StepResult.skipped(step.name() + " already done (use --force to re-run)");
        }
        return step.run(ctx);
    }

    public Map<String, StepStatus> status(SetupContext ctx) throws IOException {
        return statusStore.load(ctx.statusFile()).steps();
    }
}
