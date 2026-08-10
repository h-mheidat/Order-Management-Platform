package com.example.orders.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

/**
 * One set of containers, shared by every integration test in the JVM.
 *
 * <p>Started once in a static initializer rather than per class with {@code @Container}. With the
 * annotation, each test class gets its own PostgreSQL, Redis and Kafka - and Kafka alone takes
 * several seconds to become usable, so a suite of ten classes spends most of its wall clock starting
 * and stopping brokers. Testcontainers' reaper removes these when the JVM exits.
 *
 * <p>The cost of sharing is that state persists between test classes: tests must not assume an empty
 * database. Rolled-back slices ({@code @DataJpaTest}) are unaffected; tests that commit use unique
 * data instead of relying on isolation.
 *
 * <p>Image versions match docker-compose.yml. Testing against different versions than development and
 * production runs tests against a system nobody deploys.
 */
public final class Containers {

    public static final String REDIS_PASSWORD = "test-secret";

    public static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:17-alpine"));

    public static final KafkaContainer KAFKA =
            new KafkaContainer(DockerImageName.parse("apache/kafka:4.0.0"));

    public static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
                    .withCommand("redis-server", "--requirepass", REDIS_PASSWORD)
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    static {
        // Started in parallel: three sequential startups are the largest fixed cost in the suite,
        // and they have no dependency on each other.
        Startables.deepStart(POSTGRES, REDIS, KAFKA).join();
    }

    private Containers() {
    }

    /** Binds every container's address into the Spring context under test. */
    public static void registerTo(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);

        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);

        // The application refuses to start without a signing secret, by design. Tests supply their
        // own rather than sharing one with any real environment.
        registry.add("app.jwt.secret", () -> "test-signing-secret-at-least-32-characters-long");
        registry.add("app.jwt.issuer", () -> "order-management-platform-test");
    }
}
