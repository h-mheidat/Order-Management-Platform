package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Stage 1 acceptance test: the application context starts and is genuinely connected to
 * PostgreSQL, Redis and Kafka.
 *
 * <p>This is deliberately stronger than a {@code contextLoads()} test. Spring will happily start
 * with an unreachable Redis or Kafka - the connection is lazy - so "the context loaded" proves
 * almost nothing about configuration. Asserting that each health contributor reports UP forces a
 * real round trip to each of the three services.
 *
 * <p>Named {@code *IT}: it needs a Docker daemon and runs in {@code verify} via failsafe, keeping
 * {@code mvn test} fast and Docker-free.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationSmokeIT {

    private static final String REDIS_PASSWORD = "test-secret";

    // Same major versions as docker-compose.yml on purpose: a test that passes against different
    // versions than local dev and production is testing the wrong system.
    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    // No @ServiceConnection: Testcontainers has no first-class Redis module in the Boot-managed
    // BOM, so the container is generic and the properties are bound by hand below.
    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    HealthEndpoint health;

    @Test
    void allThreeBackingServicesAreReachable() {
        assertThat(statusOf("db")).isEqualTo(Status.UP);
        assertThat(statusOf("redis")).isEqualTo(Status.UP);
        assertThat(statusOf("kafka")).isEqualTo(Status.UP);
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
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
