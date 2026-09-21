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
package com.alibaba.cloud.ai.dataagent.commerce.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

public final class CommerceJson {
    public static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private CommerceJson() {}
    public static ObjectNode object(Object... pairs) {
        ObjectNode result = MAPPER.createObjectNode();
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        for (int i = 0; i < pairs.length; i += 2) result.set(pairs[i].toString(), MAPPER.valueToTree(pairs[i + 1]));
        return result;
    }
    public static ArrayNode array() { return MAPPER.createArrayNode(); }
    public static String id(String prefix) { return prefix + "_" + UUID.randomUUID().toString().replace("-", ""); }
    public static String sha256(String value) { return sha256(value.getBytes(StandardCharsets.UTF_8)); }
    public static String sha256(byte[] value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)); }
        catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    public static ObjectNode read(String value) {
        try { return (ObjectNode) MAPPER.readTree(value); }
        catch (Exception ex) { throw new IllegalStateException("Invalid persisted Commerce document", ex); }
    }
    public static void fields(JsonNode value, String... allowed) {
        if (value == null || !value.isObject()) throw new CommerceException(422, "INVALID_REQUEST", "请求必须是 JSON 对象");
        var names = java.util.Set.of(allowed);
        value.fieldNames().forEachRemaining(key -> {
            if (!names.contains(key)) throw new CommerceException(422, "UNKNOWN_FIELD", "不支持的字段：" + key);
        });
    }
}
