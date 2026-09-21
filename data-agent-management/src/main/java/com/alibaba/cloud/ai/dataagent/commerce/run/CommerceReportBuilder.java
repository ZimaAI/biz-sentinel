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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import static com.alibaba.cloud.ai.dataagent.commerce.run.CommercePlanner.*;
import static com.alibaba.cloud.ai.dataagent.commerce.run.CommerceRunService.*;

/** Server-owned arithmetic and rendering; all numeric claims are independently checked before publication. */
@Component
@Profile("commerce")
public class CommerceReportBuilder {
    private static final MathContext PRECISION = MathContext.DECIMAL128;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(?:[,.]\\d+)*");
    private final CommerceStore store;
    private final CommerceQueryService queries;
    private final CommercePolicy policy;

    public CommerceReportBuilder(CommerceStore store, CommerceQueryService queries, CommercePolicy policy) {
        this.store = store; this.queries = queries; this.policy = policy;
    }

    /** Register exact signed net contribution and negative-source share as independently verifiable evidence. */
    public ObjectNode compareSegments(CommerceSubject subject, ObjectNode run, ObjectNode aggregate) {
        if (aggregate.path("truncated").asBoolean() || incomplete(aggregate) || !available(aggregate.path("totals"), "paid_gmv_delta")) return aggregate;
        for (JsonNode row : aggregate.path("rows")) if (!row.path("dimensions").has("store")
                || row.path("dimensions").has("sku") || row.path("dimensions").has("day") || !available(row, "paid_gmv_delta")) return aggregate;
        ObjectNode source = queries.evidence(subject, aggregate.path("evidenceId").asText());
        assertBinding(run, source);
        ObjectNode result = withContributions(aggregate);
        String id = "ev_" + UUID.randomUUID();
        result.put("evidenceId", id); result.put("queryArtifactId", "derived_" + id);
        ObjectNode evidence = object("evidenceId", id, "runId", run.path("runId"), "kind", "QUERY_RESULT", "derivation", "SEGMENT_CONTRIBUTION",
                "datasetVersionId", run.path("datasetVersionId"), "metricManifestHash", run.path("metricManifestHash"),
                "scope", run.path("scope"), "subjectId", subject.subjectId(), "result", result, "resultHash", sha256(result.toString()),
                "sourceEvidenceId", aggregate.path("evidenceId"), "createdAt", Instant.now().toString(), "sqlTemplate", source.path("sqlTemplate"),
                "redactedParameters", source.path("redactedParameters"), "durationMs", 0,
                "calculation", "net_change_contribution=delta_i/total_delta; decline_source_share=max(-delta_i,0)/sum(max(-delta_j,0))");
        store.create("evidence", id, subject.tenantId(), evidence);
        return result;
    }

    private static ObjectNode withContributions(ObjectNode aggregate) {
        BigDecimal totalDelta = decimal(cell(aggregate.path("totals"), "paid_gmv_delta"));
        BigDecimal deltaSum = BigDecimal.ZERO, negativeSum = BigDecimal.ZERO;
        for (JsonNode row : aggregate.path("rows")) {
            BigDecimal delta = decimal(cell(row, "paid_gmv_delta"));
            deltaSum = deltaSum.add(delta);
            if (delta.signum() < 0) negativeSum = negativeSum.add(delta.abs());
        }
        if (deltaSum.subtract(totalDelta).abs().compareTo(BigDecimal.ONE) > 0)
            throw error(422, "CONTRIBUTION_RESIDUAL", "完整分组的变化之和与总体不一致，不能计算贡献比例。");
        ObjectNode result = aggregate.deepCopy();
        for (JsonNode row : result.path("rows")) {
            BigDecimal delta = decimal(cell(row, "paid_gmv_delta"));
            BigDecimal netShare = totalDelta.signum() == 0 ? null : delta.divide(totalDelta, PRECISION);
            BigDecimal declineShare = negativeSum.signum() == 0 ? null : delta.negate().max(BigDecimal.ZERO).divide(negativeSum, PRECISION);
            ((ArrayNode) row.path("cells")).add(ratioCell("net_change_contribution", netShare));
            ((ArrayNode) row.path("cells")).add(ratioCell("decline_source_share", declineShare));
        }
        ArrayNode columns = result.path("columns").isArray() ? (ArrayNode) result.path("columns") : result.putArray("columns");
        columns.add(object("field", "net_change_contribution", "label", "净变化贡献", "unit", "RATIO"));
        columns.add(object("field", "decline_source_share", "label", "下降来源占比", "unit", "RATIO"));
        return result;
    }

