package com.dashaun.cfdockercpi.setup;

public record StepResult(Outcome outcome, String detail) {

    public enum Outcome { RAN, SKIPPED, FAILED }

    public static StepResult ran(String detail) {
        return new StepResult(Outcome.RAN, detail);
    }

    public static StepResult skipped(String detail) {
        return new StepResult(Outcome.SKIPPED, detail);
    }

    public static StepResult failed(String detail) {
        return new StepResult(Outcome.FAILED, detail);
    }

    public boolean ok() {
        return outcome != Outcome.FAILED;
    }
}
