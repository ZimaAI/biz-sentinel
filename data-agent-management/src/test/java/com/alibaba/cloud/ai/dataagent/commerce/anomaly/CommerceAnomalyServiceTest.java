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
package com.alibaba.cloud.ai.dataagent.commerce.anomaly;

import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.query.CommerceQueryService;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceAudit;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.array;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Real durable state/CAS/audit; deterministic query responses exercise monitor transitions. */
class CommerceAnomalyServiceTest {
    private final LocalDate day = LocalDate.parse("2026-09-20");
    private final CommerceSubject admin = new CommerceSubject("t_demo", "admin", Set.of("s1", "s2"), Set.of("TENANT_ADMIN"), 1);
    private CommerceStore store;
    private JdbcTemplate jdbc;
    private CommerceAnomalyService service;
    private CommerceQueryService queries;
    private final Map<String, ObjectNode> responses = new HashMap<>();
    private ObjectNode dataset;

    @BeforeEach void setup() {
        DriverManagerDataSource ds = new DriverManagerDataSource("jdbc:h2:mem:monitor" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds); store = new CommerceStore(ds);
        store.create("member", "t_demo:admin", "t_demo", object("storeIds", admin.storeIds(), "roles", admin.roles(), "authzVersion", 1, "enabled", true));
        queries = mock(CommerceQueryService.class);
        when(queries.metricManifestHash()).thenReturn("metrics-v1");
        ArrayNode metrics = array();
        for (String metric : List.of("paid_gmv", "paid_orders", "refund_amount", "order_conversion_rate")) metrics.add(object("metricId", metric, "version", 1, "state", "PUBLISHED"));
        when(queries.metrics()).thenReturn(metrics);
        when(queries.queryBound(any(), any(), anyString(), any())).thenAnswer(call -> {
            JsonNode spec = call.getArgument(3); String date = spec.path("dateRange").path("start").asText();
            String metric = spec.path("metricIds").get(0).asText();
            return responses.getOrDefault(date, result(metric, metric.equals("order_conversion_rate") ? "0.05" : "10000000", "COMPLETE", 100, 2000)).deepCopy();
        });
        service = new CommerceAnomalyService(store, queries, new CommercePolicy(store), new CommerceAudit(store));
        dataset = dataset("d1", "2026-09-21", "2026-09-21T00:00:00Z");
    }
    @AfterEach void close() { jdbc.execute("SHUTDOWN"); }

    @Test void requiresFourCompleteHistoricalDaysAndNeverAlertsOnMissingCurrentSource() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        for (int week = 4; week <= 8; week++) responses.put(day.minusWeeks(week).toString(), result("paid_gmv", null, "PARTIAL", 100, 2000));
        ObjectNode history = service.evaluate(admin, rule, dataset, day);
        assertThat(history.path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(history.path("sampleCount").asInt()).isEqualTo(3);
        assertThat(service.anomalies(admin)).isEmpty();
        responses.put(day.minusWeeks(4).toString(), result("paid_gmv", "10000000", "COMPLETE", 100, 2000));
        ObjectNode ready = service.evaluate(admin, rule, dataset("d2", "2026-09-21", "2026-09-21T01:00:00Z"), day);
        assertThat(ready.path("status").asText()).isEqualTo("TRIGGERED");
        responses.put(day.plusDays(1).toString(), result("paid_gmv", "0", "PARTIAL", 100, 2000));
        ObjectNode missing = service.evaluate(admin, rule, dataset, day.plusDays(1));
        assertThat(missing.path("status").asText()).isEqualTo("INCOMPLETE_DATA");
        assertThat(service.anomalies(admin)).hasSize(1);
    }

