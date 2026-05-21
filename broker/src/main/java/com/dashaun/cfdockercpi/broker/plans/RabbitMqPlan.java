package com.dashaun.cfdockercpi.broker.plans;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RabbitMqPlan implements ContainerPlan {

    private static final int MANAGEMENT_PORT = 15672;

    @Override public String serviceName() { return "rabbitmq-single"; }
    @Override public String image() { return "rabbitmq:3-management"; }
    @Override public int port() { return 5672; }

    @Override
    public List<ExtraPort> extraPorts() {
        return List.of(new ExtraPort("management", MANAGEMENT_PORT));
    }

    @Override
    public Map<String, String> environment(Credentials c) {
        return Map.of(
                "RABBITMQ_DEFAULT_USER", c.username(),
                "RABBITMQ_DEFAULT_PASS", c.password());
    }

    @Override
    public Map<String, Object> bindingCredentials(String containerIp, Credentials c) {
        Map<String, Object> creds = new LinkedHashMap<>();
        creds.put("uri", "amqp://" + c.username() + ":" + c.password()
                + "@" + containerIp + ":" + port());
        creds.put("hostname", containerIp);
        creds.put("port", port());
        creds.put("username", c.username());
        creds.put("password", c.password());
        creds.put("managementUri", "http://" + c.username() + ":" + c.password()
                + "@" + containerIp + ":" + MANAGEMENT_PORT);
        return creds;
    }
}