    private static ObjectNode ratioCell(String field, BigDecimal value) {
        return object("field", field, "value", value == null ? null : value.stripTrailingZeros().toPlainString(),
                "unit", "RATIO", "nullReason", value == null ? "NO_COMPARABLE_BASELINE" : null);
    }

    public ObjectNode decompose(CommerceSubject subject, ObjectNode run, String evidenceId, String rowKey) {
        ObjectNode input = queries.evidence(subject, evidenceId);
        assertBinding(run, input);
        JsonNode source = input.path("result");
        if (source.path("truncated").asBoolean() || incomplete(source)) throw error(422, "INCOMPLETE_DECOMPOSITION", "分解要求完整的聚合证据。");
        JsonNode row = findRow(source, rowKey);
        BigDecimal[] pieces = decomposition(row);
        ObjectNode result = object("scope", run.path("scope"), "dateRange", run.path("dateRange"), "comparisonRange", run.path("comparisonRange"),
                "datasetVersionId", run.path("datasetVersionId"), "metricVersions", source.path("metricVersions"),
                "quality", object("status", "COMPLETE"), "truncated", false, "rowCount", 1);
        ArrayNode cells = array();
        cells.add(numberCell("orders_contribution", pieces[0], "CNY_CENT"));
        cells.add(numberCell("aov_contribution", pieces[1], "CNY_CENT"));
        cells.add(numberCell("gmv_delta", pieces[2], "CNY_CENT"));
        cells.add(numberCell("residual", pieces[0].add(pieces[1]).subtract(pieces[2]), "CNY_CENT"));
        JsonNode output = object("rowKey", rowKey, "dimensions", row.path("dimensions"), "cells", cells);
        result.set("rows", array().add(output)); result.set("totals", output);
        result.set("columns", array().add(object("field", "orders_contribution", "label", "订单贡献", "unit", "CNY_CENT"))
                .add(object("field", "aov_contribution", "label", "客单价贡献", "unit", "CNY_CENT")));
        String id = "ev_" + UUID.randomUUID();
        result.put("evidenceId", id); result.put("queryArtifactId", "derived_" + id);
        ObjectNode evidence = object("evidenceId", id, "runId", run.path("runId"), "kind", "DECOMPOSITION",
                "datasetVersionId", run.path("datasetVersionId"), "metricManifestHash", run.path("metricManifestHash"),
                "scope", run.path("scope"), "subjectId", subject.subjectId(), "result", result, "resultHash", sha256(result.toString()),
                "sourceEvidenceId", evidenceId, "sourceRowKey", rowKey, "createdAt", Instant.now().toString(),
                "sqlTemplate", "", "redactedParameters", object("sourceEvidenceId", evidenceId, "sourceRowKey", rowKey), "durationMs", 0,
                "calculation", "orders=(N1-N0)*(A0+A1)/2; aov=(A1-A0)*(N0+N1)/2; A=G/N");
        store.create("evidence", id, subject.tenantId(), evidence);
        return result;
    }

