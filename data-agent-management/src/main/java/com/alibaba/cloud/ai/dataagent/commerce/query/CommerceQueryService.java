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

import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.MAPPER;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.array;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.sha256;

/** Fixed metric plans over immutable snapshots. No caller can supply SQL or identifiers. */
@Service
@Profile("commerce")
public class CommerceQueryService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int MAX_INTERMEDIATE_ROWS = 10000;
    private static final String BOUNDARY = " tenant_id=:tenant AND dataset_version_id=:dataset AND store_id IN (:stores) ";
    private final NamedParameterJdbcTemplate jdbc;
    private final CommerceStore store;
    private final CommercePolicy policy;
    private final ArrayNode registry;
    private final String manifestHash;
    private final Map<String, Semaphore> tenantPermits = new ConcurrentHashMap<>();

    public CommerceQueryService(@Qualifier("commerceQueryJdbc") NamedParameterJdbcTemplate jdbc,
            CommerceStore store, CommercePolicy policy) {
        this.jdbc = jdbc;
        this.store = store;
        this.policy = policy;
        this.jdbc.getJdbcTemplate().setQueryTimeout(3);
        this.jdbc.getJdbcTemplate().setMaxRows(MAX_INTERMEDIATE_ROWS + 1);
        try (var input = getClass().getResourceAsStream("/commerce/metrics.json")) {
            if (input == null) throw new IllegalStateException("Missing Commerce metric registry");
            registry = (ArrayNode) MAPPER.readTree(input);
            manifestHash = sha256(registry.toString());
        }
        catch (IOException ex) { throw new IllegalStateException("Cannot load Commerce metrics", ex); }
    }

    public JsonNode metrics() { return registry.deepCopy(); }
    public String metricManifestHash() { return manifestHash; }

    public ObjectNode scope(CommerceSubject subject, List<String> requestedStores) {
        policy.assertCurrent(subject);
        Set<String> requested = new TreeSet<>(requestedStores == null || requestedStores.isEmpty()
                ? subject.storeIds() : requestedStores);
        if (requested.isEmpty() || !subject.storeIds().containsAll(requested)) {
            throw new CommerceException(403, "STORE_ACCESS_DENIED", "请求包含无权访问的店铺");
        }
        if (requested.size() > 100) throw new CommerceException(429, "QUERY_BUDGET_EXCEEDED", "单次最多查询 100 家店铺");
        return object("tenantId", subject.tenantId(), "storeIds", requested, "authzVersion", subject.authzVersion());
    }

    public ObjectNode chooseDataset(CommerceSubject subject, List<String> requestedStores, JsonNode dateRange) {
        ObjectNode scope = scope(subject, requestedStores);
        // Dataset selection covers both the requested window and its preceding comparison.
        LocalDate[] range = CommerceQuerySpec.dates(dateRange, 180);
        Set<String> stores = strings(scope.path("storeIds"));
        return store.find("dataset", subject.tenantId()).stream()
                .filter(data -> "PUBLISHED".equals(data.path("status").asText()))
                .filter(data -> subject.tenantId().equals(data.path("tenantId").asText(data.path("scope").path("tenantId").asText())))
                .filter(data -> covered(data, range[0], range[1]) && datasetStores(data).containsAll(stores))
                .max(Comparator.comparing(data -> data.path("publishedAt").asText(data.path("quality").path("publishedAt").asText())))
                .map(ObjectNode::deepCopy)
                .orElseThrow(() -> new CommerceException(422, "DATASET_NOT_AVAILABLE", "没有覆盖所选店铺及日期的已发布数据版本"));
    }

    public ObjectNode query(CommerceSubject subject, List<String> requestedStores, JsonNode specNode) {
        CommerceQuerySpec spec = CommerceQuerySpec.parse(specNode);
        ObjectNode boundScope = scope(subject, requestedStores);
        ObjectNode dataset = chooseDataset(subject, new ArrayList<>(strings(boundScope.path("storeIds"))),
                range(spec.baselineStart() == null ? spec.start() : spec.baselineStart(), spec.endExclusive()));
        return queryBound(subject, boundScope, dataset.path("datasetVersionId").asText(), specNode);
    }

    public ObjectNode queryBound(CommerceSubject subject, JsonNode boundScope, String datasetId, JsonNode specNode) {
        CommerceQuerySpec spec = CommerceQuerySpec.parse(specNode);
        assertScope(subject, boundScope);
        ObjectNode dataset = dataset(subject, boundScope, datasetId,
                spec.baselineStart() == null ? spec.start() : spec.baselineStart(), spec.endExclusive());
        Semaphore permits = tenantPermits.computeIfAbsent(subject.tenantId(), ignored -> new Semaphore(4));
        if (!permits.tryAcquire()) throw new CommerceException(429, "QUERY_CONCURRENCY_LIMIT", "查询繁忙，请稍后重试");
        long began = System.nanoTime();
        List<String> sql = new ArrayList<>();
        try {
            Period current = aggregate(subject, boundScope, dataset, spec, spec.start(), spec.endExclusive(), 0, sql);
            Period baseline = spec.baselineStart() == null ? null : aggregate(subject, boundScope, dataset, spec,
                    spec.baselineStart(), spec.baselineEnd(), spec.offsetDays(), sql);
            ArrayNode rows = array();
            Set<String> keys = new TreeSet<>(current.groups.keySet());
            if (baseline != null) keys.addAll(baseline.groups.keySet());
            boolean complete = true;
            for (String key : keys) {
                Bucket now = current.groups.get(key);
                Bucket before = baseline == null ? null : baseline.groups.get(key);
                if (now == null) now = Bucket.zero(before.dimensions, current.complete);
                if (before == null && baseline != null) before = Bucket.zero(now.dimensions, baseline.complete);
                ObjectNode row = resultRow(key, now, before, spec.metrics());
                complete &= !hasMissing(row);
                if (rows.size() < spec.limit()) rows.add(row);
            }
            ObjectNode totals = resultRow("total", current.total, baseline == null ? null : baseline.total, spec.metrics());
            complete &= !hasMissing(totals);
            ObjectNode result = resultBase(boundScope, dataset, range(spec.start(), spec.endExclusive()),
                    spec.baselineStart() == null ? null : range(spec.baselineStart(), spec.baselineEnd()), spec.metrics());
            result.set("rows", rows);
            result.set("totals", totals);
            result.put("rowCount", keys.size());
            result.put("truncated", keys.size() > spec.limit());
            result.set("quality", quality(dataset, complete, "数据来源或业务日期不完整，相关指标不补零"));
            if (overOne(totals) || java.util.stream.StreamSupport.stream(rows.spliterator(), false).anyMatch(CommerceQueryService::overOne)) {
                ((ArrayNode) result.path("quality").path("warnings")).add("支付订单转化比超过 100%，请检查订单与会话来源定义；此指标不是用户级转化率");
            }
            result.set("columns", columns(spec.metrics(), spec.dimensions(), baseline != null));
            // Guest sessions are deliberately read-only.  The regular query path
            // records query artifacts and evidence for auditability, which is a
            // database write even when the caller only requested a view.  Keep the
            // aggregate result in memory for guest overview browsing and skip that
            // persistence side effect entirely.
            return subject.guest() ? result : persistEvidence(subject, result, specNode, sql, "QUERY_RESULT", began);
        }
        catch (DataAccessException ex) { throw new CommerceException(503, "QUERY_UNAVAILABLE", "经营数据查询暂时不可用"); }
        finally { permits.release(); }
    }

    public ObjectNode overview(CommerceSubject subject, List<String> stores, String start, String endExclusive, String comparison) {
        JsonNode spec = object("metricIds", CommerceQuerySpec.METRICS, "dimensions", List.of("day", "store"),
                "dateRange", object("start", start, "endExclusive", endExclusive), "comparison", comparison,
                "filters", List.of(), "limit", 1000);
        CommerceQuerySpec parsed = CommerceQuerySpec.parse(spec);
        if (parsed.comparison().equals("NONE")) throw CommerceQuerySpec.invalid("总览需要选择对比区间");
        ObjectNode scoped = scope(subject, stores);
        ObjectNode data = chooseDataset(subject, new ArrayList<>(strings(scoped.path("storeIds"))), range(parsed.baselineStart(), parsed.endExclusive()));
        String datasetId = data.path("datasetVersionId").asText();
        ObjectNode byStoreSpec = ((ObjectNode) spec).deepCopy();
        byStoreSpec.set("dimensions", MAPPER.valueToTree(List.of("store")));
        ObjectNode byStore = queryBound(subject, scoped, datasetId, byStoreSpec);
        ObjectNode byDaySpec = ((ObjectNode) spec).deepCopy();
        byDaySpec.set("dimensions", MAPPER.valueToTree(List.of("day")));
        if (parsed.endExclusive().equals(parsed.start().plusDays(1))) {
            LocalDate trendStart = parsed.start().minusDays(6);
            LocalDate trendBaseline = trendStart.minusDays(7);
            if (covered(data, trendBaseline, parsed.endExclusive())) {
                byDaySpec.set("dateRange", range(trendStart, parsed.endExclusive()));
            }
        }
        ObjectNode byDay = queryBound(subject, scoped, datasetId, byDaySpec);
        ArrayNode baselineCells = array();
        for (JsonNode cell : byStore.path("totals").path("cells")) {
            if (cell.path("field").asText().endsWith("_baseline")) {
                ObjectNode copied = cell.deepCopy();
                copied.put("field", cell.path("field").asText().replace("_baseline", ""));
                baselineCells.add(copied);
            }
        }
        return object("scope", scoped, "dateRange", spec.path("dateRange"), "comparisonRange", byStore.path("comparisonRange"),
                "datasetVersionId", datasetId, "quality", byStore.path("quality"), "totals", byStore.path("totals"),
                "baselineTotals", object("rowKey", "baseline_total", "dimensions", object(), "cells", baselineCells),
                "trend", byDay.path("rows"), "stores", byStore.path("rows"),
                "trendRange", byDay.path("dateRange"), "trendComparisonRange", byDay.path("comparisonRange"),
                "evidenceId", byStore.path("evidenceId"), "trendEvidenceId", byDay.path("evidenceId"),
                "metricVersions", byStore.path("metricVersions"), "metricManifestHash", manifestHash,
                "synthetic", data.path("synthetic").asBoolean(), "summary", overviewSummary(byStore));
    }

    private static ObjectNode overviewSummary(JsonNode result) {
        BigDecimal totalDelta = numericCell(result.path("totals"), "paid_gmv_delta");
        JsonNode worst = null;
        BigDecimal worstDelta = BigDecimal.ZERO;
        for (JsonNode row : result.path("rows")) {
            BigDecimal delta = numericCell(row, "paid_gmv_delta");
            if (delta != null && delta.compareTo(worstDelta) < 0) { worst = row; worstDelta = delta; }
        }
        boolean comparisonComplete = totalDelta != null;
        BigDecimal contribution = !comparisonComplete || totalDelta.signum() == 0 || worst == null
                ? null : divide(worstDelta, totalDelta);
        return object("metricId", "paid_gmv", "storeId", worst == null ? null : worst.path("dimensions").path("store").asText(),
                "storeName", worst == null ? null : worst.path("dimensions").path("storeName").asText(),
                "deltaCents", worst == null ? null : worstDelta.stripTrailingZeros().toPlainString(),
                "totalDeltaCents", totalDelta == null ? null : totalDelta.stripTrailingZeros().toPlainString(),
                "netContributionRatio", contribution == null ? null : contribution.stripTrailingZeros().toPlainString(),
                "evidenceId", result.path("evidenceId"), "type", !comparisonComplete ? "INCOMPLETE"
                        : totalDelta.signum() < 0 ? "DECLINE" : worst != null ? "MIXED" : "STABLE_OR_GROWTH");
    }

    private static BigDecimal numericCell(JsonNode row, String field) {
        for (JsonNode cell : row.path("cells")) if (field.equals(cell.path("field").asText()) && !cell.path("value").isNull()) {
            return new BigDecimal(cell.path("value").asText());
        }
        return null;
    }

    public ObjectNode evidence(CommerceSubject subject, String id) {
        ObjectNode evidence = store.get("evidence", id);
        policy.assertReadable(subject, evidence);
        ObjectNode visible = evidence.deepCopy();
        visible.remove(List.of("subjectId", "_revision"));
        return visible;
    }

    public ObjectNode inventory(CommerceSubject subject, JsonNode boundScope, String datasetId,
            List<String> products, JsonNode dateRange) {
        assertScope(subject, boundScope);
        LocalDate[] dates = CommerceQuerySpec.dates(dateRange);
        if (products == null || products.isEmpty() || products.size() > 20
                || products.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 96)) {
            throw CommerceQuerySpec.invalid("库存信号需要指定 1–20 个商品");
        }
        ObjectNode data = dataset(subject, boundScope, datasetId, dates[0], dates[1]);
        long began = System.nanoTime();
        String sql = "SELECT store_id, product_id, biz_date, closing_stock, stockout_minutes, quality_status FROM cl_inventory_daily WHERE"
                + BOUNDARY + "AND biz_date>=:start_date AND biz_date<:end_date AND product_id IN (:products) ORDER BY store_id,product_id,biz_date LIMIT 1001";
        MapSqlParameterSource params = parameters(boundScope, datasetId, dates[0], dates[1]).addValue("products", products);
        ArrayNode rows = array();
        boolean complete = true;
        Semaphore permits = tenantPermits.computeIfAbsent(subject.tenantId(), ignored -> new Semaphore(4));
        if (!permits.tryAcquire()) throw new CommerceException(429, "QUERY_CONCURRENCY_LIMIT", "查询繁忙，请稍后重试");
        try {
            policy.assertCurrent(subject);
            List<Map<String, Object>> values = jdbc.queryForList(sql, params);
            Set<String> observed = new LinkedHashSet<>();
            for (Map<String, Object> row : values) {
                String storeId = text(row, "store_id");
                String product = text(row, "product_id");
                String day = text(row, "biz_date");
                boolean valid = "COMPLETE".equals(text(row, "quality_status")) && hasSource(data, "inventory_daily")
                        && sourceComplete(data, "inventory_daily", storeId, LocalDate.parse(day));
                complete &= valid;
                observed.add(storeId + ":" + product + ":" + day);
                if (rows.size() < 1000) rows.add(object("rowKey", storeId + ":" + product + ":" + day,
                        "dimensions", object("store", storeId, "sku", product, "day", day),
                        "cells", List.of(cell("closing_stock", valid ? decimal(row, "closing_stock") : null, "COUNT", valid ? null : "MISSING_DATA"),
                                cell("stockout_minutes", valid ? decimal(row, "stockout_minutes") : null, "COUNT", valid ? null : "MISSING_DATA"))));
            }
            // Missing product/day rows are absence of a snapshot, not evidence of no stockout.
            String productsSql = "SELECT store_id,product_id FROM cl_product WHERE" + BOUNDARY + "AND product_id IN (:products) LIMIT 2001";
            List<Map<String, Object>> known = jdbc.queryForList(productsSql, params);
            long expected = known.size() * java.time.temporal.ChronoUnit.DAYS.between(dates[0], dates[1]);
            complete &= expected > 0 && observed.size() == expected;
            ObjectNode result = resultBase(boundScope, data, dateRange, null, List.of());
            result.set("columns", array().add(object("field", "closing_stock", "label", "期末库存", "unit", "COUNT"))
                    .add(object("field", "stockout_minutes", "label", "缺货分钟数", "unit", "COUNT")));
            result.set("rows", rows);
            result.set("totals", object("rowKey", "total", "dimensions", object(), "cells", array()));
            result.put("rowCount", values.size());
            result.put("truncated", values.size() > 1000);
            result.set("quality", quality(data, complete, "库存快照存在缺失，无法由缺失记录推断库存状态"));
            return persistEvidence(subject, result, object("products", products, "dateRange", dateRange), List.of(sql, productsSql), "INVENTORY_SIGNAL", began);
        }
        catch (DataAccessException ex) { throw new CommerceException(503, "QUERY_UNAVAILABLE", "库存信号查询暂时不可用"); }
        finally { permits.release(); }
    }

    private Period aggregate(CommerceSubject subject, JsonNode scope, JsonNode data, CommerceQuerySpec spec,
            LocalDate start, LocalDate end, long dayOffset, List<String> executedSql) {
        Map<String, Bucket> atomic = new TreeMap<>();
        Set<String> stores = strings(scope.path("storeIds"));
        String datasetId = data.path("datasetVersionId").asText();
        MapSqlParameterSource params = parameters(scope, datasetId, start, end);
        if (!spec.products().isEmpty()) params.addValue("products", spec.products());
        boolean paymentSource = hasSource(data, "payments");
        boolean refundSource = hasSource(data, "refunds");
        boolean trafficSource = hasSource(data, "traffic_daily");
        Map<String, String> productNames = new LinkedHashMap<>();
        if (spec.skuPlan()) {
            String productsSql = "SELECT store_id,product_id,name FROM cl_product WHERE" + BOUNDARY
                    + (spec.products().isEmpty() ? "" : "AND product_id IN (:products) ") + "ORDER BY store_id,product_id LIMIT 10001";
            for (Map<String, Object> product : execute(subject, productsSql, params, executedSql)) {
                productNames.put(text(product, "store_id") + ":" + text(product, "product_id"), text(product, "name"));
            }
        }
        for (LocalDate day = start; day.isBefore(end); day = day.plusDays(1)) {
            for (String storeId : stores) {
                if (spec.skuPlan()) {
                    for (String pair : productNames.keySet()) {
                        if (pair.startsWith(storeId + ":")) {
                            String productId = pair.substring(storeId.length() + 1);
                            Bucket bucket = seed(data, day, storeId, productId, dayOffset, paymentSource && hasSource(data, "order_items"), false, false);
                            bucket.dimensions.put("skuName", productNames.get(pair));
                            atomic.put(atomKey(day, storeId, productId), bucket);
                        }
                    }
                }
                else atomic.put(atomKey(day, storeId, ""), seed(data, day, storeId, "", dayOffset, paymentSource, refundSource, trafficSource));
            }
        }
        if (atomic.size() > MAX_INTERMEDIATE_ROWS) throw budget();
        if (spec.skuPlan()) {
            String sql = "SELECT i.store_id,i.product_id,CAST(p.paid_at_utc + INTERVAL '8' HOUR AS DATE) AS biz_day,"
                    + "SUM(i.allocated_paid_cents) AS paid_gmv FROM cl_order_item i JOIN cl_payment p ON "
                    + "p.tenant_id=i.tenant_id AND p.dataset_version_id=i.dataset_version_id AND p.store_id=i.store_id AND p.order_id=i.order_id "
                    + "WHERE i.tenant_id=:tenant AND i.dataset_version_id=:dataset AND i.store_id IN (:stores) "
                    + "AND p.tenant_id=:tenant AND p.dataset_version_id=:dataset AND p.store_id IN (:stores) "
                    + "AND p.paid_at_utc>=:start_utc AND p.paid_at_utc<:end_utc "
                    + (spec.products().isEmpty() ? "" : "AND i.product_id IN (:products) ")
                    + "GROUP BY i.store_id,i.product_id,CAST(p.paid_at_utc + INTERVAL '8' HOUR AS DATE) LIMIT 10001";
            for (Map<String, Object> row : execute(subject, sql, params, executedSql)) {
                Bucket bucket = atomic.get(atomKey(LocalDate.parse(text(row, "biz_day")), text(row, "store_id"), text(row, "product_id")));
                if (bucket != null) bucket.gmv = decimal(row, "paid_gmv");
            }
        }
        else {
            if (paymentSource) {
                String sql = "SELECT store_id,CAST(paid_at_utc + INTERVAL '8' HOUR AS DATE) AS biz_day,SUM(amount_cents) AS paid_gmv,COUNT(*) AS paid_orders FROM cl_payment WHERE"
                        + BOUNDARY + "AND paid_at_utc>=:start_utc AND paid_at_utc<:end_utc GROUP BY store_id,CAST(paid_at_utc + INTERVAL '8' HOUR AS DATE) LIMIT 10001";
                for (Map<String, Object> row : execute(subject, sql, params, executedSql)) {
                    Bucket bucket = atomic.get(atomKey(LocalDate.parse(text(row, "biz_day")), text(row, "store_id"), ""));
                    if (bucket != null) { bucket.gmv = decimal(row, "paid_gmv"); bucket.orders = decimal(row, "paid_orders"); }
                }
            }
            if (refundSource) {
                String sql = "SELECT store_id,CAST(succeeded_at_utc + INTERVAL '8' HOUR AS DATE) AS biz_day,SUM(amount_cents) AS refund_amount FROM cl_refund WHERE"
                        + BOUNDARY + "AND succeeded_at_utc>=:start_utc AND succeeded_at_utc<:end_utc GROUP BY store_id,CAST(succeeded_at_utc + INTERVAL '8' HOUR AS DATE) LIMIT 10001";
                for (Map<String, Object> row : execute(subject, sql, params, executedSql)) {
                    Bucket bucket = atomic.get(atomKey(LocalDate.parse(text(row, "biz_day")), text(row, "store_id"), ""));
                    if (bucket != null) bucket.refunds = decimal(row, "refund_amount");
                }
            }
            if (trafficSource) {
                String sql = "SELECT store_id,biz_date AS biz_day,visitor_sessions,quality_status FROM cl_traffic_daily WHERE"
                        + BOUNDARY + "AND biz_date>=:start_date AND biz_date<:end_date LIMIT 10001";
                for (Map<String, Object> row : execute(subject, sql, params, executedSql)) {
                    LocalDate day = LocalDate.parse(text(row, "biz_day"));
                    String storeId = text(row, "store_id");
                    Bucket bucket = atomic.get(atomKey(day, storeId, ""));
                    if (bucket != null) {
                        bucket.sessions = decimal(row, "visitor_sessions");
                        bucket.trafficComplete = "COMPLETE".equals(text(row, "quality_status")) && sourceComplete(data, "traffic_daily", storeId, day);
                    }
                }
            }
        }
        Map<String, Bucket> groups = new TreeMap<>();
        Bucket total = new Bucket(object());
        boolean complete = true;
        for (Bucket atom : atomic.values()) {
            ObjectNode dimensions = object();
            for (String dimension : spec.dimensions()) dimensions.set(dimension, atom.dimensions.path(dimension));
            String key = dimensions.isEmpty() ? "all" : dimensions.toString();
            Bucket group = groups.computeIfAbsent(key, ignored -> new Bucket(dimensions));
            group.add(atom);
            total.add(atom);
            if (spec.dimensions().contains("store")) group.dimensions.put("storeName", storeName(data, atom.dimensions.path("store").asText()));
            if (spec.dimensions().contains("sku")) group.dimensions.set("skuName", atom.dimensions.path("skuName"));
            complete &= atom.paymentComplete;
        }
        if (groups.isEmpty() && spec.dimensions().isEmpty()) groups.put("all", total);
        return new Period(groups, total, complete);
    }

    private Bucket seed(JsonNode data, LocalDate day, String storeId, String productId, long offset,
            boolean payments, boolean refunds, boolean traffic) {
        Bucket bucket = new Bucket(object("day", day.plusDays(offset).toString(), "store", storeId, "sku", productId));
        bucket.paymentComplete = payments && sourceComplete(data, "payments", storeId, day);
        bucket.refundComplete = refunds && sourceComplete(data, "refunds", storeId, day);
        bucket.trafficComplete = false; // A concrete COMPLETE traffic row is required, even for zero sessions.
        return bucket;
    }

    private List<Map<String, Object>> execute(CommerceSubject subject, String sql, MapSqlParameterSource params, List<String> sqlLog) {
        policy.assertCurrent(subject);
        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);
        if (rows.size() > MAX_INTERMEDIATE_ROWS) throw budget();
        sqlLog.add(sql);
        return rows;
    }

    private ObjectNode resultRow(String key, Bucket now, Bucket before, List<String> metrics) {
        ArrayNode cells = array();
        for (String metric : metrics) {
            Value current = now.value(metric);
            String unit = unit(metric);
            cells.add(cell(metric, current.number, unit, current.reason));
            if (before != null) {
                Value baseline = before.value(metric);
                cells.add(cell(metric + "_baseline", baseline.number, unit, baseline.reason));
                BigDecimal delta = current.number == null || baseline.number == null ? null : current.number.subtract(baseline.number);
                cells.add(cell(metric + "_delta", "RATIO".equals(unit) && delta != null ? delta.multiply(BigDecimal.valueOf(100)) : delta,
                        "RATIO".equals(unit) ? "PP" : unit, delta == null ? "INCOMPLETE_COMPARISON" : null));
                BigDecimal change = delta == null || baseline.number.signum() == 0 ? null : divide(delta, baseline.number);
                cells.add(cell(metric + "_change_ratio", change, "RATIO", change == null ? (delta == null ? "INCOMPLETE_COMPARISON" : "NO_COMPARABLE_BASELINE") : null));
            }
        }
        return object("rowKey", key, "dimensions", now.dimensions, "cells", cells);
    }

    private ObjectNode resultBase(JsonNode scope, JsonNode dataset, JsonNode dateRange, JsonNode comparisonRange, List<String> metrics) {
        ObjectNode versions = object();
        metrics.forEach(metric -> versions.put(metric, 1));
        return object("scope", scope, "dateRange", dateRange, "comparisonRange", comparisonRange,
                "datasetVersionId", dataset.path("datasetVersionId").asText(), "metricVersions", versions);
    }

    private ObjectNode persistEvidence(CommerceSubject subject, ObjectNode result, JsonNode spec, List<String> sql, String kind, long began) {
        assertScope(subject, result.path("scope"));
        String evidenceId = "ev_" + UUID.randomUUID();
        String artifactId = "qa_" + UUID.randomUUID();
        result.put("evidenceId", evidenceId);
        result.put("queryArtifactId", artifactId);
        if (result.toString().getBytes(StandardCharsets.UTF_8).length > 1_048_576) throw budget();
        ObjectNode parameters = object("storeIds", result.path("scope").path("storeIds"),
                "dateRange", result.path("dateRange"), "comparisonRange", result.path("comparisonRange"),
                "datasetVersionId", result.path("datasetVersionId"));
        ObjectNode evidence = object("evidenceId", evidenceId, "runId", null, "kind", kind,
                "datasetVersionId", result.path("datasetVersionId"), "metricManifestHash", manifestHash,
                "scope", result.path("scope"), "subjectId", subject.subjectId(), "result", result,
                "sqlTemplate", String.join(";\n\n", new LinkedHashSet<>(sql)), "redactedParameters", parameters,
                "resultHash", sha256(result.toString()), "durationMs", (System.nanoTime() - began) / 1_000_000,
                "createdAt", Instant.now().toString());
        store.transaction(() -> {
            store.create("queryArtifact", artifactId, subject.tenantId(), object("queryArtifactId", artifactId,
                    "scope", result.path("scope"), "subjectId", subject.subjectId(), "querySpec", spec,
                    "queryHash", sha256(spec.toString()), "datasetVersionId", result.path("datasetVersionId"),
                    "metricManifestHash", manifestHash, "evidenceId", evidenceId));
            store.create("evidence", evidenceId, subject.tenantId(), evidence);
            return null;
        });
        assertScope(subject, result.path("scope"));
        return result;
    }

    private ObjectNode dataset(CommerceSubject subject, JsonNode scope, String id, LocalDate start, LocalDate end) {
        ObjectNode dataset = store.get("dataset", id);
        if (!subject.tenantId().equals(dataset.path("tenantId").asText(dataset.path("scope").path("tenantId").asText()))
                || !"PUBLISHED".equals(dataset.path("status").asText())) {
            throw new CommerceException(404, "NOT_FOUND", "数据版本不存在或不可见");
        }
        if (!covered(dataset, start, end) || !datasetStores(dataset).containsAll(strings(scope.path("storeIds")))) {
            throw new CommerceException(422, "DATASET_COVERAGE_MISSING", "绑定的数据版本不覆盖查询日期或店铺");
        }
        return dataset;
    }

    private void assertScope(CommerceSubject subject, JsonNode scope) {
        policy.assertCurrent(subject);
        if (!subject.tenantId().equals(scope.path("tenantId").asText())
                || subject.authzVersion() != scope.path("authzVersion").asLong(-1)
                || strings(scope.path("storeIds")).isEmpty()
                || !subject.storeIds().containsAll(strings(scope.path("storeIds")))) {
            throw new CommerceException(403, "ACCESS_REVOKED", "分析范围授权已失效");
        }
    }

    private static boolean covered(JsonNode data, LocalDate start, LocalDate end) {
        try {
            return !start.isBefore(LocalDate.parse(data.path("coverage").path("start").asText()))
                    && !end.isAfter(LocalDate.parse(data.path("coverage").path("endExclusive").asText()));
        }
        catch (java.time.DateTimeException ex) { return false; }
    }

    private static boolean hasSource(JsonNode data, String source) {
        for (JsonNode file : data.path("files")) if ((source + ".csv").equals(file.path("name").asText())) return true;
        for (JsonNode table : data.path("tables")) if (source.equals(table.path("name").asText()) || (source + ".csv").equals(table.path("name").asText())) return true;
        return false;
    }

    private static boolean sourceComplete(JsonNode data, String source, String storeId, LocalDate day) {
        JsonNode completeDays = data.path("coverage").path("completeBusinessDates");
        if ((source.equals("payments") || source.equals("refunds")) && completeDays.isArray()
                && !strings(completeDays).contains(day.toString())) return false;
        for (JsonNode missing : data.path("quality").path("missingCoverage")) {
            if (source.equals(missing.path("source").asText()) && storeId.equals(missing.path("storeId").asText())
                    && day.toString().equals(missing.path("date").asText())) return false;
        }
        return !"INVALID".equals(data.path("quality").path("sourceStatus").path(source).asText());
    }

    private static Set<String> datasetStores(JsonNode data) {
        Set<String> values = new TreeSet<>();
        for (JsonNode row : data.path("stores")) values.add(row.path("storeId").asText(row.path("id").asText()));
        if (values.isEmpty()) values.addAll(strings(data.path("scope").path("storeIds")));
        return values;
    }

    private static String storeName(JsonNode data, String id) {
        for (JsonNode row : data.path("stores")) if (id.equals(row.path("storeId").asText(row.path("id").asText()))) return row.path("name").asText(id);
        return id;
    }

    private static ObjectNode quality(JsonNode data, boolean complete, String warning) {
        return object("status", complete ? "COMPLETE" : "PARTIAL",
                "businessWatermark", data.path("businessWatermark").asText(data.path("quality").path("businessWatermark").asText()),
                "publishedAt", data.path("publishedAt").asText(data.path("quality").path("publishedAt").asText()),
                "warnings", complete ? List.of() : List.of(warning));
    }

    private ArrayNode columns(List<String> metrics, List<String> dimensions, boolean comparison) {
        ArrayNode columns = array();
        dimensions.forEach(dimension -> columns.add(object("field", dimension, "label", switch (dimension) {
            case "store" -> "店铺"; case "day" -> "业务日期"; default -> "商品";
        }, "unit", "DIMENSION")));
        for (String metric : metrics) {
            String name = metric;
            for (JsonNode definition : registry) if (metric.equals(definition.path("metricId").asText())) name = definition.path("displayName").asText();
            columns.add(object("field", metric, "label", name, "unit", unit(metric)));
            if (comparison) {
                columns.add(object("field", metric + "_baseline", "label", name + " · 对比期", "unit", unit(metric)));
                columns.add(object("field", metric + "_delta", "label", name + " · 变化", "unit", unit(metric).equals("RATIO") ? "PP" : unit(metric)));
                columns.add(object("field", metric + "_change_ratio", "label", name + " · 相对变化", "unit", "RATIO"));
            }
        }
        return columns;
    }

    private static boolean hasMissing(JsonNode row) {
        for (JsonNode cell : row.path("cells")) if ("MISSING_DATA".equals(cell.path("reason").asText())) return true;
        return false;
    }

    private static boolean overOne(JsonNode row) {
        for (JsonNode value : row.path("cells")) {
            String field = value.path("field").asText();
            if ((field.equals("order_conversion_rate") || field.equals("order_conversion_rate_baseline"))
                    && !value.path("value").isNull() && new BigDecimal(value.path("value").asText()).compareTo(BigDecimal.ONE) > 0) return true;
        }
        return false;
    }

    private static MapSqlParameterSource parameters(JsonNode scope, String dataset, LocalDate start, LocalDate end) {
        return new MapSqlParameterSource().addValue("tenant", scope.path("tenantId").asText())
                .addValue("dataset", dataset).addValue("stores", strings(scope.path("storeIds")))
                // DATETIME is deliberately timezone-free UTC. JDBC LocalDateTime avoids converting
                // Timestamp through the JVM timezone before it reaches a timezone-free column.
                .addValue("start_utc", start.atStartOfDay(BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .addValue("end_utc", end.atStartOfDay(BUSINESS_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime())
                .addValue("start_date", java.sql.Date.valueOf(start)).addValue("end_date", java.sql.Date.valueOf(end));
    }

    private static Set<String> strings(JsonNode values) { Set<String> result = new TreeSet<>(); values.forEach(value -> result.add(value.asText())); return result; }
    private static ObjectNode range(LocalDate start, LocalDate end) { return object("start", start.toString(), "endExclusive", end.toString()); }
    private static String atomKey(LocalDate day, String store, String product) { return day + ":" + store + ":" + product; }
    private static String text(Map<String, Object> row, String key) { return String.valueOf(row.get(key)); }
    private static BigDecimal decimal(Map<String, Object> row, String key) { return new BigDecimal(row.get(key).toString()); }
    private static BigDecimal divide(BigDecimal numerator, BigDecimal denominator) { return numerator.divide(denominator, 12, RoundingMode.HALF_UP); }
    private static String unit(String metric) { return switch (metric) { case "paid_orders", "visitor_sessions" -> "COUNT"; case "refund_intensity", "order_conversion_rate" -> "RATIO"; default -> "CNY_CENT"; }; }
    private static ObjectNode cell(String field, BigDecimal value, String unit, String reason) {
        ObjectNode cell = object("field", field, "value", value == null ? null : value.stripTrailingZeros().toPlainString(), "unit", unit);
        if (reason != null) cell.put("reason", reason);
        return cell;
    }
    private static CommerceException budget() { return new CommerceException(429, "QUERY_BUDGET_EXCEEDED", "查询聚合规模超过预算，请缩小日期或店铺范围"); }

    private record Period(Map<String, Bucket> groups, Bucket total, boolean complete) { }
    private record Value(BigDecimal number, String reason) { }
    private static class Bucket {
        final ObjectNode dimensions;
        BigDecimal gmv = BigDecimal.ZERO, orders = BigDecimal.ZERO, refunds = BigDecimal.ZERO, sessions = BigDecimal.ZERO;
        boolean paymentComplete = true, refundComplete = true, trafficComplete = true;
        Bucket(ObjectNode dimensions) { this.dimensions = dimensions.deepCopy(); }
        static Bucket zero(ObjectNode dimensions, boolean complete) {
            Bucket bucket = new Bucket(dimensions);
            bucket.paymentComplete = bucket.refundComplete = bucket.trafficComplete = complete;
            return bucket;
        }
        void add(Bucket other) {
            gmv = gmv.add(other.gmv); orders = orders.add(other.orders); refunds = refunds.add(other.refunds); sessions = sessions.add(other.sessions);
            paymentComplete &= other.paymentComplete; refundComplete &= other.refundComplete; trafficComplete &= other.trafficComplete;
        }
        Value value(String metric) {
            boolean complete = switch (metric) {
                case "paid_gmv", "paid_orders", "aov" -> paymentComplete;
                case "refund_amount" -> refundComplete;
                case "net_receipts", "refund_intensity" -> paymentComplete && refundComplete;
                case "visitor_sessions" -> trafficComplete;
                default -> paymentComplete && trafficComplete;
            };
            if (!complete) return new Value(null, "MISSING_DATA");
            BigDecimal denominator = switch (metric) { case "aov" -> orders; case "refund_intensity" -> gmv; case "order_conversion_rate" -> sessions; default -> null; };
            if (denominator != null && denominator.signum() == 0) return new Value(null, "ZERO_DENOMINATOR");
            return new Value(switch (metric) {
                case "paid_gmv" -> gmv; case "paid_orders" -> orders; case "aov" -> divide(gmv, orders);
                case "refund_amount" -> refunds; case "net_receipts" -> gmv.subtract(refunds);
                case "refund_intensity" -> divide(refunds, gmv); case "visitor_sessions" -> sessions;
                default -> divide(orders, sessions);
            }, null);
        }
    }
}
