package com.example.orders.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The OpenAPI document.
 *
 * <p>Generated from the controllers rather than hand-written. A hand-maintained spec drifts from the code
 * within a couple of sprints, and a wrong spec is worse than none at all because clients trust it and
 * build against it.
 *
 * <p>Disabled entirely under the {@code prod} profile - see {@code application-prod.yml}. Publishing a
 * complete map of the API surface, including which fields exist and which roles reach which endpoint, is
 * free reconnaissance. Internal consumers should get the spec from CI as a build artefact, not from a
 * live production endpoint.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    OpenAPI ordersOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Order Management Platform API")
                        .version("v1")
                        .description("""
                                Order management for CUSTOMER, SUPPORT and ADMIN roles.

                                ## Authenticating

                                1. `POST /api/auth/register` — always creates a CUSTOMER. The role is not \
                                accepted from the request body; sending one has no effect.
                                2. `POST /api/auth/login` — returns a bearer token, valid 15 minutes.
                                3. Press **Authorize** above and paste the token.

                                SUPPORT and ADMIN accounts cannot be self-registered and must be \
                                provisioned directly.

                                ## Errors

                                Every failure returns the same shape. Branch on `error`, never on \
                                `message` — messages are for humans and change wording freely.

                                ```json
                                {
                                  "timestamp": "2026-08-10T12:00:00Z",
                                  "status": 404,
                                  "error": "ORDER_NOT_FOUND",
                                  "message": "Order 100 was not found"
                                }
                                ```

                                Note that requesting another customer's order returns **404, not 403**. \
                                A 403 would confirm the order exists, which is precisely the fact the \
                                caller is not entitled to.

                                ## Correlation

                                Send `X-Correlation-Id` on any request and it is echoed back and \
                                attached to every log line the request produces. One is generated if you \
                                do not send it.
                                """)
                        .contact(new Contact().name("Order Management Platform")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        // Makes Swagger UI's Authorize button add "Authorization: Bearer <token>"
                        // instead of leaving the caller to construct the header by hand.
                        .bearerFormat("JWT")
                        .description("Paste the accessToken from POST /api/auth/login.")))
                // Applied document-wide, so every operation is marked as requiring a token. The two
                // public auth endpoints opt out individually with @SecurityRequirements.
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
