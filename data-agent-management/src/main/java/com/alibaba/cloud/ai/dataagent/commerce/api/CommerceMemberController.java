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

import static com.alibaba.cloud.ai.dataagent.commerce.api.CommerceAuthController.envelope;
import static com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSessionFilter.subject;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceAudit;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Profile("commerce")
@RequestMapping("/api/commerce/v1/members")
public class CommerceMemberController {
    private final CommerceStore store;
    private final CommerceAudit audit;
    private final CommercePolicy policy;
    public CommerceMemberController(CommerceStore store,CommerceAudit audit,CommercePolicy policy) {this.store=store;this.audit=audit;this.policy=policy;}
    @GetMapping public Mono<ObjectNode> list(ServerWebExchange ex) {return Mono.fromCallable(()-> {
        admin(subject(ex));return envelope(ex,object("items",store.find("member",subject(ex).tenantId()),"nextCursor",null));
    }).subscribeOn(Schedulers.boundedElastic());}
    @PostMapping public Mono<ObjectNode> create(@RequestBody JsonNode request,ServerWebExchange ex) {return Mono.fromCallable(()-> {
        CommerceSubject subject=subject(ex);admin(subject);
        fields(request,"username","password","displayName","storeIds","roles");validate(subject,request);
        String username=request.path("username").asText(),password=request.path("password").asText();
        if(!username.matches("[A-Za-z0-9._-]{3,64}")||password.length()<12||password.length()>64)throw new CommerceException(422,"INVALID_ACCOUNT","账号需为 3–64 位字母数字，密码需为 12–64 位");
        ObjectNode member=object("tenantId",subject.tenantId(),"subjectId",username,"displayName",request.path("displayName").asText(username),"storeIds",request.path("storeIds"),"roles",request.path("roles"),"authzVersion",1,"enabled",true);
        return store.transaction(()-> {
            audit.record(subject,"MEMBER_CREATE",username);
            store.create("login",username,subject.tenantId(),object("tenantId",subject.tenantId(),"subjectId",username,"passwordHash",new BCryptPasswordEncoder().encode(password)));
            return envelope(ex,store.create("member",subject.tenantId()+":"+username,subject.tenantId(),member));
        });
    }).subscribeOn(Schedulers.boundedElastic());}
    @PatchMapping("/{id}") public Mono<ObjectNode> update(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return Mono.fromCallable(()-> {
        CommerceSubject subject=subject(ex);admin(subject);fields(request,"expectedVersion","storeIds","roles","enabled");validate(subject,request);
        ObjectNode old=store.get("member",subject.tenantId()+":"+id);
        if(old.path("authzVersion").asLong()!=request.path("expectedVersion").asLong())throw new CommerceException(409,"VERSION_CONFLICT","成员授权已更新，请刷新");
        return store.transaction(()-> {
            audit.record(subject,"MEMBER_AUTHORIZATION_UPDATE",id);
            return envelope(ex,store.update("member",subject.tenantId()+":"+id,old.path("_revision").asLong(),v->{v.set("storeIds",request.path("storeIds"));v.set("roles",request.path("roles"));v.put("enabled",request.path("enabled").asBoolean(true));v.put("authzVersion",old.path("authzVersion").asLong()+1);return v;}));
        });
    }).subscribeOn(Schedulers.boundedElastic());}
    private void admin(CommerceSubject subject) {policy.assertCurrent(subject);if(!subject.isAdmin())throw new CommerceException(403,"ROLE_REQUIRED","只有租户管理员可以管理成员");}
    private void validate(CommerceSubject subject,JsonNode request) {
        Set<String> stores=CommercePolicy.strings(request.path("storeIds")),roles=CommercePolicy.strings(request.path("roles"));
        if(!request.path("storeIds").isArray()||!subject.storeIds().containsAll(stores))throw new CommerceException(403,"STORE_FORBIDDEN","只能授予当前管理范围内的店铺");
        if(roles.isEmpty()||!Set.of("TENANT_ADMIN","OPS_MANAGER","STORE_OPERATOR","VIEWER").containsAll(roles))throw new CommerceException(422,"INVALID_ROLE","角色无效");
    }
}