    public ObjectNode build(CommerceSubject subject, ObjectNode run, List<ObjectNode> evidence, String extraLimitation) {
        ArrayNode claims = array(); ArrayNode limitations = array();
        limitations.add("未校正促销与节假日，变化贡献和相关信号不构成因果证明。");
        if (run.path("synthetic").asBoolean()) limitations.add("本报告使用合成演示数据，不能代表真实商家的营收。");
        if ("DETERMINISTIC_LOCAL".equals(run.path("plannerMode").asText())) limitations.add("本次使用本地确定性诊断规划器，未调用大语言模型。");
        if (!run.path("metricResolutionNote").asText("").isBlank()) limitations.add(run.path("metricResolutionNote").asText());
        if (extraLimitation != null) limitations.add(extraLimitation);
        boolean partial = extraLimitation != null || evidence.isEmpty();
        ObjectNode segments = findSegments(evidence);
        if (segments != null) {
            if (incomplete(segments)) { limitations.add("部分来源或日期不完整，相关缺失指标未按零解释。"); partial = true; }
            JsonNode total = segments.path("totals");
            for (String metric : List.of("paid_gmv", "paid_orders", "aov", "visitor_sessions", "order_conversion_rate", "refund_amount", "net_receipts", "refund_intensity")) {
                if (!available(total, metric) || !available(total, metric + "_baseline")) continue;
                String label = metricLabel(metric);
                List<ObjectNode> bindings = new ArrayList<>();
                bindings.add(binding(segments, total, metric + "_baseline", "IDENTITY"));
                bindings.add(binding(segments, total, metric, "IDENTITY"));
                String text = label + "由 " + formatted(cell(total, metric + "_baseline")) + " 变为 " + formatted(cell(total, metric)) + "。";
                if (available(total, metric + "_delta")) {
                    text += "变化 " + formatted(cell(total, metric + "_delta")) + "。";
                    bindings.add(binding(segments, total, metric, Set.of("order_conversion_rate", "refund_intensity").contains(metric) ? "PP_DELTA" : "DELTA"));
                }
                if (metric.equals("paid_gmv") && available(total, metric + "_change_ratio")) {
                    text += "相对变化 " + formatted(cell(total, metric + "_change_ratio")) + "。";
                    bindings.add(binding(segments, total, metric, "RELATIVE_CHANGE"));
                }
                claims.add(claim(claims, "OBSERVATION", text, List.of(segments.path("evidenceId").asText()), bindings, "SUPPORTED"));
            }
            String requestedMetric = run.path("metricId").asText("paid_gmv");
            if (!"paid_gmv".equals(requestedMetric) && !incomplete(segments)) {
                JsonNode strongest = null;
                for (JsonNode row : segments.path("rows")) if (available(row, requestedMetric + "_delta") && (strongest == null
                        || decimal(cell(row, requestedMetric + "_delta")).abs().compareTo(decimal(cell(strongest, requestedMetric + "_delta")).abs()) > 0)) strongest = row;
                if (strongest != null) claims.add(claim(claims, "OBSERVATION", "变化幅度最大的店铺，" + metricLabel(requestedMetric) + "变化 "
                        + formatted(cell(strongest, requestedMetric + "_delta")) + "；店铺详见引用证据。", List.of(segments.path("evidenceId").asText()),
                        List.of(binding(segments, strongest, requestedMetric, Set.of("refund_intensity", "order_conversion_rate").contains(requestedMetric) ? "PP_DELTA" : "DELTA")), "SUPPORTED"));
            }
            if (segments.path("truncated").asBoolean()) { limitations.add("店铺结果被截断，无法据此解释全部店铺的变化贡献。"); partial = true; }
            else if (!incomplete(segments) && available(total, "paid_gmv_delta")) {
                BigDecimal totalChange = decimal(cell(total, "paid_gmv_delta"));
                BigDecimal sum = BigDecimal.ZERO;
                for (JsonNode row : segments.path("rows")) sum = sum.add(decimal(cell(row, "paid_gmv_delta")));
                if (sum.subtract(totalChange).abs().compareTo(BigDecimal.ONE) > 0)
                    throw error(422, "CONTRIBUTION_RESIDUAL", "店铺变化贡献与总量不一致，不能发布报告。");
                JsonNode focus = mostNegative(segments, "paid_gmv_delta");
                if (focus != null) {
                    List<ObjectNode> bindings = new ArrayList<>(); bindings.add(binding(segments, focus, "paid_gmv", "DELTA"));
                    String text = "下降最集中的店铺支付 GMV 变化 " + formatted(cell(focus, "paid_gmv_delta")) + "，具体店铺见引用证据。";
                    if (available(focus, "net_change_contribution")) {
                        text += (totalChange.signum() < 0 ? "占整体净下降 " : "占整体净变化 ") + formatted(cell(focus, "net_change_contribution")) + "。";
                        bindings.add(binding(segments, focus, "net_change_contribution", "IDENTITY"));
                    }
                    if (available(focus, "decline_source_share")) {
                        text += "在全部负向变化中的下降来源占比为 " + formatted(cell(focus, "decline_source_share")) + "。";
                        bindings.add(binding(segments, focus, "decline_source_share", "IDENTITY"));
                    }
                    text += "贡献比例是数学分配，不是原因概率。";
                    claims.add(claim(claims, "OBSERVATION", text, List.of(segments.path("evidenceId").asText()), bindings, "SUPPORTED"));
                }
                boolean improving = false;
                for (JsonNode row : segments.path("rows")) if (available(row, "paid_gmv_delta") && decimal(cell(row, "paid_gmv_delta")).signum() > 0) improving = true;
                if (improving && totalChange.signum() < 0) claims.add(claim(claims, "OBSERVATION", "存在支付 GMV 上升的店铺；整体下降并非所有店铺同向恶化。", List.of(segments.path("evidenceId").asText()), List.of(), "SUPPORTED"));
                if (totalChange.signum() >= 0) claims.add(claim(claims, "OBSERVATION", "当前数据不支持整体支付 GMV 下降这一前提。", List.of(segments.path("evidenceId").asText()), List.of(), "SUPPORTED"));
            }
        }
        for (ObjectNode item : evidence) {
            if ("DECOMPOSITION".equals(item.path("kind").asText())) {
                JsonNode row = item.path("rows").get(0);
                String scopeLabel = row.path("dimensions").has("store") ? "变化最集中的店铺" : "所选店铺的整体范围";
                claims.add(claim(claims, "DECOMPOSITION", scopeLabel + "，订单数项贡献 " + formatted(cell(row, "orders_contribution"))
                        + "，客单价项贡献 " + formatted(cell(row, "aov_contribution")) + "。这是数学恒等分解，不是运营因果判断。",
                        List.of(item.path("evidenceId").asText()), List.of(binding(item, row, "orders_contribution", "IDENTITY"), binding(item, row, "aov_contribution", "IDENTITY")), "SUPPORTED"));
            }
        }
        ObjectNode sku = findSku(evidence);
        if (sku != null && !incomplete(sku) && !sku.path("truncated").asBoolean()) {
            JsonNode row = mostNegative(sku, "paid_gmv_delta");
            if (row == null) for (JsonNode candidate : sku.path("rows")) if (available(candidate, "paid_gmv_delta")
                    && (row == null || decimal(cell(candidate, "paid_gmv_delta")).abs().compareTo(decimal(cell(row, "paid_gmv_delta")).abs()) > 0)) row = candidate;
            if (row != null) claims.add(claim(claims, "OBSERVATION", (decimal(cell(row, "paid_gmv_delta")).signum() < 0 ? "下降最集中的商品" : "变化幅度最大的商品") + "支付 GMV 变化 " + formatted(cell(row, "paid_gmv_delta"))
                    + "，商品与店铺详见引用证据。", List.of(sku.path("evidenceId").asText()), List.of(binding(sku, row, "paid_gmv", "DELTA")), "SUPPORTED"));
        }
        boolean inventoryFound = false;
        for (ObjectNode item : evidence) if ("INVENTORY_SIGNAL".equals(item.path("kind").asText())) {
            inventoryFound = true;
            if (incomplete(item) || item.path("rows").isEmpty()) {
                partial = true; limitations.add("库存记录不完整，不能判断缺货时长，也不能将缺失当作无缺货。");
                claims.add(claim(claims, "LIMITATION", "库存数据不足，停止库存归因判断。", List.of(item.path("evidenceId").asText()), List.of(), "INSUFFICIENT"));
                continue;
            }
            JsonNode strongest = null;
            for (JsonNode row : item.path("rows")) if (available(row, "stockout_minutes")
                    && (strongest == null || decimal(cell(row, "stockout_minutes")).compareTo(decimal(cell(strongest, "stockout_minutes"))) > 0)) strongest = row;
            if (strongest != null && decimal(cell(strongest, "stockout_minutes")).signum() > 0) {
                claims.add(claim(claims, "HYPOTHESIS", "被调查商品的单日缺货记录最高为 " + numeric(decimal(cell(strongest, "stockout_minutes")), 0)
                        + " 分钟。该信号与支付变化相关，尚不能证明缺货导致金额损失。",
                        List.of(item.path("evidenceId").asText()), List.of(binding(item, strongest, "stockout_minutes", "IDENTITY")), "SUPPORTED"));
                limitations.add("缺货时长依据数据包 stockout_minutes 测量字段；日末库存为零不能替代时长测量。");
            }
            else claims.add(claim(claims, "OBSERVATION", "被调查商品的完整库存记录未显示缺货时长上升证据，当前不支持缺货假设。", List.of(item.path("evidenceId").asText()), List.of(), "CONTRADICTED"));
        }
        if (!inventoryFound) limitations.add("尚未形成可支持库存结论的证据。");
        claims.add(claim(claims, "RECOMMENDATION", "建议结合库存分时日志、营销活动与访问链路核实相关线索，再决定运营动作。",
                evidence.stream().map(e -> e.path("evidenceId").asText()).toList(), List.of(), "SUPPORTED"));
        for (JsonNode limitation : limitations) claims.add(claim(claims, "LIMITATION", limitation.asText(), List.of(), List.of(), "SUPPORTED"));
        String title = run.path("synthetic").asBoolean() ? "经营变化诊断（合成数据）" : "经营变化诊断";
        return object("reportId", "report_" + run.path("runId").asText(), "version", 1, "runId", run.path("runId"),
                "tenantId", subject.tenantId(), "subjectId", subject.subjectId(), "visibility", "PRIVATE", "title", title, "status", partial ? "PARTIAL" : "SUCCEEDED",
                "createdAt", Instant.now().toString(), "scope", run.path("scope"), "dateRange", run.path("dateRange"), "comparisonRange", run.path("comparisonRange"),
                "datasetVersionId", run.path("datasetVersionId"), "metricManifestHash", run.path("metricManifestHash"), "claims", claims,
                "limitations", limitations, "synthetic", run.path("synthetic").asBoolean());
    }

