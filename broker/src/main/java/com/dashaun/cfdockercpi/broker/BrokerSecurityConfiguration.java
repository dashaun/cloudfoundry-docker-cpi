package com.dashaun.cfdockercpi.broker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * HTTP Basic auth on every {@code /v2/**} endpoint, validated against a single user/password
 * pair sourced from the {@code BROKER_USERNAME} / {@code BROKER_PASSWORD} env vars (also
 * available as application properties {@code broker.security.username/password}).
 *
 * <p>cf cloud_controller authenticates every OSB call with HTTP Basic using the credentials
 * registered via {@code cf create-service-broker <name> <user> <pw> <url>}. The same pair
 * must be set here.
 *
 * <p>Phase 1 keeps this dead simple: no role-based authz, no JWT. Phase 3's CLI generates a
 * random password at {@code setup broker deploy} time and pushes it via {@code cf set-env}.
 */
@Configuration
@EnableConfigurationProperties(BrokerSecurityProperties.class)
public class BrokerSecurityConfiguration {

    @Bean
    public UserDetailsService userDetailsService(BrokerSecurityProperties props) {
        // `{noop}` prefix tells Spring Security's DelegatingPasswordEncoder to store the
        // password literally. Single shared credential set; the surface is the broker itself,
        // not a multi-user identity store.
        return new InMemoryUserDetailsManager(
                User.builder()
                        .username(props.username())
                        .password("{noop}" + props.password())
                        .authorities("ROLE_BROKER")
                        .build());
    }

    @Bean
    public SecurityFilterChain brokerFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/v2/**").hasRole("BROKER")
                        // /actuator/health is open if anyone adds an actuator later;
                        // for now Phase 1 doesn't ship one, so this is harmless future-proofing.
                        .requestMatchers("/actuator/health/**").permitAll()
                        .anyRequest().denyAll())
                .httpBasic(Customizer.withDefaults());
        return http.build();
    }
}
