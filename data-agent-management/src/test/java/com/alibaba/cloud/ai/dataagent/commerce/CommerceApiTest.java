/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.cloud.ai.dataagent.commerce;

import static org.assertj.core.api.Assertions.assertThat;
import com.alibaba.cloud.ai.dataagent.DataAgentApplication;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(classes=DataAgentApplication.class,webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "commerce.datasource.management.url=jdbc:h2:mem:commerce-api-management;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "commerce.datasource.management.username=sa","commerce.datasource.management.password=",
    "commerce.datasource.ingestion.url=jdbc:h2:mem:commerce-api-analysis;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "commerce.datasource.ingestion.username=sa","commerce.datasource.ingestion.password=",
    "commerce.datasource.query.url=jdbc:h2:mem:commerce-api-analysis;MODE=MySQL;DB_CLOSE_DELAY=-1",
    "commerce.datasource.query.username=sa","commerce.datasource.query.password=",
    "commerce.initialize-schema=true","commerce.bootstrap.password=integration-password",
    "commerce.bootstrap.username=api-admin","commerce.worker.enabled=false","commerce.monitor-initial-delay-ms=3600000"
})
@ActiveProfiles("commerce")
@AutoConfigureWebTestClient
class CommerceApiTest {
    @Autowired WebTestClient client;
    @Test void commerceContextClosesLegacyEntrypointsAndRequiresSession() {
        client.get().uri("/api/stream/search?query=select+1").exchange().expectStatus().isNotFound();
        client.get().uri("/api/datasource").exchange().expectStatus().isNotFound();
        client.get().uri("/mcp").exchange().expectStatus().isNotFound();
        client.get().uri("/api/commerce/v1/me").exchange().expectStatus().isUnauthorized().expectBody().jsonPath("$.code").isEqualTo("AUTHENTICATION_REQUIRED");
    }
    @Test void sessionLoginRotatesCsrfAndRequiresSameOriginMutations() {
        var init=client.get().uri("/api/commerce/v1/auth/session").exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult();
        String csrf=init.getResponseBody().path("data").path("csrfToken").asText();
        String session=init.getResponseCookies().getFirst("SESSION").getValue();
        assertThat(csrf).isNotBlank();
        client.post().uri("/api/commerce/v1/auth/session").cookie("SESSION",session)
            .bodyValue(Map.of("username","api-admin","password","integration-password")).exchange().expectStatus().isForbidden();
        client.post().uri("/api/commerce/v1/auth/session").cookie("SESSION",session).header("X-CSRF-Token",csrf).header("Origin","https://untrusted.invalid")
            .bodyValue(Map.of("username","api-admin","password","integration-password")).exchange().expectStatus().isForbidden();
        var login=client.post().uri("/api/commerce/v1/auth/session").cookie("SESSION",session).header("X-CSRF-Token",csrf).header("Origin","http://localhost:3000")
            .bodyValue(Map.of("username","api-admin","password","integration-password")).exchange().expectStatus().isOk().expectBody(JsonNode.class).returnResult();
        String newSession=login.getResponseCookies().getFirst("SESSION").getValue();
        assertThat(newSession).isNotEqualTo(session);
        assertThat(login.getResponseBody().path("data").path("csrfToken").asText()).isNotEqualTo(csrf);
        client.get().uri("/api/commerce/v1/me").cookie("SESSION",newSession).exchange().expectStatus().isOk().expectBody().jsonPath("$.data.tenantId").isEqualTo("t_demo");
        client.post().uri("/api/commerce/v1/queries").cookie("SESSION",newSession).header("X-CSRF-Token",csrf).bodyValue(Map.of()).exchange().expectStatus().isForbidden();
    }
}
