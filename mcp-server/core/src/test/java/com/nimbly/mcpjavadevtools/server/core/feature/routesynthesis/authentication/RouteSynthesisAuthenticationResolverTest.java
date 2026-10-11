package com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.authentication;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.action.createrecipe.CreateRecipeRequest;
import com.nimbly.mcpjavadevtools.server.core.feature.routesynthesis.model.authentication.RouteSynthesisAuthenticationMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RouteSynthesisAuthenticationResolverTest {
    @TempDir
    Path project;
    private final DefaultRouteSynthesisAuthenticationResolver resolver = new DefaultRouteSynthesisAuthenticationResolver();

    @Test
    void bearerRequirementCannotBeSatisfiedByBasicCredentials() throws Exception {
        globalSecurity("type: http\n      scheme: bearer");
        RouteSynthesisAuthenticationMetadata missing = resolve(null, "username-secret", "password-secret");
        assertThat(missing.status()).isEqualTo("needs_user_input");
        assertThat(missing.missing()).containsExactly("authToken");
        RouteSynthesisAuthenticationMetadata ready = resolve("token-secret", null, null);
        assertThat(ready.status()).isEqualTo("auto_resolved");
        assertThat(ready.headers()).containsExactly("Authorization");
        assertThat(ready.toString()).doesNotContain("token-secret");
    }

    @Test
    void basicRequirementNeedsBothCredentialsEvenWhenBearerIsSupplied() throws Exception {
        globalSecurity("type: http\n      scheme: basic");
        RouteSynthesisAuthenticationMetadata missing = resolve("token-secret", null, null);
        assertThat(missing.strategy()).isEqualTo("basic");
        assertThat(missing.missing()).containsExactly("authUsername", "authPassword");
        assertThat(resolve(null, "username-secret", null).missing()).containsExactly("authPassword");
        RouteSynthesisAuthenticationMetadata ready = resolve(null, "username-secret", "password-secret");
        assertThat(ready.status()).isEqualTo("auto_resolved");
        assertThat(ready.toString()).doesNotContain("username-secret", "password-secret");
    }

    @Test
    void cookieRequirementUsesCookieMetadataWithoutExposingToken() throws Exception {
        globalSecurity("type: apiKey\n      in: cookie\n      name: session");
        assertThat(resolve(null, null, null).status()).isEqualTo("needs_user_input");
        RouteSynthesisAuthenticationMetadata ready = resolve("cookie-secret", null, null);
        assertThat(ready.strategy()).isEqualTo("cookie");
        assertThat(ready.headers()).containsExactly("Cookie");
        assertThat(ready.toString()).doesNotContain("cookie-secret");
    }

    @Test
    void endpointSecurityIsScopedToSelectedPath() throws Exception {
        Files.writeString(project.resolve("openapi.yaml"), "openapi: 3.0.3\npaths:\n  /protected:\n"
                + "    get:\n      security:\n        - auth: []\n  /public:\n    get:\n      responses: {}\n"
                + "components:\n  securitySchemes:\n    auth:\n      type: http\n      scheme: bearer\n");
        assertThat(resolve(null, null, null).status()).isEqualTo("needs_user_input");
        assertThat(resolver.resolve(request(null, null, null), project, null, "/public").status())
                .isEqualTo("not_required");
    }

    @Test
    void qualifiedSourceSecurityRequiresInputWithoutOpenApi() throws Exception {
        Path controller = project.resolve("Controller.java");
        Files.writeString(controller, "@org.springframework.security.access.prepost.PreAuthorize(\"true\") class Controller {}");
        RouteSynthesisAuthenticationMetadata missing = resolver.resolve(request(null, null, null), project, controller, "/protected");
        assertThat(missing.status()).isEqualTo("needs_user_input");
        assertThat(missing.source()).isEqualTo("source.security_annotations");
        assertThat(missing.missing()).containsExactly("authToken");
    }

    @Test
    void oversizedHintsCannotProduceAnonymousReadyMetadata() throws Exception {
        Files.writeString(project.resolve("openapi.yaml"), " ".repeat(512 * 1024 + 1));
        RouteSynthesisAuthenticationMetadata missing = resolve(null, null, null);
        assertThat(missing.status()).isEqualTo("needs_user_input");
        assertThat(missing.source()).isEqualTo("auth.requirements_unavailable");
    }

    private void globalSecurity(String scheme) throws Exception {
        Files.writeString(project.resolve("openapi.yaml"), "openapi: 3.0.3\nsecurity:\n  - auth: []\n"
                + "components:\n  securitySchemes:\n    auth:\n      " + scheme + "\n");
    }

    private RouteSynthesisAuthenticationMetadata resolve(String token, String username, String password) {
        return resolver.resolve(request(token, username, password), project, null, "/protected");
    }

    private CreateRecipeRequest request(String token, String username, String password) {
        return new CreateRecipeRequest(project.toString(), List.of(), "example.Controller", "handle", null,
                null, "static_only", null, "regression", token, username, password, null, null, null, null, null, null);
    }
}
