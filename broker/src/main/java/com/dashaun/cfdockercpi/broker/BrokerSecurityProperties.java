package com.dashaun.cfdockercpi.broker;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Broker HTTP Basic credentials. Wired into the security filter chain; required by every
 * cloud_controller call to the broker. In Phase 3 the CLI rolls a random password at
 * {@code setup broker deploy} time and pushes it via {@code cf set-env BROKER_PASSWORD ...}.
 */
@ConfigurationProperties(prefix = "broker.security")
public record BrokerSecurityProperties(String username, String password) {
    public BrokerSecurityProperties {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "broker.security.username and broker.security.password must be set "
                            + "(env BROKER_USERNAME / BROKER_PASSWORD).");
        }
    }
}
