package com.dashaun.cfdockercpi.broker.plans;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedisPlan implements ContainerPlan {

    @Override public String serviceName() { return "redis-single"; }
    @Override public String image() { return "redis:7"; }
    @Override public int port() { return 6379; }

    @Override
    public Map<String, String> environment(Credentials c) {
        return Map.of();  // password is set via --requirepass on the command line
    }

    @Override
    public List<String> command(Credentials c) {
        return List.of("redis-server", "--requirepass", c.password());
    }

    @Override
    public Map<String, Object> bindingCredentials(String containerIp, Credentials c) {
        Map<String, Object> creds = new LinkedHashMap<>();
        // Redis has no notion of "username" in OSS edition; we keep it in the password field.
        creds.put("uri", "redis://:" + c.password() + "@" + containerIp + ":" + port());
        creds.put("hostname", containerIp);
        creds.put("port", port());
        creds.put("password", c.password());
        return creds;
    }
}
