package com.dashaun.cfdockercpi.broker.plans;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class MinioPlan implements ContainerPlan {

    private static final int CONSOLE_PORT = 9001;

    @Override public String serviceName() { return "minio-single"; }
    @Override public String image() { return "minio/minio:latest"; }
    @Override public int port() { return 9000; }

    @Override
    public List<ExtraPort> extraPorts() {
        return List.of(new ExtraPort("console", CONSOLE_PORT));
    }

    @Override
    public Map<String, String> environment(Credentials c) {
        return Map.of(
                "MINIO_ROOT_USER", c.username(),
                "MINIO_ROOT_PASSWORD", c.password());
    }

    @Override
    public List<String> command(Credentials c) {
        return List.of("server", "/data", "--console-address", ":" + CONSOLE_PORT);
    }

    @Override
    public Map<String, Object> bindingCredentials(String containerIp, Credentials c) {
        Map<String, Object> creds = new LinkedHashMap<>();
        // Match the AWS SDK / s3 conventions: accessKeyId, secretAccessKey, endpoint.
        creds.put("endpoint", "http://" + containerIp + ":" + port());
        creds.put("consoleEndpoint", "http://" + containerIp + ":" + CONSOLE_PORT);
        creds.put("accessKeyId", c.username());
        creds.put("secretAccessKey", c.password());
        creds.put("region", "us-east-1");  // MinIO's default
        return creds;
    }
}
