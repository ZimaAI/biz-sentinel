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
package com.alibaba.cloud.ai.dataagent.commerce.query;

import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The only query language accepted by Commerce. Identifiers never come from SQL text. */
public record CommerceQuerySpec(List<String> metrics, List<String> dimensions, LocalDate start,
        LocalDate endExclusive, String comparison, List<String> products, int limit) {
    public static final List<String> METRICS = List.of("paid_gmv", "paid_orders", "aov", "refund_amount",
            "net_receipts", "refund_intensity", "visitor_sessions", "order_conversion_rate");

    public static CommerceQuerySpec parse(JsonNode node) {
        fields(node, Set.of("metricIds", "dimensions", "dateRange", "comparison", "filters", "limit"));
        List<String> metrics = strings(node.get("metricIds"), 1, 8, 96);
        List<String> dimensions = strings(node.get("dimensions"), 0, 3, 20);
        if (!METRICS.containsAll(metrics) || !Set.of("day", "store", "sku").containsAll(dimensions)) {
            throw invalid("未知指标或维度");
        }
        LocalDate[] dates = dates(node.get("dateRange"));
        String comparison = node.path("comparison").asText();
        if (!Set.of("NONE", "PREVIOUS_PERIOD", "PREVIOUS_WEEK_SAME_DAYS").contains(comparison)) {
            throw invalid("不支持的对比方式");
        }
        if (comparison.equals("PREVIOUS_WEEK_SAME_DAYS") && ChronoUnit.DAYS.between(dates[0], dates[1]) > 7) {
            throw invalid("同星期对比最多支持 7 天，请使用前一周期");
        }
        JsonNode filters = node.path("filters");
        if (!filters.isArray() || filters.size() > 1) throw invalid("仅支持单个商品过滤器");
        List<String> products = List.of();
        if (!filters.isEmpty()) {
            JsonNode filter = filters.get(0);
            fields(filter, Set.of("field", "operator", "values"));
            if (!"productId".equals(filter.path("field").asText()) || !"IN".equals(filter.path("operator").asText())) {
                throw invalid("仅支持 productId IN 过滤");
            }
            products = strings(filter.get("values"), 1, 20, 96);
        }
        if ((dimensions.contains("sku") || !products.isEmpty())
                && (!metrics.equals(List.of("paid_gmv")) || !dimensions.contains("store"))) {
            throw invalid("SKU 或商品过滤仅支持包含店铺维度的支付 GMV");
        }
        JsonNode limit = node.get("limit");
        if (limit == null || !limit.isIntegralNumber() || !limit.canConvertToInt()
                || limit.intValue() < 1 || limit.intValue() > 1000) throw invalid("聚合行数上限必须为 1–1000");
        return new CommerceQuerySpec(metrics, dimensions, dates[0], dates[1], comparison, products, limit.intValue());
    }

    public LocalDate baselineStart() {
        return comparison.equals("NONE") ? null : start.minusDays(offsetDays());
    }

    public LocalDate baselineEnd() {
        return comparison.equals("NONE") ? null : endExclusive.minusDays(offsetDays());
    }

    public long offsetDays() {
        return comparison.equals("PREVIOUS_WEEK_SAME_DAYS") ? 7 : ChronoUnit.DAYS.between(start, endExclusive);
    }

    public boolean skuPlan() { return dimensions.contains("sku") || !products.isEmpty(); }

    public static LocalDate[] dates(JsonNode range) {
        return dates(range, 90);
    }

    static LocalDate[] dates(JsonNode range, int maxDays) {
        fields(range, Set.of("start", "endExclusive"));
        try {
            LocalDate start = LocalDate.parse(range.path("start").asText());
            LocalDate end = LocalDate.parse(range.path("endExclusive").asText());
            long days = ChronoUnit.DAYS.between(start, end);
            if (days < 1 || days > maxDays) throw invalid("查询必须覆盖 1–" + maxDays + " 个完整业务日");
            return new LocalDate[] {start, end};
        }
        catch (java.time.DateTimeException ex) { throw invalid("日期必须使用 yyyy-MM-dd 格式"); }
    }

    static void fields(JsonNode node, Set<String> required) {
        if (node == null || !node.isObject()) throw invalid("查询字段必须为 JSON 对象");
        Set<String> actual = new HashSet<>();
        node.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(required)) throw invalid("查询包含未知字段或缺少必填字段");
    }

    static List<String> strings(JsonNode node, int min, int max, int maxLength) {
        if (node == null || !node.isArray() || node.size() < min || node.size() > max) throw invalid("列表长度不符合约束");
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > maxLength
                    || values.contains(value.asText())) throw invalid("列表值必须为唯一的非空字符串");
            values.add(value.asText());
        }
        return List.copyOf(values);
    }

    static CommerceException invalid(String message) { return new CommerceException(422, "UNSUPPORTED_QUERY", message); }
}
