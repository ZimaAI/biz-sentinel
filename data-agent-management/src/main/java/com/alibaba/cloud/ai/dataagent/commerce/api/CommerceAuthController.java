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
package com.alibaba.cloud.ai.dataagent.commerce.api;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Profile("commerce")
@RequestMapping("/api/commerce/v1")
public class CommerceAuthController {
    private final CommerceStore store;
    private final CommercePolicy policy;
    public CommerceAuthController(CommerceStore store,CommercePolicy policy) { this.store=store;this.policy=policy; }
    @GetMapping("/health") public ObjectNode health(ServerWebExchange ex) { return envelope(ex,object("status","UP","service","CommerceLens")); }
    @GetMapping("/auth/session") public Mono<ObjectNode> session(ServerWebExchange ex) {
        return ex.getSession().map(session->envelope(ex,object("csrfToken",session.getAttribute("csrf"),"authenticated",session.getAttribute("subjectId")!=null)));
    }
    @PostMapping("/auth/session") public Mono<ObjectNode> login(@RequestBody JsonNode request,ServerWebExchange ex) {
        return Mono.fromCallable(()-> {
            fields(request,"username","password");
            String username=request.path("username").asText();
            ObjectNode login=store.findOne("login",username);
            if(login==null || !new BCryptPasswordEncoder().matches(request.path("password").asText(),login.path("passwordHash").asText()))
                throw new CommerceException(401,"LOGIN_FAILED","账号或密码不正确");
            return policy.subject(login.path("tenantId").asText(),login.path("subjectId").asText());
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(subject->ex.getSession().flatMap(session->session.changeSessionId().then(Mono.fromSupplier(()-> {
            session.getAttributes().put("tenantId",subject.tenantId()); session.getAttributes().put("subjectId",subject.subjectId());
            session.getAttributes().put("csrf",id("csrf"));
            return envelope(ex,object("csrfToken",session.getAttribute("csrf"),"authenticated",true));
        }))));
    }
    @DeleteMapping("/auth/session") public Mono<ObjectNode> logout(ServerWebExchange ex) {
        return ex.getSession().flatMap(session->session.invalidate().thenReturn(envelope(ex,object("authenticated",false))));
    }
    static ObjectNode envelope(ServerWebExchange ex,Object data) { return object("requestId",ex.getAttribute("requestId"),"data",data); }
}
