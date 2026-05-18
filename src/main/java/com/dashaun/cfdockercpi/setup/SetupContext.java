package com.dashaun.cfdockercpi.setup;

import com.dashaun.cfdockercpi.docker.DockerTarget;

import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

public record SetupContext(
        DockerTarget target,
        String hostSlug,
        Path stateDir,
        Path binDir,
        boolean verify,
        boolean force,
        String directorIp,
        String internalCidr,
        String systemDomain,
        boolean ignoreResourceCheck,
        boolean writeHosts) {

    public static final String DEFAULT_DIRECTOR_NAME = "cf-docker-cpi";
    public static final String DEFAULT_DIRECTOR_IP = "10.245.0.11";
    public static final String DEFAULT_INTERNAL_CIDR = "10.245.0.0/24";
    public static final String DEFAULT_INTERNAL_GW = "10.245.0.1";
    public static final String DEFAULT_SYSTEM_DOMAIN = "bosh-lite.com";

    private static final DateTimeFormatter LOG_TS = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault());

    public Path statusFile() {
        return stateDir.resolve("status.json");
    }

    public Path logsDir() {
        return stateDir.resolve("logs");
    }

    public Path newLogFile(String stepName) {
        return logsDir().resolve(stepName + "-" + LOG_TS.format(Instant.now()) + ".log");
    }

    public Path cfHome() {
        return stateDir.resolve("cf-home");
    }
}
