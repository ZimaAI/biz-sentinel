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

import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashSet;
import java.util.Set;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("commerce")
public class CommercePolicy {
    private final CommerceStore store;
    public CommercePolicy(CommerceStore store) { this.store=store; }
    public CommerceSubject subject(String tenant, String subjectId) {
        JsonNode member=store.findOne("member",tenant+":"+subjectId);
        if(member==null || !member.path("enabled").asBoolean(true)) throw new CommerceException(401,"SESSION_EXPIRED","请重新登录");
        return new CommerceSubject(tenant,subjectId,strings(member.path("storeIds")),strings(member.path("roles")),member.path("authzVersion").asLong(),false);
    }
    public CommerceSubject guestSubject() {
        ObjectNode config=store.findOne("guest-config","global");
        if(config==null || !config.path("enabled").asBoolean(false)) throw new CommerceException(403,"GUEST_DISABLED","游客浏览暂未开放");
        String tenant=config.path("tenantId").asText(), subjectId=config.path("subjectId").asText();
        CommerceSubject target=subject(tenant,subjectId);
        Set<String> stores=strings(config.path("storeIds"));
        if(stores.isEmpty() || !target.storeIds().containsAll(stores)) throw new CommerceException(503,"GUEST_CONFIGURATION_INVALID","游客演示范围配置无效");
        return new CommerceSubject(tenant,subjectId,stores,Set.of("VIEWER"),target.authzVersion(),true);
    }
    public void assertCurrent(CommerceSubject subject) {
        CommerceSubject current=subject.guest() ? guestSubject() : subject(subject.tenantId(),subject.subjectId());
        if(!current.equals(subject)) throw new CommerceException(403,"ACCESS_REVOKED","授权范围已变化，请重新选择范围");
    }
    public void assertWritable(CommerceSubject subject) {
        assertCurrent(subject);
        if(!subject.canWrite()) throw new CommerceException(403,"READ_ONLY_SESSION","游客账号只能浏览演示数据");
    }
    public void assertReadable(CommerceSubject subject, JsonNode resource) {
        assertCurrent(subject);
        JsonNode scope=resource.path("scope");
        boolean owner=subject.subjectId().equals(resource.path("subjectId").asText(subject.subjectId()));
        boolean shared="TEAM".equals(resource.path("visibility").asText());
        if(!subject.tenantId().equals(scope.path("tenantId").asText()) || !subject.storeIds().containsAll(strings(scope.path("storeIds")))
            || (!owner && !shared && !subject.isAdmin())
            || (owner && scope.has("authzVersion") && scope.path("authzVersion").asLong()!=subject.authzVersion())) {
            throw new CommerceException(404,"RESOURCE_NOT_FOUND","资源不存在或不可见");
        }
    }
    public static Set<String> strings(JsonNode node) { Set<String> result=new HashSet<>(); node.forEach(v->result.add(v.asText())); return result; }
}