    /** Checks owner/scope/snapshot, known transforms, derived arithmetic and every literal numeric claim. */
    public void validate(CommerceSubject subject, JsonNode report) {
        policy.assertReadable(subject, report);
        if (!report.path("claims").isArray() || report.path("claims").isEmpty()) throw error(422, "REPORT_INVALID", "报告必须包含可审查的结论。");
        Set<String> claimIds = new HashSet<>();
        Map<String, ObjectNode> evidence = new HashMap<>();
        for (JsonNode claim : report.path("claims")) {
            if (!claimIds.add(claim.path("claimId").asText()) || claim.path("text").asText().length() > 2000)
                throw error(422, "REPORT_INVALID", "报告结论标识或长度不合法。");
            Set<String> refs = new HashSet<>(strings(claim.path("evidenceRefs")));
            for (String ref : refs) {
                ObjectNode source = evidence.computeIfAbsent(ref, id -> queries.evidence(subject, id));
                assertBinding(report, source);
                if ("SEGMENT_CONTRIBUTION".equals(source.path("derivation").asText())) verifyContributions(subject, report, source);
                if ("DECOMPOSITION".equals(source.path("kind").asText())) verifyDecomposition(subject, report, source);
            }
            Set<BigDecimal> allowedNumbers = new TreeSet<>();
            for (JsonNode binding : claim.path("numericBindings")) {
                String id = binding.path("evidenceId").asText();
                if (!refs.contains(id)) throw error(422, "UNBOUND_CLAIM", "数值引用必须属于结论证据。");
                JsonNode source = evidence.get(id).path("result");
                JsonNode row = findRow(source, binding.path("rowKey").asText());
                String field = binding.path("field").asText();
                String transform = binding.path("transformId").asText();
                BigDecimal value;
                String unit;
                if (Set.of("SHAPLEY_ORDERS", "SHAPLEY_AOV").contains(transform)) {
                    value = decomposition(row)[transform.equals("SHAPLEY_ORDERS") ? 0 : 1]; unit = "CNY_CENT";
                }
                else {
                    String resolved = switch (transform) {
                        case "IDENTITY" -> field;
                        case "DELTA", "PP_DELTA" -> field + "_delta";
                        case "RELATIVE_CHANGE" -> field + "_change_ratio";
                        default -> throw error(422, "UNKNOWN_TRANSFORM", "数值转换未注册。");
                    };
                    if (!available(row, resolved)) throw error(422, "MISSING_NUMERIC_EVIDENCE", "缺失数据不能支持数值主张。");
                    JsonNode cell = cell(row, resolved); value = decimal(cell); unit = cell.path("unit").asText();
                    if ("PP_DELTA".equals(transform) && !"PP".equals(unit)) throw error(422, "INVALID_UNIT", "百分点转换的单位不一致。");
                }
                allowedNumbers.add(value.stripTrailingZeros());
                BigDecimal display = "CNY_CENT".equals(unit) ? value.divide(HUNDRED) : "RATIO".equals(unit) ? value.multiply(HUNDRED) : value;
                for (int precision : List.of(0, 2, 4, 6)) allowedNumbers.add(display.setScale(precision, RoundingMode.HALF_UP).stripTrailingZeros());
            }
            Matcher numbers = NUMBER.matcher(claim.path("text").asText());
            while (numbers.find()) {
                BigDecimal value = new BigDecimal(numbers.group().replace(",", "")).stripTrailingZeros();
                if (!allowedNumbers.contains(value)) throw error(422, "UNBOUND_NUMBER", "报告含有未经证据绑定的数字。");
            }
            if ((claim.path("type").asText().equals("OBSERVATION") || claim.path("type").asText().equals("DECOMPOSITION")) && refs.isEmpty())
                throw error(422, "UNSUPPORTED_CLAIM", "事实与分解结论必须提供证据。");
        }
    }

