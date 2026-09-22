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
import com.alibaba.cloud.ai.dataagent.commerce.anomaly.CommerceAnomalyService;
import com.alibaba.cloud.ai.dataagent.commerce.ingestion.CommerceIngestionService;
import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.query.CommerceQueryService;
import com.alibaba.cloud.ai.dataagent.commerce.run.CommerceRunService;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceAudit;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@Profile("commerce")
@RequestMapping("/api/commerce/v1")
public class CommerceController {
    private final CommerceStore store;
    private final CommerceQueryService queries;
    private final CommerceRunService runs;
    private final CommerceIngestionService ingestion;
    private final CommerceAnomalyService anomalies;
    private final CommercePolicy policy;
    private final CommerceAudit audit;
    public CommerceController(CommerceStore store,CommerceQueryService queries,CommerceRunService runs,CommerceIngestionService ingestion,CommerceAnomalyService anomalies,CommercePolicy policy,CommerceAudit audit) {
        this.store=store;this.queries=queries;this.runs=runs;this.ingestion=ingestion;this.anomalies=anomalies;this.policy=policy;this.audit=audit;
    }
    private Mono<ObjectNode> result(ServerWebExchange ex,Supplier<?> body) {return Mono.fromCallable(()->envelope(ex,body.get())).subscribeOn(Schedulers.boundedElastic());}
    @GetMapping("/me") public Mono<ObjectNode> me(ServerWebExchange ex) {return result(ex,()-> {
        CommerceSubject subject=subject(ex);var stores=array();
        List<ObjectNode> datasets=store.find("dataset",subject.tenantId());
        Set<String> seen=new java.util.HashSet<>();
        for(JsonNode data:datasets)for(JsonNode s:data.path("stores")) {String id=s.path("storeId").asText();if(subject.storeIds().contains(id)&&seen.add(id))stores.add(s);}
        JsonNode member=store.get("member",subject.tenantId()+":"+subject.subjectId());
        return object("subjectId",subject.subjectId(),"tenantId",subject.tenantId(),"displayName",member.path("displayName").asText(subject.subjectId()),"roles",subject.roles(),"authzVersion",subject.authzVersion(),"stores",stores,"guest",subject.guest());
    });}
    @GetMapping("/metrics") public Mono<ObjectNode> metrics(ServerWebExchange ex) {return result(ex,queries::metrics);}
    @GetMapping("/overview") public Mono<ObjectNode> overview(@RequestParam List<String> storeIds,@RequestParam String start,@RequestParam String endExclusive,@RequestParam String comparison,ServerWebExchange ex) {return result(ex,()->queries.overview(subject(ex),storeIds,start,endExclusive,comparison));}
    @PostMapping("/queries") public Mono<ObjectNode> query(@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {CommerceSubject current=subject(ex);policy.assertWritable(current);fields(request,"requestedStoreIds","spec");return queries.query(current,new ArrayList<>(CommercePolicy.strings(request.path("requestedStoreIds"))),request.path("spec"));});}
    @PostMapping("/runs") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Mono<ObjectNode> createRun(@RequestBody JsonNode request,@RequestHeader("Idempotency-Key") String key,ServerWebExchange ex) {return result(ex,()-> {if(!subject(ex).canAnalyze())throw new CommerceException(403,"ROLE_REQUIRED","当前角色只能查看结果");return runs.create(subject(ex),request,key);});}
    @GetMapping("/runs") public Mono<ObjectNode> runs(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,ServerWebExchange ex) {return result(ex,()->page(runs.list(subject(ex)),limit,cursor));}
    @GetMapping("/runs/{id}") public Mono<ObjectNode> run(@PathVariable String id,ServerWebExchange ex) {return result(ex,()->runs.get(subject(ex),id));}
    @PostMapping("/runs/{id}/approval") public Mono<ObjectNode> approve(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()->runs.approve(subject(ex),id,request));}
    @PostMapping("/runs/{id}/cancel") @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Mono<ObjectNode> cancel(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {fields(request,"expectedRunVersion");return runs.cancel(subject(ex),id,request.path("expectedRunVersion").asLong());});}
    @GetMapping(value="/runs/{id}/events",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<ObjectNode>> events(@PathVariable String id,@RequestParam(defaultValue="0") long after,@RequestHeader(value="Last-Event-ID",required=false) String lastId,ServerWebExchange ex) {
        AtomicLong cursor=new AtomicLong(lastId==null?after:Long.parseLong(lastId));
        return Mono.fromCallable(()->runs.events(subject(ex),id,cursor.get())).subscribeOn(Schedulers.boundedElastic()).flatMapMany(initial-> {
            Flux<ServerSentEvent<ObjectNode>> data=Flux.concat(Mono.just(initial),Flux.interval(Duration.ofMillis(750)).concatMap(t->Mono.fromCallable(()->runs.events(subject(ex),id,cursor.get())).subscribeOn(Schedulers.boundedElastic())))
                .concatMapIterable(v->v).filter(event->event.path("sequence").asLong()>cursor.get())
                .map(event->{cursor.set(event.path("sequence").asLong());return ServerSentEvent.<ObjectNode>builder(event).id(Long.toString(cursor.get())).event(event.path("type").asText()).build();});
            return Flux.merge(data,Flux.interval(Duration.ofSeconds(15)).map(n->ServerSentEvent.<ObjectNode>builder().comment("heartbeat").build()))
                .takeUntil(event->Set.of("run.completed","run.failed","run.cancelled","access.revoked").contains(event.event()==null?"":event.event()));
        });
    }
    @GetMapping("/evidence/{id}") public Mono<ObjectNode> evidence(@PathVariable String id,ServerWebExchange ex) {return result(ex,()->queries.evidence(subject(ex),id));}
    @GetMapping("/reports") public Mono<ObjectNode> reports(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,ServerWebExchange ex) {return result(ex,()->page(runs.reports(subject(ex)),limit,cursor));}
    @GetMapping("/reports/{id}") public Mono<ObjectNode> report(@PathVariable String id,ServerWebExchange ex) {return result(ex,()->runs.report(subject(ex),id));}
    @GetMapping("/reports/{id}/export") public Mono<ResponseEntity<String>> export(@PathVariable String id,ServerWebExchange ex) {return Mono.fromCallable(()-> {CommerceSubject current=subject(ex);policy.assertWritable(current);audit.record(current,"REPORT_EXPORT",id);return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/markdown;charset=UTF-8")).header("Content-Disposition","attachment; filename=commerce-report.md").body(runs.export(current,id));}).subscribeOn(Schedulers.boundedElastic());}
    @PatchMapping("/reports/{id}/visibility") public Mono<ObjectNode> share(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {
        CommerceSubject current=subject(ex);policy.assertWritable(current);fields(request,"visibility");String visibility=request.path("visibility").asText();if(!Set.of("PRIVATE","TEAM").contains(visibility))throw new CommerceException(422,"INVALID_VISIBILITY","仅支持私有或租户团队共享");
        ObjectNode report=store.get("report",id);policy.assertReadable(current,report);
        if(!current.subjectId().equals(report.path("subjectId").asText())&&!current.isAdmin())throw new CommerceException(403,"OWNER_REQUIRED","只有报告所有者可以修改共享范围");
        return store.transaction(()-> {
            audit.record(current,"REPORT_VISIBILITY",id);
            Set<String> references=new java.util.HashSet<>();
            for(JsonNode claim:report.path("claims"))for(JsonNode ref:claim.path("evidenceRefs"))references.add(ref.asText());
            var pending=new java.util.ArrayDeque<>(references);
            while(!pending.isEmpty()) {String reference=pending.remove();ObjectNode evidence=store.get("evidence",reference);String source=evidence.path("sourceEvidenceId").asText();if(!source.isBlank()&&references.add(source))pending.add(source);store.update("evidence",reference,evidence.path("_revision").asLong(),v->{v.put("visibility",visibility);return v;});}
            return store.update("report",id,report.path("_revision").asLong(),v->{v.put("visibility",visibility);return v;});
        });
    });}
    @GetMapping("/anomaly-rules") public Mono<ObjectNode> rules(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,ServerWebExchange ex) {return result(ex,()->page(anomalies.rules(subject(ex)),limit,cursor));}
    @PostMapping("/anomaly-rules") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Mono<ObjectNode> createRule(@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {CommerceSubject current=subject(ex);policy.assertWritable(current);return anomalies.saveRule(current,null,request);});}
    @PatchMapping("/anomaly-rules/{id}") public Mono<ObjectNode> editRule(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {CommerceSubject current=subject(ex);policy.assertWritable(current);return anomalies.saveRule(current,id,request);});}
    @GetMapping("/anomalies") public Mono<ObjectNode> anomalies(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,ServerWebExchange ex) {return result(ex,()->page(anomalies.anomalies(subject(ex)),limit,cursor));}
    @PostMapping("/anomalies/{id}/acknowledge") public Mono<ObjectNode> acknowledge(@PathVariable String id,ServerWebExchange ex) {return result(ex,()-> {CommerceSubject current=subject(ex);policy.assertWritable(current);return anomalies.acknowledge(current,id);});}
    @GetMapping("/monitor-status") public Mono<ObjectNode> monitors(ServerWebExchange ex) {return result(ex,()->page(anomalies.monitors(subject(ex)),100,null));}
    @GetMapping("/datasets") public Mono<ObjectNode> datasets(@RequestParam(defaultValue="20") int limit,@RequestParam(required=false) String cursor,ServerWebExchange ex) {return result(ex,()->page(ingestion.datasets(subject(ex)),limit,cursor));}
    @PostMapping(value="/ingestions",consumes=MediaType.MULTIPART_FORM_DATA_VALUE) @ResponseStatus(org.springframework.http.HttpStatus.ACCEPTED)
    public Mono<ObjectNode> upload(@RequestPart("file") FilePart file,@RequestHeader("Idempotency-Key") String key,ServerWebExchange ex) {
        if(!subject(ex).isAdmin())return Mono.error(new CommerceException(403,"ROLE_REQUIRED","只有管理员可以导入数据"));
        return DataBufferUtils.join(file.content(),32*1024*1024).flatMap(buffer->{byte[] bytes=new byte[buffer.readableByteCount()];buffer.read(bytes);DataBufferUtils.release(buffer);return result(ex,()->ingestion.upload(subject(ex),bytes,key));});
    }
    @GetMapping("/ingestions/{id}") public Mono<ObjectNode> ingestion(@PathVariable String id,ServerWebExchange ex) {return result(ex,()->ingestion.get(subject(ex),id));}
    @PostMapping("/ingestions/{id}/publish") public Mono<ObjectNode> publish(@PathVariable String id,@RequestBody JsonNode request,ServerWebExchange ex) {return result(ex,()-> {fields(request,"expectedVersion");return ingestion.publish(subject(ex),id,request.path("expectedVersion").asLong());});}
    static ObjectNode page(List<ObjectNode> items,int limit,String cursor) {
        if(limit<1||limit>100)throw new CommerceException(422,"INVALID_PAGE","每页数量须在 1–100 之间");
        int offset=0;
        if(cursor!=null) {try{offset=Integer.parseInt(new String(Base64.getUrlDecoder().decode(cursor),StandardCharsets.UTF_8));}catch(Exception e){throw new CommerceException(422,"INVALID_CURSOR","分页游标无效");}}
        if(offset<0||offset>items.size())throw new CommerceException(422,"INVALID_CURSOR","分页游标无效");
        int end=Math.min(offset+limit,items.size());
        return object("items",items.subList(offset,end),"nextCursor",end<items.size()?Base64.getUrlEncoder().withoutPadding().encodeToString(Integer.toString(end).getBytes(StandardCharsets.UTF_8)):null);
    }
}
