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
package com.alibaba.cloud.ai.dataagent.commerce.security;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Component
@Profile("commerce")
@Order(Ordered.HIGHEST_PRECEDENCE + 5)
public class CommerceSessionFilter implements WebFilter {
    public static final String SUBJECT="commerce.subject";
    private final CommercePolicy policy;
    private final Set<String> origins;
    public CommerceSessionFilter(CommercePolicy policy, Environment env) {
        this.policy=policy;
        origins=Set.copyOf(Arrays.asList(env.getProperty("commerce.allowed-origins","http://localhost:3000,http://127.0.0.1:3000").split(",")));
    }
    @Override
    public Mono<Void> filter(ServerWebExchange exchange,WebFilterChain chain) {
        String path=exchange.getRequest().getPath().value();
        exchange.getAttributes().put("requestId",id("req"));
        if(!path.startsWith("/api/commerce/v1/")) return fail(exchange,new CommerceException(404,"ENDPOINT_DISABLED","此部署仅开放经营分析接口"));
        return exchange.getSession().flatMap(session -> Mono.fromCallable(()-> {
            String method=exchange.getRequest().getMethod().name();
            String csrf=session.getAttribute("csrf");
            if(csrf==null) { csrf=id("csrf"); session.getAttributes().put("csrf",csrf); }
            if(!Set.of("GET","HEAD","OPTIONS").contains(method)) {
                String origin=exchange.getRequest().getHeaders().getOrigin();
                if(origin!=null&&!origins.contains(origin)) throw new CommerceException(403,"ORIGIN_REJECTED","请求来源不受信任");
                String token=exchange.getRequest().getHeaders().getFirst("X-CSRF-Token");
                if(token==null || !MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
                    throw new CommerceException(403,"CSRF_REJECTED","会话校验失败，请刷新后重试");
            }
            if(!path.equals("/api/commerce/v1/auth/session") && !path.equals("/api/commerce/v1/health")) {
                String tenant=session.getAttribute("tenantId"), subject=session.getAttribute("subjectId");
                if(tenant==null || subject==null) throw new CommerceException(401,"AUTHENTICATION_REQUIRED","请先登录商脉工作台");
                exchange.getAttributes().put(SUBJECT,policy.subject(tenant,subject));
            }
            return true;
        }).subscribeOn(Schedulers.boundedElastic()).flatMap(ignored->chain.filter(exchange)))
        .onErrorResume(CommerceException.class,error->fail(exchange,error));
    }
    private Mono<Void> fail(ServerWebExchange exchange,CommerceException ex) {
        if(exchange.getResponse().isCommitted()) return Mono.empty();
        exchange.getResponse().setStatusCode(HttpStatusCode.valueOf(ex.status()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes=object("code",ex.code(),"message",ex.getMessage(),"requestId",exchange.getAttribute("requestId"),"details",object()).toString().getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(bytes)));
    }
    public static CommerceSubject subject(ServerWebExchange exchange) { return exchange.getRequiredAttribute(SUBJECT); }
}
