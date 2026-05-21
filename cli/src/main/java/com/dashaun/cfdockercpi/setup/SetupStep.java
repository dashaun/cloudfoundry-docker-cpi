package com.dashaun.cfdockercpi.setup;

public interface SetupStep {

    String name();

    String description();

    StepCheck check(SetupContext ctx);

    StepResult run(SetupContext ctx) throws Exception;
}