    private void verifyDecomposition(CommerceSubject subject, JsonNode report, JsonNode evidence) {
        ObjectNode source = queries.evidence(subject, evidence.path("sourceEvidenceId").asText()); assertBinding(report, source);
        if ("SEGMENT_CONTRIBUTION".equals(source.path("derivation").asText())) verifyContributions(subject, report, source);
        BigDecimal[] expected = decomposition(findRow(source.path("result"), evidence.path("sourceRowKey").asText()));
        JsonNode row = evidence.path("result").path("rows").get(0);
        if (decimal(cell(row, "orders_contribution")).subtract(expected[0]).abs().compareTo(new BigDecimal("0.000001")) > 0
                || decimal(cell(row, "aov_contribution")).subtract(expected[1]).abs().compareTo(new BigDecimal("0.000001")) > 0)
            throw error(422, "INVALID_DECOMPOSITION", "分解证据未通过重算校验。");
    }

    private void verifyContributions(CommerceSubject subject, JsonNode report, JsonNode evidence) {
        ObjectNode source = queries.evidence(subject, evidence.path("sourceEvidenceId").asText()); assertBinding(report, source);
        ObjectNode original = (ObjectNode) source.path("result");
        if (original.path("truncated").asBoolean() || incomplete(original)) throw error(422, "INVALID_CONTRIBUTION", "贡献计算不能使用截断或缺失的分组证据。");
        ObjectNode expected = withContributions(original);
        if (!expected.path("rows").equals(evidence.path("result").path("rows")) || !expected.path("totals").equals(evidence.path("result").path("totals")))
            throw error(422, "INVALID_CONTRIBUTION", "分组贡献证据未通过原始聚合重算校验。");
    }

