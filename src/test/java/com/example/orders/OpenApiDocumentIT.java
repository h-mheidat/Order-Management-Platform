package com.example.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.support.IntegrationTestBase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The generated OpenAPI document is complete and reachable.
 *
 * <p>Worth testing rather than eyeballing once. The spec is generated, so it cannot drift from the
 * controllers - but it can silently lose an endpoint when a security rule changes, or start documenting
 * a framework-injected argument as a query parameter that callers then dutifully try to send. Both
 * failures leave the API working perfectly and the documentation wrong, which is worse than having none:
 * clients build against a spec they trust.
 */
class OpenApiDocumentIT extends IntegrationTestBase {

    @Autowired
    ObjectMapper objectMapper;

    private JsonNode fetchSpec() throws Exception {
        String body = mockMvc.perform(get("/v3/api-docs"))
                // Reachable without a token: you need the spec to discover how to obtain one.
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void servesTheDocumentWithoutAuthentication() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").exists())
                .andExpect(jsonPath("$.info.title").value("Order Management Platform API"));
    }

    @Test
    void servesSwaggerUi() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    void documentsEveryEndpointTheApiActuallyExposes() throws Exception {
        JsonNode paths = fetchSpec().get("paths");

        // If an endpoint is added and this list is not, the omission is caught here rather than by a
        // client discovering the API has an operation nobody wrote down.
        assertThat(paths.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "/api/auth/register",
                "/api/auth/login",
                "/api/orders",
                "/api/orders/{id}",
                "/api/orders/{id}/status",
                "/api/admin/statistics");

        assertThat(paths.get("/api/orders").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("get", "post");
        assertThat(paths.get("/api/orders/{id}").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("get", "delete");
        assertThat(paths.get("/api/orders/{id}/status").fieldNames()).toIterable()
                .containsExactly("patch");
    }

    @Test
    void declaresBearerAuthenticationSoTheAuthorizeButtonWorks() throws Exception {
        JsonNode spec = fetchSpec();

        JsonNode scheme = spec.path("components").path("securitySchemes").path("bearerAuth");
        assertThat(scheme.path("type").asText()).isEqualTo("http");
        assertThat(scheme.path("scheme").asText()).isEqualTo("bearer");
        assertThat(scheme.path("bearerFormat").asText()).isEqualTo("JWT");

        // Applied document-wide, so every operation inherits it.
        assertThat(spec.path("security").toString()).contains("bearerAuth");
    }

    @Test
    void marksTheAuthEndpointsAsNotRequiringAToken() throws Exception {
        JsonNode paths = fetchSpec().get("paths");

        // An empty `security` array is how OpenAPI expresses "overrides the global requirement".
        // Without it Swagger UI shows a padlock on register and login, and callers reasonably conclude
        // they need a token to get a token.
        for (String path : new String[]{"/api/auth/register", "/api/auth/login"}) {
            JsonNode security = paths.path(path).path("post").path("security");
            assertThat(security.isArray()).as("%s must declare a security override", path).isTrue();
            assertThat(security).as("%s must not require a token", path).isEmpty();
        }
    }

    @Test
    void doesNotDocumentFrameworkInjectedArgumentsAsRequestParameters() throws Exception {
        JsonNode paths = fetchSpec().get("paths");

        // The Jwt principal is resolved from the security context. Left un-hidden, springdoc documents
        // it as a parameter named "jwt" and clients try to send it.
        JsonNode getOrderParams = paths.path("/api/orders/{id}").path("get").path("parameters");
        assertThat(getOrderParams.toString())
                .as("the authentication principal must not appear as a request parameter")
                .doesNotContain("\"jwt\"");

        // Pageable must be expanded into real query parameters, not documented as one opaque object.
        JsonNode listParams = paths.path("/api/orders").path("get").path("parameters");
        assertThat(listParams.findValuesAsText("name"))
                .contains("page", "size", "sort", "status")
                .doesNotContain("pageable", "jwt");
    }

    @Test
    void documentsTheSharedErrorContractOnFailureResponses() throws Exception {
        JsonNode spec = fetchSpec();

        // Clients need the error shape to write one error path, and the schema is what tells them the
        // `error` field is an enum they can switch on.
        assertThat(spec.path("components").path("schemas").has("ErrorResponse"))
                .as("ErrorResponse must be part of the published contract")
                .isTrue();

        JsonNode notFound = spec.path("paths").path("/api/orders/{id}").path("get")
                .path("responses").path("404");
        assertThat(notFound.isMissingNode()).isFalse();
        assertThat(notFound.toString()).contains("ErrorResponse");
    }

    @Test
    void keepsActuatorOutOfTheApiDocument() throws Exception {
        // Actuator is infrastructure, not API. In production it is not even on this port, and listing it
        // here would invite clients to depend on it.
        assertThat(fetchSpec().get("paths").fieldNames()).toIterable()
                .noneMatch(path -> path.startsWith("/actuator"));
    }
}
