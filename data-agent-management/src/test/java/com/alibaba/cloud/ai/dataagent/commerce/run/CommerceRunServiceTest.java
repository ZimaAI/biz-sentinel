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
package com.alibaba.cloud.ai.dataagent.commerce.run;

import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.query.CommerceQueryService;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.alibaba.cloud.ai.dataagent.service.llm.LlmService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real H2 document CAS + real graph/planner/report validator; domain query boundary uses controlled evidence. */
class CommerceRunServiceTest {
    private CommerceStore store;
    private CommercePolicy policy;
    private CommerceQueryService queries;
    private CommercePlanner planner;
    private CommerceReportBuilder builder;
    private CommerceRunService runs;
    private CommerceSubject subject;
    private ObjectNode scope;
    private String decliningShop;
    private boolean inventoryComplete;
    private final String datasetId = "snapshot-test";

    @BeforeEach
    void setup() {
        store = new CommerceStore(new DriverManagerDataSource("jdbc:h2:mem:run_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        policy = new CommercePolicy(store);
        subject = new CommerceSubject("tenant-test", "operator", Set.of("shop-blue", "shop-red"), Set.of("OPS_MANAGER"), 1);
        store.create("member", "tenant-test:operator", "tenant-test", object("storeIds", subject.storeIds(), "roles", subject.roles(), "authzVersion", 1, "enabled", true));
        scope = object("tenantId", subject.tenantId(), "storeIds", List.of("shop-blue", "shop-red"), "authzVersion", 1);
        ObjectNode dataset = object("datasetVersionId", datasetId, "status", "PUBLISHED", "tenantId", subject.tenantId(), "scope", scope,
                "quality", object("status", "COMPLETE"), "synthetic", true);
        store.create("dataset", datasetId, subject.tenantId(), dataset);
        queries = mock(CommerceQueryService.class);
        when(queries.scope(any(), anyList())).thenReturn(scope);
        when(queries.chooseDataset(any(), anyList(), any())).thenReturn(dataset);
        when(queries.metricManifestHash()).thenReturn("metric-test-hash");
        when(queries.evidence(any(), anyString())).thenAnswer(inv -> {
            ObjectNode evidence = store.get("evidence", inv.getArgument(1)); policy.assertReadable(inv.getArgument(0), evidence); return evidence;
        });
        when(queries.queryBound(any(), any(), anyString(), any())).thenAnswer(inv -> queryResult(inv.getArgument(3)));
        when(queries.inventory(any(), any(), anyString(), anyList(), any())).thenAnswer(inv -> inventoryResult(inv.getArgument(3)));
        @SuppressWarnings("unchecked") ObjectProvider<LlmService> provider = mock(ObjectProvider.class);
        planner = new CommercePlanner(provider, "deterministic");
        builder = new CommerceReportBuilder(store, queries, policy);
        runs = new CommerceRunService(store, policy, queries, planner, builder, false);
        decliningShop = "shop-blue"; inventoryComplete = true;
    }

    @Test
    void approvalVersionIsBoundAndGraphProducesValidatedReportAndReplayableEvents() {
        ObjectNode run = create(true, "approval-test");
        String id = run.path("runId").asText();
        runs.process(id);
        ObjectNode waiting = runs.get(subject, id);
        assertThat(waiting.path("status").asText()).isEqualTo("WAITING_APPROVAL");
        ObjectNode approval = approval(waiting);
        ObjectNode stale = approval.deepCopy(); stale.put("planHash", "wrong-plan");
        assertThatThrownBy(() -> runs.approve(subject, id, stale)).isInstanceOf(CommerceException.class);
        runs.approve(subject, id, approval);
        assertThatThrownBy(() -> runs.approve(subject, id, approval)).isInstanceOf(CommerceException.class);
        runs.process(id);
        ObjectNode done = runs.get(subject, id);
        assertThat(done.path("status").asText()).withFailMessage(done.toPrettyString()).isEqualTo("SUCCEEDED");
        ObjectNode report = runs.report(subject, done.path("reportId").asText());
        assertThat(report.path("claims").toString()).contains("数学恒等分解", "480", "尚不能证明");
        assertThat(report.path("limitations").toString()).contains("未调用大语言模型");
        List<ObjectNode> events = runs.events(subject, id, 0);
        for (int i = 0; i < events.size(); i++) assertThat(events.get(i).path("sequence").asInt()).isEqualTo(i + 1);
        long halfway = events.size() / 2;
        assertThat(runs.events(subject, id, halfway)).hasSize(events.size() - (int) halfway);
        assertThat(events.get(events.size() - 1).path("type").asText()).isEqualTo("run.completed");
        assertThat(runs.export(subject, report.path("reportId").asText())).contains("# 经营变化诊断", "分析边界");
        assertThat(store.find("audit", subject.tenantId())).hasSize(2);
    }

    @Test
    void idempotencyReturnsSameRunButRejectsDifferentBodyAndActiveLimitIsEnforced() {
        ObjectNode first = create(true, "same");
        assertThat(create(true, "same").path("runId")).isEqualTo(first.path("runId"));
        ObjectNode request = request(true); request.put("question", "比较订单变化");
        assertThatThrownBy(() -> runs.create(subject, request, "same")).isInstanceOfSatisfying(CommerceException.class, ex -> assertThat(ex.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
        create(true, "second");
        assertThatThrownBy(() -> create(true, "third")).isInstanceOfSatisfying(CommerceException.class, ex -> assertThat(ex.status()).isEqualTo(429));
    }

    @Test
    void changedEvidenceChangesInvestigatedShopAndProductWithoutFixtureIdentifiers() {
        decliningShop = "shop-red";
        ObjectNode run = create(false, "perturbed"); String id = run.path("runId").asText();
        runs.process(id);
        ObjectNode done = runs.get(subject, id);
        assertThat(done.path("status").asText()).withFailMessage(done.toPrettyString()).isEqualTo("SUCCEEDED");
        JsonNode decomposition = done.path("steps").findValues("action").stream().filter(a -> a.path("toolName").asText().equals("decompose_gmv")).findFirst().orElseThrow();
        assertThat(decomposition.path("arguments").path("rowKey").asText()).isEqualTo("shop-red");
        verify(queries).inventory(eq(subject), eq(scope), eq(datasetId), eq(List.of("product-red")), any());
    }

    @Test
    void missingInventoryYieldsPartialWithoutInventedStockoutNumbers() {
        inventoryComplete = false;
        ObjectNode run = create(false, "missing-inventory"); String id = run.path("runId").asText();
        runs.process(id);
        ObjectNode done = runs.get(subject, id);
        assertThat(done.path("status").asText()).withFailMessage(done.toPrettyString()).isEqualTo("PARTIAL");
        String report = runs.report(subject, done.path("reportId").asText()).toString();
        assertThat(report).contains("库存数据不足").doesNotContain("480 分钟");
    }

    @Test
    void cancelledRunCannotBeResurrectedAndChangedAuthorizationRevokesWaitingRun() {
        ObjectNode run = create(true, "cancel"); String id = run.path("runId").asText();
        runs.cancel(subject, id, run.path("runVersion").asLong()); runs.process(id);
        assertThat(runs.get(subject, id).path("status").asText()).isEqualTo("CANCELLED");
        runs.process(id); assertThat(store.all("evidence")).isEmpty();
        ObjectNode second = create(true, "revoke"); String secondId = second.path("runId").asText(); runs.process(secondId);
        ObjectNode member = store.get("member", "tenant-test:operator");
        store.update("member", "tenant-test:operator", member.path("_revision").asLong(), m -> { m.put("authzVersion", 2); m.set("storeIds", MAPPER.valueToTree(List.of("shop-red"))); return m; });
        runs.process(secondId);
        assertThat(store.get("run", secondId).path("status").asText()).isEqualTo("ACCESS_REVOKED");
        List<ObjectNode> safe = runs.events(subject, secondId, 0);
        assertThat(safe).hasSize(1);
        assertThat(safe.get(0).path("type").asText()).isEqualTo("access.revoked");
        assertThatThrownBy(() -> runs.get(subject, secondId)).isInstanceOf(CommerceException.class);
    }

    @Test
    void independentWorkerTakesOverExpiredLeaseAndUsesCommittedStepWithoutDuplicatingEvidence() {
        ObjectNode run = create(false, "recovery"); String id = run.path("runId").asText();
        // Simulate a worker crash after persisting its lease. Another instance must advance its fencing token.
        ObjectNode saved = store.get("run", id);
        store.update("run", id, saved.path("_revision").asLong(), r -> {
            r.put("leaseOwner", "dead-worker"); r.put("fencingToken", 7); r.put("leaseUntil", Instant.now().minusSeconds(10).toString()); return r;
        });
        CommerceRunService restarted = new CommerceRunService(store, policy, queries, planner, builder, false);
        restarted.process(id);
        assertThat(restarted.get(subject, id).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(store.get("run", id).path("fencingToken").asInt()).isEqualTo(8);
        int count = store.all("evidence").size(); restarted.process(id);
        assertThat(store.all("evidence")).hasSize(count);
    }

    @Test
    void reportValidatorRejectsInventedNumbersCrossSnapshotBindingsAndUnknownTransforms() {
        ObjectNode run = create(false, "validation"); String id = run.path("runId").asText(); runs.process(id);
        ObjectNode report = store.get("report", runs.get(subject, id).path("reportId").asText());
        ObjectNode invented = report.deepCopy(); ((ObjectNode) invented.path("claims").get(0)).put("text", "未验证损失9999999元");
        assertThatThrownBy(() -> builder.validate(subject, invented)).isInstanceOfSatisfying(CommerceException.class, ex -> assertThat(ex.code()).isEqualTo("UNBOUND_NUMBER"));
        ObjectNode differentDataset = report.deepCopy(); differentDataset.put("datasetVersionId", "another-snapshot");
        assertThatThrownBy(() -> builder.validate(subject, differentDataset)).isInstanceOfSatisfying(CommerceException.class, ex -> assertThat(ex.code()).isEqualTo("EVIDENCE_SCOPE_MISMATCH"));
        ObjectNode unknown = report.deepCopy(); ((ObjectNode) unknown.path("claims").get(0).path("numericBindings").get(0)).put("transformId", "EVAL_SCRIPT");
        assertThatThrownBy(() -> builder.validate(subject, unknown)).isInstanceOfSatisfying(CommerceException.class, ex -> assertThat(ex.code()).isEqualTo("UNKNOWN_TRANSFORM"));
    }

    @Test
    void unsupportedProfitQuestionEndsAsClarificationWithoutDomainQueries() {
        ObjectNode request = request(false); request.put("question", "为什么利润下降？");
        ObjectNode run = runs.create(subject, request, "profit"); String id = run.path("runId").asText(); runs.process(id);
        ObjectNode done = runs.get(subject, id);
        assertThat(done.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(done.path("clarification").path("message").asText()).contains("成本");
        verify(queries, never()).queryBound(any(), any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"查询 SKU 退款率", "按商品分析转化比", "自动退款", "SELECT * FROM payments", "预测明天GMV", "比较美元与人民币GMV", "今天天气怎么样"})
    void unsupportedIntentsClarifyInsteadOfSilentlyAnsweringGmv(String question) {
        ObjectNode request = request(false); request.put("question", question);
        ObjectNode run = runs.create(subject, request, "unsupported"); runs.process(run.path("runId").asText());
        ObjectNode done = runs.get(subject, run.path("runId").asText());
        assertThat(done.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(done.path("clarification").path("message").asText()).isNotBlank();
        verify(queries, never()).queryBound(any(), any(), anyString(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"退款金额为什么变化", "净收款为什么变化", "客单价为什么变化", "访客为什么减少", "支付订单为什么减少", "退款强度为什么增加", "订单转化比为什么下降"})
    void canonicalMetricIntentIsPreservedInTheQuery(String question) {
        ObjectNode request = request(false); request.put("question", question);
        ObjectNode run = runs.create(subject, request, "metric"); runs.process(run.path("runId").asText());
        String metric = CommerceRunService.resolveMetric(question);
        verify(queries).queryBound(eq(subject), eq(scope), eq(datasetId), argThat(spec -> spec.path("metricIds").size() == 1
                && spec.path("metricIds").get(0).asText().equals(metric)));
        verify(queries, never()).inventory(any(), any(), anyString(), anyList(), any());
    }

    @ParameterizedTest
    @CsvSource({
            "昨天退款金额强度多少？,refund_intensity", "退款 金额 强度变化,refund_intensity", "昨天成功退款金额是多少？,refund_amount",
            "哪个店铺贡献最大下降？,paid_gmv", "哪些门店对下降的贡献最大？,paid_gmv", "订单数和客单价分别贡献多少？,paid_gmv",
            "按客单价与支付订单数拆解变化,paid_gmv", "主店的核心 SKU 变化是多少？,paid_gmv", "商品支付金额变化,paid_gmv",
            "客单价是多少？,aov", "哪个店铺退款金额变化最大？,refund_amount"
    })
    void completeMetricNamesAndDocumentedContextsResolveBeforeGenericWords(String question, String metric) {
        assertThat(CommerceRunService.resolveMetric(question)).isEqualTo(metric);
        assertThat(CommerceRunService.clarify(question)).isNull();
    }

    @Test
    void refundAmountIntensityProducesRatioEvidenceInsteadOfRefundAmountEvidence() {
        ObjectNode request = request(false); request.put("question", "昨天退款金额强度多少？");
        ObjectNode created = runs.create(subject, request, "refund-intensity-alias"); runs.process(created.path("runId").asText());
        ObjectNode completed = runs.get(subject, created.path("runId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("metricId").asText()).isEqualTo("refund_intensity");
        ObjectNode report = runs.report(subject, completed.path("reportId").asText());
        assertThat(report.path("claims").toString()).contains("退款金额强度").doesNotContain("订单退款率为");
        verify(queries).queryBound(eq(subject), eq(scope), eq(datasetId), argThat(spec ->
                spec.path("metricIds").equals(MAPPER.valueToTree(List.of("refund_intensity")))));
    }

    @Test
    void storeContributionIncludesSignedVerifiableNetAndNegativeSourceShares() {
        ObjectNode request = request(false); request.put("question", "哪个店铺贡献最大下降？");
        ObjectNode created = runs.create(subject, request, "store-contribution"); runs.process(created.path("runId").asText());
        ObjectNode completed = runs.get(subject, created.path("runId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("metricResolutionNote").asText()).contains("支付 GMV", "不是原因概率");
        ObjectNode report = runs.report(subject, completed.path("reportId").asText());
        // The improving store offsets part of the decline: the worst store can contribute more than one hundred percent.
        assertThat(report.path("claims").toString()).contains("占整体净下降 125.0000%", "下降来源占比为 100.0000%");
        ObjectNode derived = store.all("evidence").stream().filter(e -> "SEGMENT_CONTRIBUTION".equals(e.path("derivation").asText())).findFirst().orElseThrow();
        JsonNode improving = derived.path("result").path("rows").get(1);
        assertThat(CommercePlanner.decimal(CommercePlanner.cell(improving, "net_change_contribution"))).isEqualByComparingTo("-0.25");
        assertThat(CommercePlanner.decimal(CommercePlanner.cell(improving, "decline_source_share"))).isZero();
        store.update("evidence", derived.path("evidenceId").asText(), derived.path("_revision").asLong(), value -> {
            ((ObjectNode) CommercePlanner.cell(value.path("result").path("rows").get(0), "net_change_contribution")).put("value", "0.99"); return value;
        });
        assertThatThrownBy(() -> builder.validate(subject, report)).isInstanceOfSatisfying(CommerceException.class,
                ex -> assertThat(ex.code()).isEqualTo("INVALID_CONTRIBUTION"));
    }

    @Test
    void pairedFactorContributionQuestionDecomposesTheWholeSelectedScope() {
        ObjectNode request = request(false); request.put("question", "订单数和客单价分别贡献多少？");
        ObjectNode created = runs.create(subject, request, "paired-factor"); runs.process(created.path("runId").asText());
        ObjectNode completed = runs.get(subject, created.path("runId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("metricId").asText()).isEqualTo("paid_gmv");
        JsonNode decomposition = completed.path("steps").findValues("action").stream()
                .filter(action -> action.path("toolName").asText().equals("decompose_gmv")).findFirst().orElseThrow();
        assertThat(decomposition.path("arguments").path("rowKey").asText()).isEqualTo("total");
        ObjectNode derived = store.all("evidence").stream().filter(e -> "DECOMPOSITION".equals(e.path("kind").asText())).findFirst().orElseThrow();
        JsonNode total = derived.path("result").path("totals");
        assertThat(CommercePlanner.decimal(CommercePlanner.cell(total, "orders_contribution"))
                .add(CommercePlanner.decimal(CommercePlanner.cell(total, "aov_contribution")))).isEqualByComparingTo("-40000");
        assertThat(runs.report(subject, completed.path("reportId").asText()).path("claims").toString()).contains("所选店铺的整体范围");
        verify(queries, never()).inventory(any(), any(), anyString(), anyList(), any());
    }

    @Test
    void implicitSkuChangeExplainsItsMeasureAndUsesActualDecliningProducts() {
        decliningShop = "shop-red";
        ObjectNode request = request(false); request.put("question", "主店的核心 SKU 变化是多少？");
        ObjectNode created = runs.create(subject, request, "implicit-sku"); runs.process(created.path("runId").asText());
        ObjectNode completed = runs.get(subject, created.path("runId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(completed.path("metricResolutionNote").asText()).contains("支付 GMV", "不预设店铺或商品编号");
        verify(queries).inventory(eq(subject), eq(scope), eq(datasetId), eq(List.of("product-red")), any());
    }

    @ParameterizedTest
    @CsvSource({
            "为什么 SKU 退款率上升？,UNSUPPORTED_METRIC_DIMENSION,退款映射",
            "SKU支付订单数多少？,UNSUPPORTED_METRIC_DIMENSION,商品订单数",
            "昨天利润是多少？,UNSUPPORTED_METRIC,成本",
            "哪个广告计划拖累了 ROI？,UNSUPPORTED_DOMAIN,广告归因事实",
            "三店总共多少独立访客？,UNSUPPORTED_METRIC,不是跨店去重用户",
            "直接给缺货商品补货100件,READ_ONLY_BOUNDARY,只读",
            "销售额多少？,AMBIGUOUS_METRIC,下单金额"
    })
    void unsupportedAndAmbiguousRequestsKeepSpecificCodesWithoutQuerying(String question, String code, String explanation) {
        ObjectNode request = request(false); request.put("question", question);
        ObjectNode created = runs.create(subject, request, "specific-boundary"); runs.process(created.path("runId").asText());
        ObjectNode completed = runs.get(subject, created.path("runId").asText());
        assertThat(completed.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(completed.path("metricId").isNull()).isTrue();
        assertThat(completed.path("clarification").path("code").asText()).isEqualTo(code);
        assertThat(completed.path("clarification").path("message").asText()).contains(explanation);
        assertThat(completed.path("evidenceIds")).isEmpty();
        if (code.equals("AMBIGUOUS_METRIC")) {
            assertThat(completed.path("clarification").path("message").asText()).contains("支付金额", "净收款");
            assertThat(completed.path("clarification").path("choices")).hasSize(3);
        }
        verify(queries, never()).queryBound(any(), any(), anyString(), any());
    }

    private ObjectNode create(boolean approval, String key) { return runs.create(subject, request(approval), key); }
    private ObjectNode request(boolean approval) { return object("question", "支付 GMV 为什么下降？", "requestedStoreIds", List.of("shop-blue", "shop-red"),
            "dateRange", object("start", "2026-09-20", "endExclusive", "2026-09-21"), "comparison", "PREVIOUS_WEEK_SAME_DAYS", "requireApproval", approval); }
    private ObjectNode approval(JsonNode run) { return object("decision", "APPROVE", "planVersion", run.path("plan").path("planVersion"),
            "planHash", run.path("plan").path("planHash"), "expectedRunVersion", run.path("runVersion")); }

    private ObjectNode queryResult(JsonNode spec) {
        boolean sku = spec.path("dimensions").toString().contains("sku");
        ArrayNode rows = array();
        for (String shop : List.of("shop-blue", "shop-red")) {
            boolean decline = shop.equals(decliningShop); long current = decline ? 50000 : 110000; long baseline = 100000;
            ObjectNode dimensions = object("store", shop);
            if (sku) dimensions.put("sku", shop.equals("shop-blue") ? "product-blue" : "product-red");
            ArrayNode cells = array();
            for (JsonNode metric : spec.path("metricIds")) addQueryMetric(cells, metric.asText(), current, baseline, decline ? 5 : 11, 10, 1000, 1000, 500, 200);
            rows.add(object("rowKey", sku ? shop + ":sku" : shop, "dimensions", dimensions, "cells", cells));
        }
        ArrayNode totals = array();
        for (JsonNode metric : spec.path("metricIds")) addQueryMetric(totals, metric.asText(), 160000, 200000, 16, 20, 2000, 2000, 1000, 400);
        return persist(object("rows", rows, "totals", object("rowKey", "total", "dimensions", object(), "cells", totals), "columns", array(),
                "quality", object("status", "COMPLETE"), "truncated", false), "QUERY_RESULT");
    }

    private ObjectNode inventoryResult(List<String> products) {
        ArrayNode cells = array().add(object("field", "stockout_minutes", "value", inventoryComplete ? "480" : null, "unit", "COUNT"));
        ObjectNode row = object("rowKey", "inventory-row", "dimensions", object("store", decliningShop, "sku", products.get(0)), "cells", cells);
        return persist(object("rows", array().add(row), "totals", object("rowKey", "total", "cells", array()), "columns", array(),
                "quality", object("status", inventoryComplete ? "COMPLETE" : "PARTIAL"), "truncated", false), "INVENTORY_SIGNAL");
    }

    private ObjectNode persist(ObjectNode result, String kind) {
        String id = "evidence_" + UUID.randomUUID(); result.put("evidenceId", id); result.set("scope", scope);
        result.put("datasetVersionId", datasetId); result.set("dateRange", request(false).path("dateRange"));
        result.set("comparisonRange", object("start", "2026-09-13", "endExclusive", "2026-09-14"));
        store.create("evidence", id, subject.tenantId(), object("evidenceId", id, "subjectId", subject.subjectId(), "scope", scope,
                "datasetVersionId", datasetId, "metricManifestHash", "metric-test-hash", "kind", kind, "result", result)); return result;
    }

    private static void addMetric(ArrayNode cells, String field, long now, long before, String unit) {
        addMetric(cells, field, BigDecimal.valueOf(now), BigDecimal.valueOf(before), unit);
    }

    private static void addMetric(ArrayNode cells, String field, BigDecimal now, BigDecimal before, String unit) {
        BigDecimal delta = now.subtract(before);
        cells.add(object("field", field, "value", now.toPlainString(), "unit", unit));
        cells.add(object("field", field + "_baseline", "value", before.toPlainString(), "unit", unit));
        cells.add(object("field", field + "_delta", "value", (unit.equals("RATIO") ? delta.multiply(new BigDecimal("100")) : delta).toPlainString(), "unit", unit.equals("RATIO") ? "PP" : unit));
        cells.add(object("field", field + "_change_ratio", "value", before.signum() == 0 ? null : delta.divide(before, 12, java.math.RoundingMode.HALF_UP).toPlainString(), "unit", "RATIO"));
    }

    private static void addQueryMetric(ArrayNode cells, String metric, long gmv, long baselineGmv, long orders, long baselineOrders,
                                       long sessions, long baselineSessions, long refunds, long baselineRefunds) {
        switch (metric) {
            case "paid_gmv" -> addMetric(cells, metric, gmv, baselineGmv, "CNY_CENT");
            case "paid_orders" -> addMetric(cells, metric, orders, baselineOrders, "COUNT");
            case "visitor_sessions" -> addMetric(cells, metric, sessions, baselineSessions, "COUNT");
            case "refund_amount" -> addMetric(cells, metric, refunds, baselineRefunds, "CNY_CENT");
            case "net_receipts" -> addMetric(cells, metric, gmv - refunds, baselineGmv - baselineRefunds, "CNY_CENT");
            case "aov" -> addMetric(cells, metric, divide(gmv, orders), divide(baselineGmv, baselineOrders), "CNY_CENT");
            case "refund_intensity" -> addMetric(cells, metric, divide(refunds, gmv), divide(baselineRefunds, baselineGmv), "RATIO");
            case "order_conversion_rate" -> addMetric(cells, metric, divide(orders, sessions), divide(baselineOrders, baselineSessions), "RATIO");
            default -> throw new AssertionError("Unknown test metric: " + metric);
        }
    }

    private static BigDecimal divide(long numerator, long denominator) { return BigDecimal.valueOf(numerator).divide(BigDecimal.valueOf(denominator), 12, java.math.RoundingMode.HALF_UP); }
}
