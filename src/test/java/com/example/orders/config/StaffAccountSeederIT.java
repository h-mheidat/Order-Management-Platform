package com.example.orders.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.entity.Role;
import com.example.orders.repository.UserRepository;
import com.example.orders.support.Containers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The staff seeder creates working ADMIN and SUPPORT accounts, and refuses to run in production.
 *
 * <p>Both halves matter. The first is the only legitimate route to a privileged account, since
 * registration always produces a CUSTOMER. The second is the guard that stops a convenience from
 * becoming a known-password administrator in a deployed environment.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffAccountSeederIT {

    private static final String ADMIN_EMAIL = "seeded-admin@test.local";
    private static final String SUPPORT_EMAIL = "seeded-support@test.local";
    private static final String PASSWORD = "SeededPass123";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        Containers.registerTo(registry);
        registry.add("app.seed.enabled", () -> "true");
        registry.add("app.seed.admin.username", () -> "seeded-admin");
        registry.add("app.seed.admin.email", () -> ADMIN_EMAIL);
        registry.add("app.seed.admin.password", () -> PASSWORD);
        registry.add("app.seed.support.username", () -> "seeded-support");
        registry.add("app.seed.support.email", () -> SUPPORT_EMAIL);
        registry.add("app.seed.support.password", () -> PASSWORD);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    UserRepository userRepository;

    @Autowired
    StaffAccountSeeder seeder;

    @Autowired
    PasswordEncoder passwordEncoder;

    private String login(String email) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}""".formatted(email, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"accessToken\":\"([^\"]*)\".*", "$1");
    }

    @Test
    void createsTheTwoRolesThatCannotBeSelfRegistered() {
        assertThat(userRepository.findByEmailIgnoreCase(ADMIN_EMAIL))
                .as("the seeder runs at startup, so the account exists before any test does")
                .isPresent()
                .get()
                .satisfies(user -> {
                    assertThat(user.getRole()).isEqualTo(Role.ADMIN);
                    assertThat(user.isEnabled()).isTrue();
                    // Hashed by the same PasswordEncoder the login path uses, with the {bcrypt}
                    // prefix that makes a future algorithm change survivable.
                    assertThat(user.getPasswordHash()).startsWith("{bcrypt}$2a$");
                    assertThat(user.getPasswordHash()).doesNotContain(PASSWORD);
                });

        assertThat(userRepository.findByEmailIgnoreCase(SUPPORT_EMAIL))
                .isPresent().get()
                .extracting(user -> user.getRole()).isEqualTo(Role.SUPPORT);
    }

    @Test
    void seededAdminCanActuallyLogInAndReachAdminEndpoints() throws Exception {
        String token = login(ADMIN_EMAIL);

        // The point of the whole exercise: a usable privileged account. Asserting the row exists is
        // not enough - a wrongly-encoded password would still produce a row.
        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalOrders").exists());
    }

    @Test
    void seededSupportCanReadOrdersButNotReachAdminEndpoints() throws Exception {
        String token = login(SUPPORT_EMAIL);

        mockMvc.perform(get("/api/orders")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());

        // SUPPORT is staff, not an administrator. Seeding must not quietly over-grant.
        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void isIdempotentAndLeavesAnExistingAccountAlone() {
        String hashBefore = userRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow()
                .getPasswordHash();
        long usersBefore = userRepository.count();

        // Running again is what happens on every restart.
        seeder.run(null);

        assertThat(userRepository.count())
                .as("a restart must not create a second admin")
                .isEqualTo(usersBefore);
        assertThat(userRepository.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow().getPasswordHash())
                .as("an existing account must be left untouched - resetting it would silently "
                        + "overwrite a changed password and re-grant a role somebody removed")
                .isEqualTo(hashBefore);
    }

    @Test
    void cannotEvenBeConstructedUnderTheProdProfile() {
        // The guard that matters, and it is in the constructor rather than run() on purpose:
        // ApplicationRunners execute after the web server is already accepting connections, so
        // checking there would let the application serve traffic for a moment before dying.
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");

        assertThatThrownBy(() -> new StaffAccountSeeder(
                new SeedProperties(true,
                        new SeedProperties.Account("x", "x@test.local", "Password123"), null),
                userRepository, passwordEncoder, prod))
                .as("it must fail loudly, not skip quietly - a silent skip leaves somebody believing "
                        + "there is an admin account when there is not")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("prod");
    }
}