    @Test void exactRerunIsDeduplicatedAndMadZeroUsesThresholds() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        ObjectNode first = service.evaluate(admin, rule, dataset, day);
        assertThat(service.evaluate(admin, rule, dataset, day).toString()).isEqualTo(first.toString());
        assertThat(store.find("anomaly", "t_demo")).hasSize(1);
        assertThat(store.find("monitor-status", "t_demo")).hasSize(1);
        ObjectNode event = service.anomalies(admin).get(0);
        assertThat(event.path("scoreUnavailable").asText()).isEqualTo("MAD_ZERO");
        assertThat(event.path("current").path("value").asText()).isEqualTo("5000000");
    }

    @Test void missingDayDoesNotCountTowardsTwoConsecutiveNormalDays() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        String event = service.evaluate(admin, rule, dataset, day).path("anomalyId").asText();
        service.acknowledge(admin, event);
        service.evaluate(admin, rule, dataset, day.plusDays(1));
        assertThat(store.get("anomaly", event).path("normalDays").asInt()).isEqualTo(1);
        responses.put(day.plusDays(2).toString(), result("paid_gmv", null, "PARTIAL", 100, 2000));
        service.evaluate(admin, rule, dataset, day.plusDays(2));
        assertThat(store.get("anomaly", event).path("status").asText()).isEqualTo("ACKNOWLEDGED");
        service.evaluate(admin, rule, dataset, day.plusDays(3));
        assertThat(store.get("anomaly", event).path("normalDays").asInt()).isEqualTo(1);
        service.evaluate(admin, rule, dataset, day.plusDays(4));
        assertThat(store.get("anomaly", event).path("status").asText()).isEqualTo("RESOLVED");
    }

    @Test void repeatedAlertResetsRecoveryAndCooldownMergesIt() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        String first = service.evaluate(admin, rule, dataset, day).path("anomalyId").asText();
        service.evaluate(admin, rule, dataset, day.plusDays(1));
        responses.put(day.plusDays(2).toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        String merged = service.evaluate(admin, rule, dataset, day.plusDays(2)).path("anomalyId").asText();
        assertThat(store.get("anomaly", merged).path("status").asText()).isEqualTo("SUPPRESSED");
        assertThat(store.get("anomaly", merged).path("mergedInto").asText()).isEqualTo(first);
        assertThat(store.get("anomaly", first).path("normalDays").asInt()).isZero();
        service.evaluate(admin, rule, dataset, day.plusDays(3));
        assertThat(store.get("anomaly", first).path("status").asText()).isEqualTo("OPEN");
        service.evaluate(admin, rule, dataset, day.plusDays(4));
        assertThat(store.get("anomaly", first).path("status").asText()).isEqualTo("RESOLVED");
    }

    @Test void ratioUsesPercentagePointsAndRequiresCurrentAndReferenceSampleSizes() {
        ObjectNode rule = rule("order_conversion_rate");
        responses.put(day.toString(), result("order_conversion_rate", "0.04", "COMPLETE", 80, 2000));
        ObjectNode status = service.evaluate(admin, rule, dataset, day);
        assertThat(status.path("status").asText()).isEqualTo("TRIGGERED");
        assertThat(store.get("anomaly", status.path("anomalyId").asText()).path("reason").asText())
                .isEqualTo("达到绝对变化阈值；参考最近八个同星期完整日的中位数");
        assertThat(CommerceAnomalyService.threshold(new BigDecimal("0.046"), new BigDecimal("0.05"), rule.path("definition"))).isFalse();
        responses.put(day.plusDays(1).toString(), result("order_conversion_rate", "0.03", "COMPLETE", 29, 2000));
        assertThat(service.evaluate(admin, rule, dataset, day.plusDays(1)).path("status").asText()).isEqualTo("SMALL_SAMPLE");
        responses.put(day.toString(), result("order_conversion_rate", "0.04", "COMPLETE", 80, 2000));
        for (int week = 4; week <= 8; week++) responses.put(day.minusWeeks(week).toString(), result("order_conversion_rate", "0.05", "COMPLETE", 10, 200));
        assertThat(service.evaluate(admin, rule, dataset("d2", "2026-09-21", "2026-09-21T01:00:00Z"), day).path("status").asText()).isEqualTo("INSUFFICIENT_HISTORY");
    }

    @Test void ruleCasRolesScopeAndMetricVersionAreEnforced() {
        ObjectNode rule = rule("paid_gmv");
        ObjectNode changed = definition("paid_gmv"); changed.put("absoluteThreshold", "2000000");
        ObjectNode updated = service.saveRule(admin, rule.path("ruleId").asText(), object("definition", changed, "expectedVersion", 1));
        assertThat(updated.path("version").asInt()).isEqualTo(2);
        assertThat(store.find("rule-history", "t_demo")).hasSize(1);
        assertThatThrownBy(() -> service.saveRule(admin, rule.path("ruleId").asText(), object("definition", changed, "expectedVersion", 1)))
                .isInstanceOf(CommerceException.class).hasMessageContaining("刷新");
        ObjectNode forbidden = definition("paid_gmv"); forbidden.set("storeIds", array().add("s3"));
        assertThatThrownBy(() -> service.saveRule(admin, null, forbidden)).isInstanceOf(CommerceException.class);
        CommerceSubject viewer = new CommerceSubject("t_demo", "viewer", Set.of("s1"), Set.of("VIEWER"), 1);
        store.create("member", "t_demo:viewer", "t_demo", object("storeIds", viewer.storeIds(), "roles", viewer.roles(), "authzVersion", 1, "enabled", true));
        assertThatThrownBy(() -> service.saveRule(viewer, null, definition("paid_gmv"))).isInstanceOf(CommerceException.class);
        ObjectNode invalid = definition("paid_gmv"); invalid.remove("enabled");
        assertThatThrownBy(() -> service.saveRule(admin, null, invalid)).isInstanceOf(CommerceException.class);
        when(queries.metricManifestHash()).thenReturn("metrics-v2");
        assertThat(service.evaluate(admin, updated, dataset, day).path("status").asText()).isEqualTo("METRIC_VERSION_CHANGED");
    }

    @Test void newRuleVersionDoesNotRecoverOldRuleEventsAndDatasetRevisionSupersedesPriorValues() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        String first = service.evaluate(admin, rule, dataset, day).path("anomalyId").asText();
        String revised = service.evaluate(admin, rule, dataset("d2", "2026-09-21", "2026-09-21T01:00:00Z"), day).path("anomalyId").asText();
        assertThat(store.get("anomaly", first).path("status").asText()).isEqualTo("SUPPRESSED");
        assertThat(store.get("anomaly", revised).path("supersedesEventId").asText()).isEqualTo(first);
        ObjectNode updated = service.saveRule(admin, rule.path("ruleId").asText(), object("definition", definition("paid_gmv"), "expectedVersion", 1));
        service.evaluate(admin, updated, dataset, day.plusDays(1));
        service.evaluate(admin, updated, dataset, day.plusDays(2));
        assertThat(store.get("anomaly", revised).path("status").asText()).isEqualTo("OPEN");
    }

    @Test void schedulerCatchesUpDailyObservationsInsteadOfSkippingRecoveryWindow() {
        ObjectNode rule = rule("paid_gmv");
        responses.put(day.toString(), result("paid_gmv", "5000000", "COMPLETE", 100, 2000));
        store.create("dataset", "d1", "t_demo", dataset);
        service.tick();
        String event = service.anomalies(admin).get(0).path("anomalyId").asText();
        store.create("dataset", "d2", "t_demo", dataset("d2", "2026-09-23", "2026-09-23T00:00:00Z"));
        service.tick();
        assertThat(store.get("anomaly", event).path("status").asText()).isEqualTo("RESOLVED");
        assertThat(store.find("monitor-status", "t_demo")).hasSize(3);
        service.tick();
        assertThat(store.find("monitor-status", "t_demo")).hasSize(3);
        assertThat(service.rules(admin)).hasSize(1);
        assertThat(rule.path("metricVersion").asInt()).isEqualTo(1);
    }

    private ObjectNode rule(String metric) { return service.saveRule(admin, null, definition(metric)); }
    private ObjectNode definition(String metric) {
        return object("name", "规则 " + metric, "metricId", metric, "storeIds", List.of("s1"), "direction", "DOWN", "baseline", "SAME_WEEKDAY_MEDIAN_8",
                "relativeThreshold", metric.equals("order_conversion_rate") ? null : "0.15", "absoluteThreshold", metric.equals("order_conversion_rate") ? "0.5" : "1000000",
                "unit", metric.equals("order_conversion_rate") ? "PP" : "CNY_CENT", "cooldownHours", 24, "enabled", true);
    }
    private ObjectNode dataset(String id, String end, String published) {
        return object("datasetVersionId", id, "status", "PUBLISHED", "publishedAt", published,
                "stores", List.of(object("storeId", "s1")), "scope", object("tenantId", "t_demo", "storeIds", List.of("s1"), "authzVersion", 1),
                "coverage", object("start", "2026-07-01", "endExclusive", end));
    }
    private ObjectNode result(String metric, String value, String quality, int orders, int sessions) {
        ArrayNode cells = array().add(object("field", metric, "value", value, "unit", metric.equals("order_conversion_rate") ? "RATIO" : "CNY_CENT"));
        if (metric.equals("order_conversion_rate")) {
            cells.add(object("field", "paid_orders", "value", Integer.toString(orders), "unit", "COUNT"));
            cells.add(object("field", "visitor_sessions", "value", Integer.toString(sessions), "unit", "COUNT"));
        }
        return object("totals", object("cells", cells), "quality", object("status", quality), "metricVersions", object(metric, 1));
    }
}