    private void assertBinding(JsonNode runOrReport, JsonNode evidence) {
        if (!runOrReport.path("scope").equals(evidence.path("scope"))
                || !runOrReport.path("datasetVersionId").equals(evidence.path("datasetVersionId"))
                || !runOrReport.path("metricManifestHash").equals(evidence.path("metricManifestHash")))
            throw error(422, "EVIDENCE_SCOPE_MISMATCH", "证据范围、数据版本或指标口径与报告不一致。");
    }

    static BigDecimal[] decomposition(JsonNode row) {
        for (String metric : List.of("paid_gmv", "paid_gmv_baseline", "paid_orders", "paid_orders_baseline"))
            if (!available(row, metric)) throw error(422, "DECOMPOSITION_UNSUPPORTED", "缺失金额或订单数，无法进行分解。");
        BigDecimal currentOrders = decimal(cell(row, "paid_orders")); BigDecimal baselineOrders = decimal(cell(row, "paid_orders_baseline"));
        if (currentOrders.signum() == 0 || baselineOrders.signum() == 0) throw error(422, "ZERO_DENOMINATOR", "订单数为零，客单价无法定义，不能继续分解。");
        BigDecimal currentGmv = decimal(cell(row, "paid_gmv")); BigDecimal baselineGmv = decimal(cell(row, "paid_gmv_baseline"));
        BigDecimal currentAov = currentGmv.divide(currentOrders, PRECISION); BigDecimal baselineAov = baselineGmv.divide(baselineOrders, PRECISION);
        BigDecimal orders = currentOrders.subtract(baselineOrders).multiply(baselineAov.add(currentAov)).divide(new BigDecimal("2"), PRECISION);
        BigDecimal aov = currentAov.subtract(baselineAov).multiply(baselineOrders.add(currentOrders)).divide(new BigDecimal("2"), PRECISION);
        BigDecimal delta = currentGmv.subtract(baselineGmv);
        if (orders.add(aov).subtract(delta).abs().compareTo(BigDecimal.ONE) > 0) throw error(422, "DECOMPOSITION_RESIDUAL", "分解残差超过一分。");
        return new BigDecimal[] { orders, aov, delta };
    }

