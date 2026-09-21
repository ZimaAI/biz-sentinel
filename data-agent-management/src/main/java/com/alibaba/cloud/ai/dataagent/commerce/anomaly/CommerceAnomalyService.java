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

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.*;
import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.query.CommerceQueryService;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceAudit;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** Daily deterministic monitoring. LLMs have no role in deciding alerts. */
@Service
@Profile("commerce")
public class CommerceAnomalyService {
    private final CommerceStore store;
    private final CommerceQueryService queries;
    private final CommercePolicy policy;
    private final CommerceAudit audit;
    public CommerceAnomalyService(CommerceStore store,CommerceQueryService queries,CommercePolicy policy,CommerceAudit audit) {
        this.store=store;this.queries=queries;this.policy=policy;this.audit=audit;
    }
    public List<ObjectNode> rules(CommerceSubject subject) { return visible(subject,"rule"); }
    public List<ObjectNode> anomalies(CommerceSubject subject) { return visible(subject,"anomaly"); }
    public List<ObjectNode> monitors(CommerceSubject subject) { return visible(subject,"monitor-status"); }
    private List<ObjectNode> visible(CommerceSubject subject,String kind) {
        policy.assertCurrent(subject);
        return store.find(kind,subject.tenantId()).stream().filter(v->{try{policy.assertReadable(subject,v);return true;}catch(CommerceException e){return false;}}).toList();
    }
    public ObjectNode saveRule(CommerceSubject subject,String ruleId,JsonNode request) {
        policy.assertCurrent(subject);
        if(!subject.isAdmin()) throw new CommerceException(403,"ROLE_REQUIRED","只有租户管理员可以配置监控规则");
        JsonNode definition=ruleId==null?request:request.path("definition");
        fields(definition,"name","metricId","storeIds","direction","baseline","relativeThreshold","absoluteThreshold","unit","cooldownHours","enabled");
        for(String required:List.of("name","metricId","storeIds","direction","baseline","relativeThreshold","absoluteThreshold","unit","cooldownHours","enabled")) if(!definition.has(required)) invalid();
        if(!definition.path("storeIds").isArray()||!definition.path("enabled").isBoolean()||!definition.path("cooldownHours").isIntegralNumber()
                ||!definition.path("absoluteThreshold").isTextual()||(!definition.path("relativeThreshold").isNull()&&!definition.path("relativeThreshold").isTextual())) invalid();
        String metric=definition.path("metricId").asText(),unit=definition.path("unit").asText();
        if(!Set.of("paid_gmv","paid_orders","refund_amount","order_conversion_rate").contains(metric)) invalid();
        String expected=metric.equals("order_conversion_rate")?"PP":metric.equals("paid_orders")?"COUNT":"CNY_CENT";
        if(!expected.equals(unit)||!Set.of("UP","DOWN").contains(definition.path("direction").asText())
            ||!"SAME_WEEKDAY_MEDIAN_8".equals(definition.path("baseline").asText())
            ||definition.path("name").asText().isBlank()||definition.path("name").asText().length()>100
            ||definition.path("cooldownHours").asInt()<1||definition.path("cooldownHours").asInt()>168) invalid();
        if(decimal(definition.path("absoluteThreshold")).signum()<0) invalid();
        if(!definition.path("relativeThreshold").isNull() && decimal(definition.path("relativeThreshold")).signum()<0) invalid();
        Set<String> stores=CommercePolicy.strings(definition.path("storeIds"));
        if(stores.isEmpty()||!subject.storeIds().containsAll(stores)) throw new CommerceException(403,"STORE_FORBIDDEN","请求包含未授权店铺");
        if(ruleId==null) {
            String id=id("rule");
            return store.transaction(()-> {
                audit.record(subject,"RULE_CREATE",id);
                return store.create("rule",id,subject.tenantId(),object("ruleId",id,"version",1,"definition",definition,"metricManifestHash",queries.metricManifestHash(),"metricVersion",metricVersion(metric),"scope",object("tenantId",subject.tenantId(),"storeIds",new TreeSet<>(stores),"authzVersion",subject.authzVersion()),"subjectId",subject.subjectId(),"visibility","TEAM","createdAt",Instant.now().toString()));
            });
        }
        fields(request,"definition","expectedVersion");
        ObjectNode old=store.get("rule",ruleId);policy.assertReadable(subject,old);
        if(request.path("expectedVersion").asLong()!=old.path("version").asLong()) throw new CommerceException(409,"VERSION_CONFLICT","规则已更新，请刷新");
        return store.transaction(()-> {
            audit.record(subject,"RULE_UPDATE",ruleId);
            store.create("rule-history",ruleId+":"+old.path("version").asLong(),subject.tenantId(),old);
            return store.update("rule",ruleId,old.path("_revision").asLong(),v->{v.set("definition",definition);v.put("version",old.path("version").asLong()+1);v.put("metricVersion",metricVersion(metric));v.put("metricManifestHash",queries.metricManifestHash());v.put("subjectId",subject.subjectId());v.set("scope",object("tenantId",subject.tenantId(),"storeIds",new TreeSet<>(stores),"authzVersion",subject.authzVersion()));return v;});
        });
    }
    public ObjectNode acknowledge(CommerceSubject subject,String id) {
        if(!subject.canAnalyze()) throw new CommerceException(403,"ROLE_REQUIRED","当前角色只能查看异常");
        ObjectNode event=store.get("anomaly",id);policy.assertReadable(subject,event);
        if(!"OPEN".equals(event.path("status").asText())) return event;
        return store.transaction(()->{audit.record(subject,"ANOMALY_ACKNOWLEDGE",id);return store.update("anomaly",id,event.path("_revision").asLong(),v->{v.put("status","ACKNOWLEDGED");v.put("acknowledgedAt",Instant.now().toString());return v;});});
    }
    @Scheduled(fixedDelayString="${commerce.monitor-delay-ms:60000}",initialDelayString="${commerce.monitor-initial-delay-ms:15000}")
    public void tick() {
        for(ObjectNode rule:store.all("rule")) {
            if(!rule.path("definition").path("enabled").asBoolean()) continue;
            try {
                CommerceSubject subject=policy.subject(rule.path("scope").path("tenantId").asText(),rule.path("subjectId").asText());
                policy.assertReadable(subject,rule);
                List<ObjectNode> datasets=store.find("dataset",subject.tenantId()).stream().filter(d->"PUBLISHED".equals(d.path("status").asText()))
                        .filter(d->datasetStores(d).containsAll(CommercePolicy.strings(rule.path("scope").path("storeIds")))).toList();
                if(datasets.isEmpty()) continue;
                ObjectNode latest=datasets.stream().max(Comparator.comparing(d->d.path("publishedAt").asText())).orElseThrow();
                LocalDate day=LocalDate.parse(latest.path("coverage").path("endExclusive").asText()).minusDays(1);
                LocalDate previous=store.find("monitor-status",subject.tenantId()).stream()
                        .filter(status->sameRuleScope(status,rule)).map(status->LocalDate.parse(status.path("window").path("start").asText()))
                        .max(Comparator.naturalOrder()).orElse(day.minusDays(1));
                LocalDate next=previous.isBefore(day)?previous.plusDays(1):day;
                LocalDate first=LocalDate.parse(latest.path("coverage").path("start").asText());
                if(next.isBefore(first))next=first;
                // Catch up missing observations in business-date order so recovery cannot skip days.
                for(int count=0;!next.isAfter(day)&&count<31;next=next.plusDays(1),count++) evaluate(subject,rule,latest,next);
            } catch(CommerceException ignored) {
                // Per-rule failures do not stop other tenants; no missing source is converted to zero.
            } catch(Exception ex) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Monitor {} failed",rule.path("ruleId").asText(),ex); }
        }
    }
    public ObjectNode evaluate(CommerceSubject subject,ObjectNode rule,ObjectNode dataset,LocalDate day) {
        policy.assertReadable(subject,rule);
        String ruleId=rule.path("ruleId").asText(), datasetId=dataset.path("datasetVersionId").asText();
        int metricVersion=rule.path("metricVersion").asInt(1);
        String key=sha256(object("tenant",subject.tenantId(),"ruleId",ruleId,"ruleVersion",rule.path("version"),
                "scope",scopeHash(rule.path("scope")),"metricVersion",metricVersion,"metricManifestHash",queries.metricManifestHash(),"dataset",datasetId,"date",day.toString()).toString());
        ObjectNode prior=store.findOne("monitor-status",key);if(prior!=null) return prior;
        JsonNode definition=rule.path("definition");String metric=definition.path("metricId").asText();
        List<String> metrics=metric.equals("order_conversion_rate")?List.of(metric,"paid_orders","visitor_sessions"):List.of(metric);
        List<BigDecimal> values=new ArrayList<>();ArrayNode dates=array();
        ObjectNode current=queries.queryBound(subject,rule.path("scope"),datasetId,spec(metrics,day));
        BigDecimal actual=value(current.path("totals"),metric);
        String gate=actual==null || !"COMPLETE".equals(current.path("quality").path("status").asText())?"INCOMPLETE_DATA":null;
        if(gate==null&&metric.equals("order_conversion_rate")&&!sufficientSample(current.path("totals"))) gate="SMALL_SAMPLE";
        if(gate==null&&((rule.has("metricManifestHash")&&!rule.path("metricManifestHash").asText().equals(queries.metricManifestHash()))
                ||current.path("metricVersions").path(metric).asInt(metricVersion)!=metricVersion))gate="METRIC_VERSION_CHANGED";
        for(int week=1;week<=8;week++) {
            LocalDate reference=day.minusWeeks(week);
            if(reference.isBefore(LocalDate.parse(dataset.path("coverage").path("start").asText()))) continue;
            ObjectNode result=queries.queryBound(subject,rule.path("scope"),datasetId,spec(metrics,reference));
            BigDecimal number=value(result.path("totals"),metric);
            if(number!=null&&"COMPLETE".equals(result.path("quality").path("status").asText())
                    &&result.path("metricVersions").path(metric).asInt(metricVersion)==metricVersion
                    &&(!metric.equals("order_conversion_rate")||sufficientSample(result.path("totals")))) {values.add(number);dates.add(reference.toString());}
        }
        if(gate==null&&values.size()<4) gate="INSUFFICIENT_HISTORY";
        BigDecimal baseline=values.isEmpty()?null:median(values);
        boolean triggered=gate==null&&threshold(actual,baseline,definition);
        ObjectNode status=object("monitorId",key,"ruleId",ruleId,"ruleVersion",rule.path("version"),"metricVersion",metricVersion,"scope",rule.path("scope"),"subjectId",subject.subjectId(),"visibility","TEAM","datasetVersionId",datasetId,"window",range(day),"status",gate==null?(triggered?"TRIGGERED":"NORMAL"):gate,"referenceDates",dates,"sampleCount",values.size(),"createdAt",Instant.now().toString());
        return store.transaction(()-> {
            policy.assertReadable(subject,rule);
            // Reserve the unique evaluation first; a racing scheduler cannot commit duplicate alerts.
            ObjectNode reserved=store.create("monitor-status",key,subject.tenantId(),status);
            if(triggered) {
                List<ObjectNode> related=store.find("anomaly",subject.tenantId()).stream().filter(e->sameRuleScope(e,rule)&&e.path("metricVersion").asInt(1)==metricVersion).toList();
                ObjectNode sameDay=related.stream().filter(e->e.path("window").path("start").asText().equals(day.toString())).findFirst().orElse(null);
                ObjectNode active=related.stream().filter(e->Set.of("OPEN","ACKNOWLEDGED").contains(e.path("status").asText())&&!e.path("window").path("start").asText().equals(day.toString())).findFirst().orElse(null);
                String eventId=id("anomaly");
                String severity="CRITICAL";
                String reason=(definition.hasNonNull("relativeThreshold")?"达到绝对及相对阈值":"达到绝对变化阈值")+"；参考最近八个同星期完整日的中位数";
                ObjectNode event=object("anomalyId",eventId,"ruleId",ruleId,"ruleVersion",rule.path("version"),"status","OPEN","severity",severity,"scope",rule.path("scope"),"subjectId",subject.subjectId(),"visibility","TEAM","metricId",metric,"window",range(day),"referenceDates",dates,"sampleCount",values.size(),"current",cell(metric,actual,metric.equals("order_conversion_rate")?"RATIO":definition.path("unit").asText()),"baseline",cell(metric,baseline,metric.equals("order_conversion_rate")?"RATIO":definition.path("unit").asText()),"datasetVersionId",datasetId,"metricVersion",metricVersion,"reason",reason,"algorithmVersion","weekday-median-v1","createdAt",Instant.now().toString(),"normalDays",0,"scoreUnavailable",mad(values,baseline).signum()==0?"MAD_ZERO":null);
                if(sameDay!=null) {
                    event.put("supersedesEventId",sameDay.path("anomalyId").asText());
                    store.update("anomaly",sameDay.path("anomalyId").asText(),sameDay.path("_revision").asLong(),v->{v.put("status","SUPPRESSED");v.put("supersededByEventId",eventId);return v;});
                }
                if(active!=null && Instant.parse(active.path("createdAt").asText()).plusSeconds(definition.path("cooldownHours").asLong()*3600).isAfter(Instant.now())) {event.put("status","SUPPRESSED");event.put("mergedInto",active.path("anomalyId").asText());}
                for(ObjectNode old:related) if(Set.of("OPEN","ACKNOWLEDGED").contains(old.path("status").asText())
                        &&!old.path("window").path("start").asText().equals(day.toString())) {
                    LocalDate last=LocalDate.parse(old.path("lastObservedDay").asText(old.path("window").path("start").asText()));
                    if(day.isAfter(last))store.update("anomaly",old.path("anomalyId").asText(),old.path("_revision").asLong(),v->{v.put("normalDays",0);v.put("lastObservedDay",day.toString());return v;});
                }
                store.create("anomaly",eventId,subject.tenantId(),event);status.put("anomalyId",eventId);
            } else if("NORMAL".equals(status.path("status").asText())) {
                for(ObjectNode event:store.find("anomaly",subject.tenantId())) {
                    if(!sameRuleScope(event,rule)||event.path("metricVersion").asInt(1)!=metricVersion||!Set.of("OPEN","ACKNOWLEDGED").contains(event.path("status").asText())) continue;
                    if(day.toString().equals(event.path("window").path("start").asText())&&!datasetId.equals(event.path("datasetVersionId").asText())) {
                        store.update("anomaly",event.path("anomalyId").asText(),event.path("_revision").asLong(),v->{v.put("status","SUPPRESSED");v.put("supersededByDatasetVersionId",datasetId);return v;});
                        continue;
                    }
                    LocalDate last=LocalDate.parse(event.path("lastObservedDay").asText(event.path("window").path("start").asText()));
                    if(!day.isAfter(last)) continue;
                    int normals=day.equals(last.plusDays(1))?event.path("normalDays").asInt()+1:1;
                    store.update("anomaly",event.path("anomalyId").asText(),event.path("_revision").asLong(),v->{v.put("normalDays",normals);v.put("lastObservedDay",day.toString());if(normals>=2)v.put("status","RESOLVED");return v;});
                }
            }
            return store.update("monitor-status",key,reserved.path("_revision").asLong(),v->status.deepCopy());
        });
    }
    private int metricVersion(String metric) {
        for(JsonNode definition:queries.metrics())if(metric.equals(definition.path("metricId").asText())&&"PUBLISHED".equals(definition.path("state").asText()))return definition.path("version").asInt();
        throw new CommerceException(422,"METRIC_UNAVAILABLE","指标尚未发布或已停用");
    }
    private static boolean sufficientSample(JsonNode row) {
        BigDecimal orders=value(row,"paid_orders"),sessions=value(row,"visitor_sessions");
        return orders!=null&&sessions!=null&&orders.compareTo(new BigDecimal("30"))>=0&&sessions.compareTo(new BigDecimal("1000"))>=0;
    }
    private static String scopeHash(JsonNode scope) { return sha256(object("tenantId",scope.path("tenantId"),"storeIds",new TreeSet<>(CommercePolicy.strings(scope.path("storeIds")))).toString()); }
    private static boolean sameRuleScope(JsonNode event,JsonNode rule) {
        return rule.path("ruleId").asText().equals(event.path("ruleId").asText())&&rule.path("version").asLong()==event.path("ruleVersion").asLong()
                &&scopeHash(rule.path("scope")).equals(scopeHash(event.path("scope")));
    }
    private static Set<String> datasetStores(JsonNode dataset) {
        Set<String> stores=new TreeSet<>();for(JsonNode item:dataset.path("stores"))stores.add(item.path("storeId").asText());
        if(stores.isEmpty())stores.addAll(CommercePolicy.strings(dataset.path("scope").path("storeIds")));return stores;
    }
    static ObjectNode spec(List<String> metrics,LocalDate day) {return object("metricIds",metrics,"dimensions",List.of(),"dateRange",range(day),"comparison","NONE","filters",List.of(),"limit",1000);}
    static ObjectNode range(LocalDate day) {return object("start",day.toString(),"endExclusive",day.plusDays(1).toString());}
    static ObjectNode cell(String field,BigDecimal value,String unit) {return object("field",field,"value",value==null?null:value.toPlainString(),"unit",unit);}
    static BigDecimal value(JsonNode row,String metric) {for(JsonNode cell:row.path("cells"))if(metric.equals(cell.path("field").asText()))return cell.path("value").isNull()?null:decimal(cell.path("value"));return null;}
    public static BigDecimal median(List<BigDecimal> values) {List<BigDecimal> sorted=values.stream().sorted().toList();int n=sorted.size();return n%2==1?sorted.get(n/2):sorted.get(n/2-1).add(sorted.get(n/2)).divide(BigDecimal.valueOf(2));}
    static BigDecimal mad(List<BigDecimal> values,BigDecimal median) {return median(values.stream().map(v->v.subtract(median).abs()).toList());}
    public static boolean threshold(BigDecimal current,BigDecimal baseline,JsonNode rule) {
        if(current==null||baseline==null)return false;
        BigDecimal delta="DOWN".equals(rule.path("direction").asText())?baseline.subtract(current):current.subtract(baseline);
        if(delta.signum()<=0)return false;
        BigDecimal absolute=delta.multiply("PP".equals(rule.path("unit").asText())?BigDecimal.valueOf(100):BigDecimal.ONE);
        if(absolute.compareTo(decimal(rule.path("absoluteThreshold")))<0)return false;
        if(rule.path("relativeThreshold").isNull())return true;
        return baseline.signum()!=0&&delta.divide(baseline.abs(),12,RoundingMode.HALF_UP).compareTo(decimal(rule.path("relativeThreshold")))>=0;
    }
    static BigDecimal decimal(JsonNode value) {try{return new BigDecimal(value.asText());}catch(Exception e){throw new CommerceException(422,"INVALID_THRESHOLD","阈值必须是十进制数字");}}
    private static void invalid() {throw new CommerceException(422,"INVALID_RULE","规则配置无效，请检查指标、单位和阈值");}
}
