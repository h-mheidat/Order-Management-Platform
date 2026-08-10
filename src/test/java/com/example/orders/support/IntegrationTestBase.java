package com.example.orders.support;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Base for tests that exercise the whole application against real infrastructure.
 *
 * <p>MockMvc rather than a real HTTP client: it runs the complete filter chain, including Spring
 * Security, so authentication and authorization are genuinely tested - while staying in one thread,
 * which keeps failures debuggable and avoids port juggling.
 *
 * <p>Every subclass shares the same Spring context and the same containers. Tests must therefore be
 * order-independent and must not assume an empty database.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class IntegrationTestBase {

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        Containers.registerTo(registry);
    }

    @Autowired
    protected MockMvc mockMvc;
}
