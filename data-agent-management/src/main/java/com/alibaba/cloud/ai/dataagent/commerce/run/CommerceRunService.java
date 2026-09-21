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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;

/** Durable application state is independent of any browser or SSE connection. */
@Service
@Profile("commerce")
public class CommerceRunService {

    static final Set<String> TERMINAL = Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED", "EXPIRED", "ACCESS_REVOKED");
    static final Set<String> TOOLS = Set.of("resolve_metric", "inspect_data_quality", "query_metrics", "compare_segments",
            "decompose_gmv", "inspect_inventory_signal", "lookup_business_definition", "finalize_report");
    private final CommerceStore store;
    private final CommercePolicy policy;
    private final CommerceQueryService queries;
    private final CommercePlanner planner;
    private final CommerceReportBuilder reports;
    private final String workerId = UUID.randomUUID().toString();
    private final CommerceRunGraph graph;
    private final boolean workerEnabled;

    public CommerceRunService(CommerceStore store, CommercePolicy policy, CommerceQueryService queries,
                              CommercePlanner planner, CommerceReportBuilder reports,
                              @Value("${commerce.worker.enabled:true}") boolean workerEnabled) {
        this.store = store;
        this.policy = policy;
        this.queries = queries;
        this.planner = planner;
        this.reports = reports;
        this.workerEnabled = workerEnabled;
        this.graph = new CommerceRunGraph(this);
    }

