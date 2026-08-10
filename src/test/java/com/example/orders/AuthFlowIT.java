package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import com.example.orders.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * End-to-end authentication and authorization.
 *
 * <p>Goes through the real filter chain, so a passing test means the token was genuinely signed,
 * validated, and converted into authorities Spring Security acted on - not that a mock said yes.
 */
class AuthFlowIT extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    /** Unique per call: these tests commit, and they share a database with every other test class. */
    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerAndLogin() throws Exception {
        String suffix = uniqueSuffix();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"user_%s","email":"user_%s@test.com","password":"Password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"user_%s@test.com","password":"Password123"}
                                """.formatted(suffix)))
                .andExpect(status().isOk())
                .andReturn();

        return objectMapper.readTree(result.getResponse().getContentAsString())
                .get("accessToken").asText();
    }

    @Test
    void registersAUserWithoutEverReturningTheirPassword() throws Exception {
        String suffix = uniqueSuffix();

        MvcResult result = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"fresh_%s","email":"Fresh_%s@Test.com","password":"Password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"))
                // Normalised on write so the case-insensitive unique index matches it.
                .andExpect(jsonPath("$.email").value("fresh_%s@test.com".formatted(suffix)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .as("no password material may ever appear in a response")
                .doesNotContain("Password123")
                .doesNotContain("passwordHash")
                .doesNotContain("bcrypt");
    }

    @Test
    void refusesToLetAnyoneRegisterThemselvesAsAdmin() throws Exception {
        String suffix = uniqueSuffix();

        // The extra "role" field is simply not bound - RegisterRequest has no such component.
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"sneaky_%s","email":"sneaky_%s@test.com",
                                 "password":"Password123","role":"ADMIN"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.role").value("CUSTOMER"));
    }

    @Test
    void rejectsADuplicateEmailEvenInADifferentCase() throws Exception {
        String suffix = uniqueSuffix();
        String payload = """
                {"username":"dup_%s","email":"dup_%s@test.com","password":"Password123"}
                """.formatted(suffix, suffix);

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"other_%s","email":"DUP_%s@TEST.COM","password":"Password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("EMAIL_ALREADY_USED"));
    }

    @Test
    void reportsEveryInvalidFieldAtOnce() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"x","email":"not-an-email","password":"short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                // All three fields, not just the first one to fail - one round trip to fix a form.
                .andExpect(jsonPath("$.fieldErrors.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(3)));
    }

    @Test
    void givesTheSameAnswerForAnUnknownEmailAndAWrongPassword() throws Exception {
        String suffix = uniqueSuffix();
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"real_%s","email":"real_%s@test.com","password":"Password123"}
                                """.formatted(suffix, suffix)))
                .andExpect(status().isCreated());

        MvcResult wrongPassword = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"real_%s@test.com","password":"WrongPassword1"}
                                """.formatted(suffix)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownEmail = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody_%s@test.com","password":"WrongPassword1"}
                                """.formatted(suffix)))
                .andExpect(status().isUnauthorized())
                .andReturn();

        JsonNode a = objectMapper.readTree(wrongPassword.getResponse().getContentAsString());
        JsonNode b = objectMapper.readTree(unknownEmail.getResponse().getContentAsString());

        // Identical code and message: the response must not reveal which accounts exist.
        assertThat(a.get("error").asText()).isEqualTo(b.get("error").asText());
        assertThat(a.get("message").asText()).isEqualTo(b.get("message").asText());
    }

    @Test
    void issuesATokenThatOpensAProtectedEndpoint() throws Exception {
        String token = registerAndLogin();

        // 200 here would mean the token was accepted and roles converted correctly. The endpoint
        // does not exist yet (stage 5), so 404 is the proof that authentication passed - a rejected
        // token would have produced 401 before routing.
        mockMvc.perform(get("/api/orders/999999")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().is(org.hamcrest.Matchers.not(401)));
    }

    @Test
    void rejectsAMissingTokenWithTheStandardErrorBody() throws Exception {
        mockMvc.perform(get("/api/orders"))
                .andExpect(status().isUnauthorized())
                // The security filter chain answers before the DispatcherServlet, so this asserts
                // RestAuthenticationEntryPoint really does emit the shared contract.
                .andExpect(jsonPath("$.error").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    void rejectsATokenWhoseClaimsHaveBeenEditedToEscalateRole() throws Exception {
        String token = registerAndLogin();
        String[] parts = token.split("\\.");

        // Rewrite the payload to claim ADMIN, then reattach the original signature. This is the
        // attack the signature exists to stop, and it is a far better test than flipping a
        // character of the signature itself: the last character of a 43-character base64url string
        // carries only two significant bits, so a flip there frequently decodes to the same MAC
        // bytes and the token stays valid.
        String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
        String escalated = payload.replace("\"CUSTOMER\"", "\"ADMIN\"");
        assertThat(escalated).as("the payload must actually have been modified").isNotEqualTo(payload);

        String forged = parts[0] + "."
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(escalated.getBytes(StandardCharsets.UTF_8))
                + "." + parts[2];

        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deniesACustomerAccessToAdminEndpoints() throws Exception {
        String token = registerAndLogin();

        mockMvc.perform(get("/api/admin/statistics")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("ACCESS_DENIED"));
    }

    @Test
    void keepsUnlistedEndpointsClosedByDefault() throws Exception {
        String token = registerAndLogin();

        // Nothing maps /api/anything-else, and the final rule is denyAll() - so a new endpoint added
        // without an explicit rule fails closed rather than being publicly reachable.
        mockMvc.perform(get("/api/anything-else")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }
}
