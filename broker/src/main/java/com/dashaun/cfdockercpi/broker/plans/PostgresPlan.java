package com.dashaun.cfdockercpi.broker.plans;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class PostgresPlan implements ContainerPlan {

    private static final String DB = "postgres";

    @Override public String serviceName() { return "postgres-single"; }
    @Override public String image() { return "postgres:16"; }
    @Override public int port() { return 5432; }

    @Override
    public Map<String, String> environment(Credentials c) {
        return Map.of(
                "POSTGRES_USER", c.username(),
                "POSTGRES_PASSWORD", c.password(),
                "POSTGRES_DB", DB);
    }

    @Override
    public Map<String, Object> bindingCredentials(String containerIp, Credentials c) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("uri", "postgres://" + c.username() + ":" + c.password()
                + "@" + containerIp + ":" + port() + "/" + DB);
        creds.put("jdbcUrl", "jdbc:postgresql://" + containerIp + ":" + port() + "/" + DB);
        creds.put("hostname", containerIp);
        creds.put("port", port());
        creds.put("username", c.username());
        creds.put("password", c.password());
        creds.put("database", DB);
        return creds;
    }
}