    public ObjectNode create(CommerceSubject subject, JsonNode request, String idempotencyKey) {
        policy.assertCurrent(subject);
        if (!subject.canAnalyze()) throw error(403, "ANALYSIS_FORBIDDEN", "当前角色不能发起诊断。");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128)
            throw error(400, "IDEMPOTENCY_KEY_REQUIRED", "请提供有效的 Idempotency-Key。");
        allowFields(request, Set.of("question", "requestedStoreIds", "dateRange", "comparison", "requireApproval"));
        String question = request.path("question").asText("").strip();
        if (question.isBlank() || question.length() > 2000)
            throw error(422, "INVALID_QUESTION", "问题应包含一到两千个字符。");
        if (!request.path("requestedStoreIds").isArray() || request.path("requestedStoreIds").isEmpty())
            throw error(422, "STORE_REQUIRED", "请选择分析店铺。");
        List<String> stores = strings(request.path("requestedStoreIds"));
        if (stores.size() > 100 || stores.size() != new HashSet<>(stores).size())
            throw error(422, "INVALID_STORES", "店铺列表不可重复且至多一百项。");
        ObjectNode scope = queries.scope(subject, stores);
        String comparison = request.path("comparison").asText();
        if (!Set.of("PREVIOUS_WEEK_SAME_DAYS", "PREVIOUS_PERIOD").contains(comparison))
            throw error(422, "INVALID_COMPARISON", "诊断需要选择上周同期或上一周期。");
        ObjectNode dateRange = dateRange(request.path("dateRange"));
        ObjectNode baseline = comparisonRange(dateRange, comparison);
        ObjectNode normalized = object("question", question, "requestedStoreIds", stores.stream().sorted().toList(),
                "dateRange", dateRange, "comparison", comparison, "requireApproval", request.path("requireApproval").asBoolean(true));
        String hash = sha256(normalized.toString());
        String key = sha256(subject.tenantId() + "|" + subject.subjectId() + "|create-run|" + idempotencyKey);
        try { return store.transaction(() -> {
            ObjectNode previous = store.findOne("run_key", key);
            if (previous != null && Instant.parse(previous.path("expiresAt").asText()).isAfter(Instant.now())) {
                if (!hash.equals(previous.path("requestHash").asText()))
                    throw error(409, "IDEMPOTENCY_CONFLICT", "此幂等键已用于不同的请求。");
                return get(subject, previous.path("runId").asText());
            }
            // A member-row CAS serializes per-subject admissions across application instances.
            ObjectNode member = store.get("member", subject.tenantId() + ":" + subject.subjectId());
            store.update("member", subject.tenantId() + ":" + subject.subjectId(), member.path("_revision").asLong(), m -> m);
            long concurrent = store.find("run", subject.tenantId()).stream().filter(r -> subject.subjectId().equals(r.path("subjectId").asText())
                    && !TERMINAL.contains(r.path("status").asText())).count();
            if (concurrent >= 2) throw error(429, "RUN_CONCURRENCY_LIMIT", "每位用户最多同时保留两个活动诊断，请先结束已有诊断。");
            ObjectNode coverage = object("start", baseline.path("start"), "endExclusive", dateRange.path("endExclusive"));
            ObjectNode dataset = queries.chooseDataset(subject, stores, coverage);
            String id = "run_" + UUID.randomUUID();
            Instant now = Instant.now();
            ObjectNode run = object("runId", id, "conversationId", "conv_" + UUID.randomUUID(), "subjectId", subject.subjectId(),
                    "tenantId", subject.tenantId(), "status", "QUEUED", "runVersion", 1, "question", question,
                    "scope", scope, "scopeHash", sha256(scope.toString()), "datasetVersionId", dataset.path("datasetVersionId"),
                    "snapshotId", dataset.path("datasetVersionId"), "metricManifestHash", queries.metricManifestHash(),
                    "authzVersion", subject.authzVersion(), "planVersion", 0, "plan", null, "planHash", null,
                    "dateRange", dateRange, "comparisonRange", baseline, "comparison", comparison,
                    "requireApproval", normalized.path("requireApproval"), "plannerMode", planner.mode(),
                    "metricId", clarification(question) == null ? resolveMetric(question) : null,
                    "diagnosticIntent", diagnosticIntent(question), "metricResolutionNote", metricResolutionNote(question),
                    "synthetic", dataset.path("synthetic").asBoolean(false), "createdAt", now.toString(),
                    "latestSequence", 0, "events", array(), "steps", array(), "evidenceIds", array(),
                    "acceptedActions", array(), "hypotheses", array(), "remainingBudget", object("toolCalls", 12, "tokens", 30000,
                            "activeMillis", 180000, "structureRepairs", 1, "modelRetries", 1),
                    "fencingToken", 0, "leaseUntil", Instant.EPOCH.toString(), "activeMillis", 0,
                    "eventsUrl", "/api/commerce/v1/runs/" + id + "/events", "reportId", null);
            event(run, "run.created", object("status", "QUEUED", "plannerMode", planner.mode()));
            store.create("run", id, subject.tenantId(), run);
            ObjectNode mapping = object("runId", id, "requestHash", hash, "expiresAt", now.plus(24, ChronoUnit.HOURS).toString());
            if (previous == null) store.create("run_key", key, subject.tenantId(), mapping);
            else store.update("run_key", key, previous.path("_revision").asLong(), old -> mapping);
            return visible(run);
        }); }
        catch (CommerceException ex) {
            if (Set.of("ALREADY_EXISTS", "VERSION_CONFLICT").contains(ex.code())) {
                ObjectNode raced = store.findOne("run_key", key);
                if (raced != null && hash.equals(raced.path("requestHash").asText())) return get(subject, raced.path("runId").asText());
            }
            throw ex;
        }
    }

    public ObjectNode get(CommerceSubject subject, String id) {
        ObjectNode run = store.get("run", id);
        policy.assertReadable(subject, run);
        return visible(run);
    }

    public List<ObjectNode> list(CommerceSubject subject) {
        policy.assertCurrent(subject);
        return readable(subject, "run").stream().map(this::visible).sorted(Comparator.comparing(r -> r.path("createdAt").asText(), Comparator.reverseOrder())).toList();
    }

    public ObjectNode approve(CommerceSubject subject, String id, JsonNode request) {
        allowFields(request, Set.of("decision", "planVersion", "planHash", "expectedRunVersion", "comment"));
        ObjectNode run = store.get("run", id);
        policy.assertReadable(subject, run);
        if (!subject.canAnalyze() || !subject.isAdmin() && !subject.subjectId().equals(run.path("subjectId").asText()))
            throw error(403, "APPROVAL_FORBIDDEN", "当前角色不能审批诊断计划。");
        if (!"WAITING_APPROVAL".equals(run.path("status").asText())) throw conflict("运行未处于待审批状态。");
        assertVersion(run, request.path("expectedRunVersion").asLong(-1));
        JsonNode plan = run.path("plan");
        if (plan.path("planVersion").asLong() != request.path("planVersion").asLong(-1)
                || !plan.path("planHash").asText().equals(request.path("planHash").asText())) throw conflict("计划已更新，请重新确认。");
        if (!Instant.parse(plan.path("expiresAt").asText()).isAfter(Instant.now()))
            throw error(409, "PLAN_EXPIRED", "计划已过期，请新建诊断。");
        assertDataset(run);
        String decision = request.path("decision").asText();
        if (!Set.of("APPROVE", "REJECT").contains(decision)) throw error(422, "INVALID_DECISION", "审批决定无效。");
        return store.transaction(() -> {
        ObjectNode updated = change(run, current -> {
            current.set("approval", object("decision", decision, "subjectId", subject.subjectId(), "approvedAt", Instant.now().toString(),
                    "planVersion", plan.path("planVersion"), "planHash", plan.path("planHash"), "comment", request.path("comment").asText("")));
            if ("APPROVE".equals(decision)) state(current, "RUNNING");
            else {
                current.put("stopReason", "计划被拒绝。请调整问题后创建新的诊断。");
                state(current, "CANCELLED");
                event(current, "run.cancelled", object("reason", "PLAN_REJECTED"));
            }
        });
        store.create("audit", "audit_" + UUID.randomUUID(), subject.tenantId(), object("operation", "PLAN_" + decision,
                "subjectId", subject.subjectId(), "runId", id, "planVersion", plan.path("planVersion"), "planHash", plan.path("planHash"), "createdAt", Instant.now().toString()));
        return visible(updated);
        });
    }

    public ObjectNode cancel(CommerceSubject subject, String id, long expectedVersion) {
        ObjectNode run = store.get("run", id);
        policy.assertReadable(subject, run);
        if (!subject.canAnalyze() || !subject.isAdmin() && !subject.subjectId().equals(run.path("subjectId").asText()))
            throw error(403, "CANCEL_FORBIDDEN", "当前角色不能取消此运行。");
        if (TERMINAL.contains(run.path("status").asText()) || "CANCEL_REQUESTED".equals(run.path("status").asText())) return visible(run);
        assertVersion(run, expectedVersion);
        return visible(change(run, current -> state(current, "CANCEL_REQUESTED")));
    }

    public List<ObjectNode> events(CommerceSubject subject, String id, long after) {
        ObjectNode run = store.get("run", id);
        // Scope is checked on every polling pass, including existing SSE connections.
        try { policy.assertReadable(subject, run); }
        catch (CommerceException ex) {
            if (!subject.tenantId().equals(run.path("scope").path("tenantId").asText()) || !subject.subjectId().equals(run.path("subjectId").asText())) throw ex;
            if (!"ACCESS_REVOKED".equals(run.path("status").asText())) run = change(run, r -> {
                state(r, "ACCESS_REVOKED"); event(r, "access.revoked", object("reason", "AUTHORIZATION_CHANGED"));
            });
            List<ObjectNode> revoked = new ArrayList<>();
            for (JsonNode event : run.path("events")) if ("access.revoked".equals(event.path("type").asText()) && event.path("sequence").asLong() > after)
                revoked.add((ObjectNode) event.deepCopy());
            return revoked;
        }
        if (after < 0 || after > run.path("latestSequence").asLong()) throw error(422, "INVALID_EVENT_CURSOR", "事件游标无效。");
        if (after > 0 && after < run.path("firstRetainedSequence").asLong(1) - 1)
            throw error(410, "EVENTS_EXPIRED", "事件已过期，请从运行快照恢复。");
        List<ObjectNode> result = new ArrayList<>();
        for (JsonNode event : run.path("events")) if (event.path("sequence").asLong() > after) result.add((ObjectNode) event.deepCopy());
        return result;
    }

    public List<ObjectNode> reports(CommerceSubject subject) {
        policy.assertCurrent(subject);
        return readable(subject, "report").stream().map(this::publicReport)
                .sorted(Comparator.comparing(r -> r.path("createdAt").asText(), Comparator.reverseOrder())).toList();
    }

    public ObjectNode report(CommerceSubject subject, String id) {
        ObjectNode report = store.get("report", id);
        policy.assertReadable(subject, report);
        reports.validate(subject, report);
        return publicReport(report);
    }

    public String export(CommerceSubject subject, String id) {
        ObjectNode report = report(subject, id);
        StringBuilder markdown = new StringBuilder("# ").append(report.path("title").asText()).append("\n\n");
        markdown.append("数据版本：").append(report.path("datasetVersionId").asText()).append("\n\n");
        for (JsonNode claim : report.path("claims")) markdown.append("- **").append(claim.path("type").asText()).append("**：")
                .append(claim.path("text").asText()).append(" ").append(claim.path("evidenceRefs")).append("\n");
        markdown.append("\n## 分析边界\n\n");
        for (JsonNode limitation : report.path("limitations")) markdown.append("- ").append(limitation.asText()).append("\n");
        store.create("audit", "audit_" + UUID.randomUUID(), subject.tenantId(), object("operation", "REPORT_EXPORT", "subjectId", subject.subjectId(),
                "reportId", id, "version", report.path("version"), "scopeHash", sha256(report.path("scope").toString()), "createdAt", Instant.now().toString()));
        return markdown.toString();
    }

    /** Scheduled work, not subscription callbacks, owns execution and recovery. */
    @Scheduled(fixedDelayString = "${commerce.worker.delay-ms:500}")
    public void tick() {
        if (!workerEnabled) return;
        for (ObjectNode candidate : store.all("run")) {
            if (TERMINAL.contains(candidate.path("status").asText())) continue;
            try { process(candidate.path("runId").asText()); }
            catch (CommerceException ignored) { /* Another worker or user won the CAS; read again on the next scan. */ }
        }
    }

    public void process(String id) {
        ObjectNode run = store.get("run", id);
        if (TERMINAL.contains(run.path("status").asText())) return;
        CommerceSubject subject;
        try {
            subject = policy.subject(run.path("tenantId").asText(), run.path("subjectId").asText());
            policy.assertReadable(subject, run);
        }
        catch (CommerceException revoked) {
            change(run, r -> { state(r, "ACCESS_REVOKED"); r.put("leaseUntil", Instant.EPOCH.toString()); event(r, "access.revoked", object("reason", "AUTHORIZATION_CHANGED")); });
            return;
        }
        if ("CANCEL_REQUESTED".equals(run.path("status").asText())) {
            change(run, r -> { state(r, "CANCELLED"); r.put("leaseUntil", Instant.EPOCH.toString()); event(r, "run.cancelled", object()); });
            return;
        }
        if ("WAITING_APPROVAL".equals(run.path("status").asText())) {
            if (!Instant.parse(run.path("plan").path("expiresAt").asText()).isAfter(Instant.now()))
                change(run, r -> { state(r, "EXPIRED"); event(r, "run.failed", object("code", "PLAN_EXPIRED")); });
            return;
        }
        if (Instant.parse(run.path("leaseUntil").asText(Instant.EPOCH.toString())).isAfter(Instant.now())) return;
        run = change(run, r -> {
            r.put("fencingToken", r.path("fencingToken").asLong() + 1);
            r.put("leaseOwner", workerId);
            r.put("leaseUntil", Instant.now().plusSeconds(60).toString());
            r.put("executionStartedAt", Instant.now().toString());
        });
        long fence = run.path("fencingToken").asLong();
        long started = System.nanoTime();
        try {
            if (Set.of("QUEUED", "PLANNING").contains(run.path("status").asText())) prepare(subject, run, fence);
            if ("RUNNING".equals(store.get("run", id).path("status").asText())) graph.execute(id, fence);
        }
        catch (CommerceException ex) {
            if (!Set.of("VERSION_CONFLICT", "LEASE_LOST", "ACCESS_REVOKED").contains(exCode(ex))) failIfOwned(id, fence, "RUN_EXECUTION_FAILED", "诊断执行失败，请查看数据质量或重新运行。");
        }
        catch (RuntimeException ex) { failIfOwned(id, fence, "RUN_EXECUTION_FAILED", "诊断依赖暂不可用，请稍后重试。"); }
        finally {
            ObjectNode latest = store.get("run", id);
            if (latest.path("fencingToken").asLong() == fence && workerId.equals(latest.path("leaseOwner").asText())) {
                long elapsed = Duration.ofNanos(System.nanoTime() - started).toMillis();
                try { change(latest, r -> {
                    r.put("leaseUntil", Instant.EPOCH.toString());
                    r.put("activeMillis", r.path("activeMillis").asLong() + elapsed);
                    r.remove("executionStartedAt");
                    ((ObjectNode) r.path("remainingBudget")).put("activeMillis", Math.max(0, 180000 - r.path("activeMillis").asLong()));
                }); } catch (CommerceException ignored) { }
            }
        }
    }

    private void prepare(CommerceSubject subject, ObjectNode run, long fence) {
        run = owned(run.path("runId").asText(), fence);
        ObjectNode prepared = run;
        change(run, current -> {
            state(current, "PLANNING");
            int version = current.path("planVersion").asInt() + 1;
            ArrayNode actions = array();
            actions.add(object("actionId", "quality", "toolName", "inspect_data_quality", "purpose",
                    current.path("metricResolutionNote").asText("").isBlank() ? "确认当前与基期数据覆盖" : current.path("metricResolutionNote").asText(), "maxCalls", 1));
            actions.add(object("actionId", "segments", "toolName", "compare_segments", "purpose", "按店铺核对变化与反向贡献", "maxCalls", 3));
            actions.add(object("actionId", "query", "toolName", "query_metrics", "purpose", "查询已授权口径的聚合证据", "maxCalls", 4));
            actions.add(object("actionId", "decompose", "toolName", "decompose_gmv", "purpose", "用订单数和客单价做确定性对称分解", "maxCalls", 1));
            actions.add(object("actionId", "inventory", "toolName", "inspect_inventory_signal", "purpose", "必要时核查商品库存相关信号", "maxCalls", 1));
            actions.add(object("actionId", "final", "toolName", "finalize_report", "purpose", "生成有证据引用的报告", "maxCalls", 1));
            ObjectNode plan = object("planVersion", version, "scope", current.path("scope"), "dateRange", current.path("dateRange"),
                    "actions", actions, "maxToolCalls", 12, "maxTokens", 30000, "expiresAt", Instant.now().plus(24, ChronoUnit.HOURS).toString());
            plan.put("planHash", sha256(object("plan", plan, "datasetVersionId", prepared.path("datasetVersionId"),
                    "metricManifestHash", prepared.path("metricManifestHash")).toString()));
            current.set("plan", plan);
            current.put("planVersion", version);
            current.put("planHash", plan.path("planHash").asText());
            event(current, "plan.ready", object("planVersion", version, "planHash", plan.path("planHash")));
            if (current.path("requireApproval").asBoolean(true)) {
                state(current, "WAITING_APPROVAL"); event(current, "approval.required", object("planVersion", version));
            }
            else {
                current.set("approval", object("decision", "APPROVE", "automatic", true, "planVersion", version, "planHash", plan.path("planHash")));
                state(current, "RUNNING");
            }
        });
    }

    /** Graph decision node. Every graph state is reloaded under its persisted fencing token. */
    boolean select(String id, long fence) {
        if (!"RUNNING".equals(store.get("run", id).path("status").asText())) return false;
        ObjectNode run = owned(id, fence);
        if (!"RUNNING".equals(run.path("status").asText())) return false;
        CommerceSubject subject = currentSubject(run);
        if (run.path("pendingAction").isObject()) return true;
        if (run.path("remainingBudget").path("toolCalls").asInt() <= 0 || run.path("remainingBudget").path("tokens").asInt() <= 0
                || activeMillis(run) >= 180000) {
            finish(subject, run, fence, "执行预算已用完，报告仅包含已经验证的证据。"); return false;
        }
        ObjectNode clarification = clarification(run.path("question").asText());
        if (clarification != null) {
            run = change(run, r -> r.set("clarification", clarification));
            finish(subject, run, fence, clarification.path("message").asText()); return false;
        }
        List<ObjectNode> evidence = evidence(subject, run);
        ObjectNode action;
        if ("MODEL".equals(planner.mode())) {
            action = modelAction(subject, run, fence, evidence);
            if (action == null) return false;
        }
        else action = planner.next(run, evidence);
        validateAction(run, action);
        change(owned(id, fence), r -> {
            r.set("pendingAction", action);
            ((ArrayNode) r.path("acceptedActions")).add(action);
            r.put("leaseUntil", Instant.now().plusSeconds(60).toString());
        });
        return true;
    }

    private ObjectNode modelAction(CommerceSubject subject, ObjectNode initial, long fence, List<ObjectNode> evidence) {
        String id = initial.path("runId").asText();
        for (int attempt = 0; attempt < 3; attempt++) {
            ObjectNode run = owned(id, fence);
            int reservation = planner.tokenReservation(run, evidence);
            if (run.path("remainingBudget").path("tokens").asInt() < reservation || activeMillis(run) >= 180000) {
                finish(subject, run, fence, "剩余模型预算不足以继续调查。"); return null;
            }
            run = change(run, r -> {
                ObjectNode budget = (ObjectNode) r.path("remainingBudget"); budget.put("tokens", budget.path("tokens").asInt() - reservation);
                r.put("modelAttempts", r.path("modelAttempts").asInt() + 1);
            });
            try {
                ObjectNode action = planner.next(run, evidence);
                ObjectNode usage = planner.lastUsage();
                change(owned(id, fence), r -> {
                    r.put("modelInputTokens", r.path("modelInputTokens").asLong() + usage.path("inputTokens").asLong());
                    r.put("modelOutputTokens", r.path("modelOutputTokens").asLong() + usage.path("outputTokens").asLong());
                    r.put("tokenAccounting", usage.path("known").asBoolean() ? "PROVIDER_USAGE_WITH_RESERVED_BUDGET" : "RESERVED_ESTIMATE_USAGE_UNKNOWN");
                    if (usage.path("known").asBoolean()) {
                        long used = usage.path("inputTokens").asLong() + usage.path("outputTokens").asLong();
                        ObjectNode budget = (ObjectNode) r.path("remainingBudget");
                        budget.put("tokens", Math.max(0, budget.path("tokens").asLong() + reservation - used));
                    }
                });
                validateAction(run, action);
                return action;
            }
            catch (RuntimeException ex) {
                boolean structure = ex instanceof CommerceException ce && ce.status() == 422;
                String budgetName = structure ? "structureRepairs" : "modelRetries";
                ObjectNode fresh = owned(id, fence);
                if (fresh.path("remainingBudget").path(budgetName).asInt() <= 0) {
                    finish(subject, fresh, fence, structure ? "模型行动未通过结构与批准范围校验，已停止继续调查。" : "模型服务未能完成调查，保留已经验证的证据。");
                    return null;
                }
                change(fresh, r -> {
                    ObjectNode budget = (ObjectNode) r.path("remainingBudget"); budget.put(budgetName, budget.path(budgetName).asInt() - 1);
                    r.put("lastActionError", structure ? "上一个行动不合法，请严格遵守工具参数、范围及已完成行动约束。" : "上次模型调用失败，重试已计入预算。");
                });
            }
        }
        finish(subject, owned(id, fence), fence, "模型修复与重试预算已用完。");
        return null;
    }

    /** Graph tool node. Committed step results are reusable after a lease takeover. */
    void executeStep(String id, long fence) {
        ObjectNode run = owned(id, fence);
        if (!"RUNNING".equals(run.path("status").asText())) return;
        CommerceSubject subject = currentSubject(run);
        assertDataset(run);
        ObjectNode action = (ObjectNode) run.path("pendingAction");
        validateAction(run, action);
        String stepId = sha256(id + "|" + action.path("actionId").asText());
        ObjectNode saved = store.findOne("run_step", stepId);
        if (saved == null) {
            if (run.path("remainingBudget").path("toolCalls").asInt() <= 0) {
                finish(subject, run, fence, "工具调用预算已用完。"); return;
            }
            run = change(run, r -> {
                ObjectNode budget = (ObjectNode) r.path("remainingBudget"); budget.put("toolCalls", budget.path("toolCalls").asInt() - 1);
                event(r, "step.started", object("actionId", action.path("actionId"), "toolName", action.path("toolName"), "purpose", action.path("purpose")));
            });
            if ("finalize_report".equals(action.path("toolName").asText())) { finish(subject, run, fence, null); return; }
            ObjectNode boundRun = run;
            saved = store.transaction(() -> {
                ObjectNode duplicate = store.findOne("run_step", stepId);
                if (duplicate != null) return duplicate;
                ObjectNode result = perform(subject, boundRun, action);
                owned(id, fence); // Never commit late results after cancellation, authorization loss or lease takeover.
                ObjectNode step = object("action", action, "result", result, "completedAt", Instant.now().toString(), "runId", id);
                store.create("run_step", stepId, subject.tenantId(), step);
                return step;
            });
        }
        ObjectNode result = (ObjectNode) saved.path("result");
        ObjectNode completedStep = saved;
        change(owned(id, fence), r -> {
            ((ArrayNode) r.path("steps")).add(completedStep);
            String evidenceId = result.path("evidenceId").asText("");
            if (!evidenceId.isBlank() && !strings(r.path("evidenceIds")).contains(evidenceId)) {
                ((ArrayNode) r.path("evidenceIds")).add(evidenceId);
                event(r, "evidence.ready", object("evidenceId", evidenceId));
            }
            event(r, "step.summary", object("actionId", action.path("actionId"), "toolName", action.path("toolName"),
                    "summary", action.path("purpose").asText("已完成受控调查"), "evidenceId", evidenceId));
            r.remove("pendingAction");
            r.put("leaseUntil", Instant.now().plusSeconds(60).toString());
        });
    }

    private ObjectNode perform(CommerceSubject subject, ObjectNode run, ObjectNode action) {
        String tool = action.path("toolName").asText();
        JsonNode args = action.path("arguments");
        return switch (tool) {
            case "inspect_data_quality" -> {
                ObjectNode dataset = store.get("dataset", run.path("datasetVersionId").asText());
                yield object("quality", dataset.path("quality"), "coverage", dataset.path("coverage"), "datasetVersionId", run.path("datasetVersionId"));
            }
            case "query_metrics" -> queries.queryBound(subject, run.path("scope"), run.path("datasetVersionId").asText(), args.path("querySpec"));
            case "compare_segments" -> {
                ObjectNode result = queries.queryBound(subject, run.path("scope"), run.path("datasetVersionId").asText(), args.path("querySpec"));
                yield strings(args.path("querySpec").path("metricIds")).contains("paid_gmv")
                        ? reports.compareSegments(subject, run, result) : result;
            }
            case "inspect_inventory_signal" -> queries.inventory(subject, run.path("scope"), run.path("datasetVersionId").asText(), strings(args.path("productIds")), run.path("dateRange"));
            case "decompose_gmv" -> reports.decompose(subject, run, args.path("evidenceId").asText(), args.path("rowKey").asText());
            case "resolve_metric", "lookup_business_definition" -> object("metrics", queries.metrics());
            default -> throw error(422, "TOOL_NOT_ALLOWED", "该工具未被允许。");
        };
    }

    private void finish(CommerceSubject subject, ObjectNode run, long fence, String limitation) {
        ObjectNode report = reports.build(subject, run, evidence(subject, run), limitation);
        reports.validate(subject, report);
        store.transaction(() -> {
            ObjectNode fresh = owned(run.path("runId").asText(), fence);
            String reportId = report.path("reportId").asText();
            if (store.findOne("report", reportId) == null) store.create("report", reportId, subject.tenantId(), report);
            change(fresh, r -> {
                r.put("reportId", reportId); r.remove("pendingAction"); state(r, report.path("status").asText());
                event(r, "report.ready", object("reportId", reportId));
                event(r, "run.completed", object("status", report.path("status"), "reportId", reportId));
            }); return null;
        });
    }

    private List<ObjectNode> evidence(CommerceSubject subject, ObjectNode run) {
        List<ObjectNode> evidence = new ArrayList<>();
        for (String id : strings(run.path("evidenceIds"))) {
            ObjectNode document = queries.evidence(subject, id);
            ObjectNode result = document.path("result").isObject() ? (ObjectNode) document.path("result").deepCopy() : document.deepCopy();
            result.put("evidenceId", id);
            result.set("metricManifestHash", document.path("metricManifestHash"));
            result.set("kind", document.path("kind"));
            evidence.add(result);
        }
        return evidence;
    }

    private ObjectNode owned(String id, long fence) {
        ObjectNode run = store.get("run", id);
        if (run.path("fencingToken").asLong() != fence || !workerId.equals(run.path("leaseOwner").asText())
                || !Instant.parse(run.path("leaseUntil").asText()).isAfter(Instant.now())) throw error(409, "LEASE_LOST", "执行租约已变更。");
        if ("CANCEL_REQUESTED".equals(run.path("status").asText()) || TERMINAL.contains(run.path("status").asText()))
            throw error(409, "LEASE_LOST", "运行已停止。");
        currentSubject(run);
        return run;
    }

    private CommerceSubject currentSubject(ObjectNode run) {
        CommerceSubject subject = policy.subject(run.path("tenantId").asText(), run.path("subjectId").asText());
        policy.assertReadable(subject, run);
        return subject;
    }

    private void assertDataset(ObjectNode run) {
        ObjectNode dataset = store.get("dataset", run.path("datasetVersionId").asText());
        if (!"PUBLISHED".equals(dataset.path("status").asText())) throw error(409, "DATASET_UNAVAILABLE", "数据版本已不可用。");
        if (!queries.metricManifestHash().equals(run.path("metricManifestHash").asText())) throw error(409, "METRICS_CHANGED", "指标口径已更新，请重新发起诊断。");
    }

    private void validateAction(ObjectNode run, ObjectNode action) {
        allowFields(action, Set.of("actionId", "toolName", "arguments", "hypothesisId", "expectedEvidenceType", "purpose"));
        String name = action.path("toolName").asText();
        if (!TOOLS.contains(name)) throw error(422, "TOOL_NOT_ALLOWED", "模型选择了未注册的工具。");
        if (action.path("actionId").asText().isBlank() || !action.path("arguments").isObject()) throw error(422, "INVALID_ACTION", "调查行动格式无效。");
        JsonNode allowance = null;
        for (JsonNode allowed : run.path("plan").path("actions")) if (name.equals(allowed.path("toolName").asText())) allowance = allowed;
        if (allowance == null) throw error(422, "PLAN_BOUNDARY", "该行动超出批准计划。");
        int completed = 0;
        for (JsonNode step : run.path("steps")) {
            if (action.path("actionId").equals(step.path("action").path("actionId"))) throw error(422, "DUPLICATE_ACTION", "行动标识已完成，不能重复使用。");
            if (name.equals(step.path("action").path("toolName").asText())) completed++;
        }
        if (completed >= allowance.path("maxCalls").asInt()) throw error(429, "TOOL_BUDGET_EXCEEDED", "该工具的批准调用次数已用完。");
        JsonNode args = action.path("arguments");
        switch (name) {
            case "query_metrics", "compare_segments" -> {
                allowFields(args, Set.of("querySpec"));
                JsonNode spec = args.path("querySpec");
                if (!spec.path("dateRange").equals(run.path("dateRange")) || !spec.path("comparison").equals(run.path("comparison")))
                    throw error(422, "PLAN_BOUNDARY", "查询日期或对比范围超出批准计划。");
                if (spec.path("limit").asInt() > 100) throw error(422, "MODEL_RESULT_LIMIT", "诊断单次最多读取一百行聚合结果。");
            }
            case "inspect_inventory_signal" -> { allowFields(args, Set.of("productIds")); if (!args.path("productIds").isArray() || args.path("productIds").isEmpty()) throw error(422, "INVALID_ACTION", "库存调查必须指定已登记商品。"); }
            case "decompose_gmv" -> { allowFields(args, Set.of("evidenceId", "rowKey")); if (!strings(run.path("evidenceIds")).contains(args.path("evidenceId").asText())) throw error(422, "UNREGISTERED_EVIDENCE", "分解必须使用当前运行的证据。"); }
            default -> allowFields(args, Set.of());
        }
    }

    private void failIfOwned(String id, long fence, String code, String message) {
        ObjectNode run = store.get("run", id);
        if (run.path("fencingToken").asLong() != fence || TERMINAL.contains(run.path("status").asText()) || "CANCEL_REQUESTED".equals(run.path("status").asText())) return;
        try {
            try { currentSubject(run); }
            catch (CommerceException ex) { change(run, r -> { state(r, "ACCESS_REVOKED"); event(r, "access.revoked", object("reason", "AUTHORIZATION_CHANGED")); }); return; }
            change(run, r -> { state(r, "FAILED"); r.set("error", object("code", code, "message", message)); event(r, "run.failed", object("code", code, "message", message)); });
        }
        catch (CommerceException ignored) { }
    }

    private ObjectNode change(ObjectNode run, Consumer<ObjectNode> mutation) {
        return store.update("run", run.path("runId").asText(), run.path("_revision").asLong(), current -> {
            mutation.accept(current); current.put("runVersion", current.path("runVersion").asLong() + 1); return current;
        });
    }

    private List<ObjectNode> readable(CommerceSubject subject, String kind) {
        List<ObjectNode> result = new ArrayList<>();
        for (ObjectNode resource : store.find(kind, subject.tenantId())) {
            try { policy.assertReadable(subject, resource); result.add(resource); } catch (CommerceException ignored) { }
        }
        return result;
    }

    private ObjectNode visible(ObjectNode run) {
        ObjectNode view = run.deepCopy();
        view.remove(List.of("_revision", "tenantId", "subjectId", "events", "leaseOwner", "leaseUntil", "fencingToken", "pendingAction"));
        return view;
    }

    private ObjectNode publicReport(ObjectNode report) {
        ObjectNode result = report.deepCopy(); result.remove(List.of("_revision", "tenantId", "subjectId")); return result;
    }

    static void state(ObjectNode run, String state) {
        run.put("status", state); event(run, "run.state.changed", object("status", state));
    }

    static void event(ObjectNode run, String type, JsonNode payload) {
        long seq = run.path("latestSequence").asLong() + 1;
        run.put("latestSequence", seq);
        ((ArrayNode) run.path("events")).add(object("schemaVersion", "1.0", "runId", run.path("runId"), "sequence", seq,
                "type", type, "occurredAt", Instant.now().toString(), "payload", payload));
    }

    static ObjectNode dateRange(JsonNode value) {
        try {
            allowFields(value, Set.of("start", "endExclusive"));
            LocalDate start = LocalDate.parse(value.path("start").asText()); LocalDate end = LocalDate.parse(value.path("endExclusive").asText());
            if (!start.isBefore(end) || ChronoUnit.DAYS.between(start, end) > 90) throw new IllegalArgumentException();
            return object("start", start.toString(), "endExclusive", end.toString());
        }
        catch (RuntimeException ex) { throw error(422, "INVALID_DATE_RANGE", "日期范围必须有效且不超过九十天。"); }
    }

    static ObjectNode comparisonRange(JsonNode date, String comparison) {
        LocalDate start = LocalDate.parse(date.path("start").asText()); LocalDate end = LocalDate.parse(date.path("endExclusive").asText());
        long shift = "PREVIOUS_PERIOD".equals(comparison) ? ChronoUnit.DAYS.between(start, end) : 7;
        return object("start", start.minusDays(shift).toString(), "endExclusive", end.minusDays(shift).toString());
    }

    static String clarify(String question) {
        ObjectNode clarification = clarification(question);
        return clarification == null ? null : clarification.path("message").asText();
    }

    private static ObjectNode clarification(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        if (lower.matches("(?s).*(sql|select\\s|drop\\s|delete\\s|insert\\s|update\\s|union\\s|execute|shell|python).*"))
            return clarification("UNSUPPORTED_QUERY", "诊断只支持已发布经营指标，不执行用户提供的 SQL、脚本或命令。");
        if (question.matches("(?s).*(自动退款|执行退款|退款操作|发起退款|删除|修改数据|写入|调整库存|执行转账|自动下单|补货\\s*\\d+).*"))
            return clarification("READ_ONLY_BOUNDARY", "诊断仅进行只读分析，不能执行退款、补货、库存变更或其他经营写操作。");
        if (question.matches("(?s).*(直接|立即|自动|执行|请|帮我).*补货.*"))
            return clarification("READ_ONLY_BOUNDARY", "诊断仅进行只读分析，不能执行补货或其他库存写操作；可以先核查库存相关信号。");
        // Capability boundaries precede generic metric aliases: SKU refunds must never become a cohort query.
        if (productQuestion(question) && lower.matches("(?s).*(退款|退货|转化|访客|会话|支付订单|订单数|净收|客单价|refund|conversion|visitor_sessions|paid_orders|net_receipts|aov).*"))
            return clarification("UNSUPPORTED_METRIC_DIMENSION", "商品（SKU）维度仅支持支付 GMV。当前缺少订单项与退款映射，不能推算 SKU 退款额或退款率；商品订单数、净收款、客单价与流量转化也未发布。");
        if (lower.matches("(?s).*(cohort|队列|订单群|退款率|退货率).*"))
            return clarification("AMBIGUOUS_REFUND_METRIC", "请区分同期事件退款金额强度与订单群（cohort）退款率。首版可以计算同期退款金额强度，不支持订单群退款率，不能换名替代。");
        if (lower.matches("(?s).*(广告|投放|\\broi\\b|\\broas\\b).*"))
            return clarification("UNSUPPORTED_DOMAIN", "当前没有广告消耗、广告计划或广告归因事实，不能分析广告 ROI 或判断哪个广告计划拖累经营表现。");
        if (lower.matches("(?s).*(独立访客|去重用户|去重访客|去重人数|跨店用户|\\buv\\b).*"))
            return clarification("UNSUPPORTED_METRIC", "当前仅有店铺日级访客会话数，会话数不是跨店去重用户或独立访客，不能给出跨店独立访客人数。可以改查访客会话数。");
        if (lower.matches("(?s).*(多币|外币|美元|欧元|usd|eur|汇率).*"))
            return clarification("UNSUPPORTED_CURRENCY", "当前指标以人民币分计量，不支持跨币种汇总或汇率换算。");
        if (question.matches("(?s).*(预测|未来|明天|后天|预计).*"))
            return clarification("UNSUPPORTED_FORECAST", "当前仅支持历史事实分析，没有可验证的预测能力。请选择已发布数据覆盖的日期。");
        if (question.matches("(?s).*(手机号|电话号码|客户明细|个人信息|身份证).*"))
            return clarification("UNSUPPORTED_PERSONAL_DATA", "诊断只提供授权范围内的经营聚合，不返回客户个人信息。");
        if (question.contains("利润") || lower.contains("profit"))
            return clarification("UNSUPPORTED_METRIC", "当前没有成本、税费等完整口径，无法计算利润；净收款也不是利润。可以改查支付 GMV、订单或退款指标。");
        if (question.contains("销售额")) {
            ObjectNode result = clarification("AMBIGUOUS_METRIC", "请确认销售额是指下单金额、支付金额（支付 GMV），还是扣除同期成功退款后的净收款。首版未发布下单金额口径，可以选择支付 GMV 或净收款；不能将三者混用。");
            result.set("choices", array().add(object("metricId", null, "label", "下单金额", "supported", false))
                    .add(object("metricId", "paid_gmv", "label", "支付金额（支付 GMV）", "supported", true))
                    .add(object("metricId", "net_receipts", "label", "净收款", "supported", true)));
            return result;
        }
        if (resolveMetric(question) == null)
            return clarification("METRIC_REQUIRED", "请明确要分析的指标：支付 GMV、支付订单数、客单价、退款金额、净收款、退款金额强度、访问会话或订单转化比。");
        return null;
    }

    private static ObjectNode clarification(String code, String message) {
        return object("code", code, "message", message, "suggestedQuestion", "支付 GMV 为什么变化？");
    }

    static String resolveMetric(String question) {
        String normalized = question.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        // Match complete compound names before their component words, especially 退款金额强度.
        if (normalized.contains("净收") || normalized.contains("net_receipts")) return "net_receipts";
        if (normalized.matches(".*退款(?:金额)?强度.*") || normalized.contains("refund_intensity")) return "refund_intensity";
        if (normalized.contains("退款") || normalized.contains("refund_amount")) return "refund_amount";
        if (normalized.contains("转化") || normalized.contains("conversion")) return "order_conversion_rate";
        if (normalized.contains("会话") || normalized.contains("访客") || normalized.contains("流量") || normalized.contains("visitor_sessions")) return "visitor_sessions";
        if (normalized.contains("gmv") || normalized.contains("支付金额") || gmvDecompositionQuestion(question)) return "paid_gmv";
        if (normalized.contains("客单价") || normalized.contains("aov")) return "aov";
        if (normalized.contains("订单") || normalized.contains("paid_orders")) return "paid_orders";
        // Only these documented CommerceLens contexts have a default measure; arbitrary questions still clarify.
        if (storeContributionQuestion(question) || skuChangeQuestion(question)) return "paid_gmv";
        return null;
    }

    private static boolean productQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return lower.contains("sku") || question.contains("商品") || question.contains("单品");
    }

    private static boolean gmvDecompositionQuestion(String question) {
        String lower = question.toLowerCase(Locale.ROOT);
        return (question.contains("订单") || lower.contains("paid_orders")) && (question.contains("客单价") || lower.contains("aov"))
                && question.matches("(?s).*(贡献|分解|拆解).*" );
    }

    private static boolean storeContributionQuestion(String question) {
        return question.matches("(?s).*(店铺|门店|店).*" ) && question.matches("(?s).*(贡献|拖累).*" )
                && question.matches("(?s).*(下降|下滑|减少).*" );
    }

    private static boolean skuChangeQuestion(String question) {
        return productQuestion(question) && question.matches("(?s).*(变化|变动|下降|下滑|减少|增长|上升).*" );
    }

    private static String diagnosticIntent(String question) {
        if (!"paid_gmv".equals(resolveMetric(question))) return "METRIC_COMPARISON";
        if (gmvDecompositionQuestion(question)) return "GMV_DECOMPOSITION";
        if (skuChangeQuestion(question)) return "SKU_COMPARISON";
        if (storeContributionQuestion(question)) return "STORE_CONTRIBUTION";
        return "GMV_DIAGNOSIS";
    }

    private static String metricResolutionNote(String question) {
        if (clarification(question) != null || !"paid_gmv".equals(resolveMetric(question))) return "";
        if (gmvDecompositionQuestion(question)) return "将订单数与客单价的贡献解释为支付 GMV 变化的对称分解，分析当前完整授权范围。";
        if (skuChangeQuestion(question)) return "商品变化按已发布的支付 GMV 口径分析；主店与核心商品根据实际变化证据定位，不预设店铺或商品编号。";
        if (storeContributionQuestion(question)) return "店铺下降贡献按支付 GMV 的净变化分析；贡献比例是数学分配，不是原因概率。";
        return "";
    }

    static void allowFields(JsonNode node, Set<String> allowed) {
        if (!node.isObject()) throw error(422, "INVALID_STRUCTURE", "请求格式无效。");
        node.fieldNames().forEachRemaining(name -> { if (!allowed.contains(name)) throw error(422, "UNKNOWN_FIELD", "不支持的字段：" + name); });
    }

    static List<String> strings(JsonNode nodes) {
        List<String> result = new ArrayList<>(); nodes.forEach(n -> result.add(n.asText())); return result;
    }

    private static long activeMillis(JsonNode run) {
        long elapsed = run.path("activeMillis").asLong();
        if (run.hasNonNull("executionStartedAt")) elapsed += Duration.between(Instant.parse(run.path("executionStartedAt").asText()), Instant.now()).toMillis();
        return elapsed;
    }

    private static void assertVersion(JsonNode run, long expected) { if (run.path("runVersion").asLong() != expected) throw conflict("运行已更新，请刷新后再试。"); }
    static CommerceException error(int status, String code, String message) { return new CommerceException(status, code, message); }
    private static CommerceException conflict(String message) { return error(409, "VERSION_CONFLICT", message); }
    // Exception types remain deliberately independent of web adapters.
    private static String exCode(CommerceException ex) { return ex.code(); }
}
