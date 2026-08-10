package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;

/**
 * The application context starts and is genuinely connected to PostgreSQL, Redis and Kafka.
 *
 * <p>Deliberately stronger than a {@code contextLoads()} test. Spring will start happily with an
 * unreachable Redis or Kafka - those connections are lazy - so "the context loaded" proves almost
 * nothing about configuration. Asserting each health contributor reports UP forces a real round trip.
 */
class ApplicationSmokeIT extends IntegrationTestBase {

    @Autowired
    HealthEndpoint health;

    @Test
    void allThreeBackingServicesAreReachable() {
        assertThat(statusOf("db")).isEqualTo(Status.UP);
        assertThat(statusOf("redis")).isEqualTo(Status.UP);
        assertThat(statusOf("kafka")).isEqualTo(Status.UP);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void readinessIgnoresTheCacheAndTheBroker() {
        // The dependencies that may take an instance out of the load balancer are db only: a Redis
        // outage has a fallback and a Kafka outage is absorbed by the outbox.
        assertThat(health.healthForPath("readiness").getStatus()).isEqualTo(Status.UP);
    }

    private Status statusOf(String name) {
        HealthComponent component = health.healthForPath(name);
        assertThat(component)
                .as("health contributor '%s' is not registered - check the starter is on the "
                        + "classpath and auto-configuration was not excluded", name)
                .isNotNull();
        return component.getStatus();
    }
}