    private static ObjectNode claim(ArrayNode existing, String type, String text, List<String> refs, List<ObjectNode> bindings, String support) {
        return object("claimId", "c" + (existing.size() + 1), "type", type, "text", text, "evidenceRefs", refs.stream().distinct().toList(), "numericBindings", bindings, "support", support);
    }

    private static ObjectNode binding(JsonNode evidence, JsonNode row, String field, String transform) {
        return object("evidenceId", evidence.path("evidenceId"), "rowKey", row.path("rowKey"), "field", field, "transformId", transform);
    }

    private static JsonNode findRow(JsonNode result, String rowKey) {
        if (rowKey.equals(result.path("totals").path("rowKey").asText())) return result.path("totals");
        for (JsonNode row : result.path("rows")) if (rowKey.equals(row.path("rowKey").asText())) return row;
        throw error(422, "EVIDENCE_ROW_MISSING", "数值引用的证据行不存在。");
    }

    static boolean available(JsonNode row, String field) {
        JsonNode cell = cell(row, field); return !cell.isMissingNode() && !cell.path("value").isMissingNode() && !cell.path("value").isNull();
    }

    private static ObjectNode numberCell(String field, BigDecimal value, String unit) { return object("field", field, "value", value.stripTrailingZeros().toPlainString(), "unit", unit, "nullReason", null); }

    private static String formatted(JsonNode cell) {
        BigDecimal value = decimal(cell);
        return switch (cell.path("unit").asText()) {
            case "CNY_CENT" -> numeric(value.divide(HUNDRED), 2) + " 元";
            case "RATIO" -> numeric(value.multiply(HUNDRED), 4) + "%";
            case "PP" -> numeric(value, 4) + " 个百分点";
            default -> numeric(value, 0);
        };
    }

    private static String numeric(BigDecimal value, int precision) { return value.setScale(precision, RoundingMode.HALF_UP).toPlainString(); }
    private static String metricLabel(String metric) {
        return switch (metric) { case "paid_gmv" -> "支付 GMV"; case "paid_orders" -> "支付订单数"; case "aov" -> "客单价";
            case "visitor_sessions" -> "访问会话数"; case "order_conversion_rate" -> "订单转化比"; case "refund_amount" -> "退款金额";
            case "net_receipts" -> "净收款"; case "refund_intensity" -> "退款金额强度"; default -> metric; };
    }
}
