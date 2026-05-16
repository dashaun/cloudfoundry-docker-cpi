package com.dashaun.cfdockercpi.docker;

public record CheckResult(String name, Status status, String detail) {

    public enum Status { PASS, FAIL, WARN, SKIP }

    public static CheckResult pass(String name, String detail) {
        return new CheckResult(name, Status.PASS, detail);
    }

    public static CheckResult fail(String name, String detail) {
        return new CheckResult(name, Status.FAIL, detail);
    }

    public static CheckResult warn(String name, String detail) {
        return new CheckResult(name, Status.WARN, detail);
    }
}
