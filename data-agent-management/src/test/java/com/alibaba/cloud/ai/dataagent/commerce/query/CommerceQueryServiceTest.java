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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.MAPPER;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CommerceQueryServiceTest {
    private CommerceQueryService service;
    private CommerceStore documents;
    private JdbcTemplate jdbc;
    private CommerceSubject subject;
    private JsonNode golden;
    private static final String DATASET = "demo_20260921_v1";

    @BeforeAll
    void setup() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:query_" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        documents = new CommerceStore(ds);
        CommercePolicy policy = new CommercePolicy(documents);
        subject = new CommerceSubject("t_demo", "ops", Set.of("s1", "s2", "s3"), Set.of("OPS_MANAGER"), 1);
        documents.create("member", "t_demo:ops", "t_demo", object("enabled", true, "storeIds", subject.storeIds(), "roles", subject.roles(), "authzVersion", 1));
        Path reference = Files.exists(Path.of("../docs/reference/commerce-lens"))
                ? Path.of("../docs/reference/commerce-lens") : Path.of("docs/reference/commerce-lens");
        Path fixtures = reference.resolve("fixtures/business/t_demo").toAbsolutePath();
        golden = MAPPER.readTree(reference.resolve("fixtures/golden.json").toFile());
        ObjectNode manifest = (ObjectNode) MAPPER.readTree(fixtures.resolve("manifest.json").toFile());
        manifest.put("status", "PUBLISHED");
        manifest.set("stores", MAPPER.valueToTree(List.of(object("storeId", "s1", "name", "旗舰店"), object("storeId", "s2", "name", "生活店"), object("storeId", "s3", "name", "精选店"))));
        manifest.set("scope", object("tenantId", "t_demo", "storeIds", subject.storeIds(), "authzVersion", 1));
        manifest.put("subjectId", "ops");
        documents.create("dataset", DATASET, "t_demo", manifest);
        createCsvTable(fixtures, "payments", "cl_payment", "tenant_id,dataset_version_id,store_id,payment_id,order_id,CAST(paid_at_utc AS TIMESTAMP) AS paid_at_utc,CAST(amount_cents AS BIGINT) AS amount_cents", "");
        createCsvTable(fixtures, "refunds", "cl_refund", "tenant_id,dataset_version_id,store_id,refund_id,order_id,CAST(succeeded_at_utc AS TIMESTAMP) AS succeeded_at_utc,CAST(amount_cents AS BIGINT) AS amount_cents", "");
        createCsvTable(fixtures, "order_items", "cl_order_item", "tenant_id,dataset_version_id,store_id,order_id,item_id,product_id,CAST(quantity AS INTEGER) AS quantity,CAST(allocated_paid_cents AS BIGINT) AS allocated_paid_cents", "");
        createCsvTable(fixtures, "products", "cl_product", "*", "");
        createCsvTable(fixtures, "traffic_daily", "cl_traffic_daily", "tenant_id,dataset_version_id,store_id,CAST(biz_date AS DATE) AS biz_date,CAST(visitor_sessions AS BIGINT) AS visitor_sessions,quality_status", "");
        createCsvTable(fixtures, "inventory_daily", "cl_inventory_daily", "tenant_id,dataset_version_id,store_id,product_id,CAST(biz_date AS DATE) AS biz_date,CAST(closing_stock AS BIGINT) AS closing_stock,CAST(stockout_minutes AS INTEGER) AS stockout_minutes,quality_status", "");
        jdbc.execute("CREATE INDEX payment_scope ON cl_payment(tenant_id,dataset_version_id,store_id,paid_at_utc)");
        jdbc.execute("CREATE INDEX payment_order ON cl_payment(tenant_id,dataset_version_id,store_id,order_id)");
        jdbc.execute("CREATE INDEX item_order ON cl_order_item(tenant_id,dataset_version_id,store_id,order_id)");
        jdbc.execute("CREATE INDEX refund_scope ON cl_refund(tenant_id,dataset_version_id,store_id,succeeded_at_utc)");
        service = new CommerceQueryService(new NamedParameterJdbcTemplate(jdbc), documents, policy);
    }

    private void createCsvTable(Path fixtures, String file, String table, String select, String where) {
        String path = fixtures.resolve(file + ".csv").toString().replace('\\', '/').replace("'", "''");
        jdbc.execute("CREATE TABLE " + table + " AS SELECT " + select + " FROM CSVREAD('" + path + "') " + where);
    }

    private ObjectNode spec(List<String> metrics, List<String> dimensions) {
        return object("metricIds", metrics, "dimensions", dimensions,
                "dateRange", object("start", "2026-09-20", "endExclusive", "2026-09-21"),
                "comparison", "PREVIOUS_WEEK_SAME_DAYS", "filters", List.of(), "limit", 100);
    }

    private JsonNode cell(JsonNode row, String metric) {
        for (JsonNode cell : row.path("cells")) if (metric.equals(cell.path("field").asText())) return cell;
        throw new AssertionError("Missing cell " + metric);
    }

    private JsonNode row(JsonNode result, String store, String sku) {
        for (JsonNode row : result.path("rows")) if (store.equals(row.path("dimensions").path("store").asText())
                && (sku == null || sku.equals(row.path("dimensions").path("sku").asText()))) return row;
        throw new AssertionError("Missing row " + store + "/" + sku);
    }

    @Test
    void fullGoldenMetricsAndComparisonsMatchRawFixtures() {
        ObjectNode result = service.query(subject, List.of("s1", "s2", "s3"), spec(CommerceQuerySpec.METRICS, List.of("store")));
        JsonNode total = result.path("totals");
        assertThat(cell(total, "paid_gmv").path("value").asText()).isEqualTo(golden.at("/current/paidGmvCents").asText());
        assertThat(cell(total, "paid_orders").path("value").asText()).isEqualTo(golden.at("/current/paidOrders").asText());
        assertThat(cell(total, "refund_amount").path("value").asText()).isEqualTo(golden.at("/current/refundCents").asText());
        assertThat(cell(total, "visitor_sessions").path("value").asText()).isEqualTo(golden.at("/current/sessions").asText());
        assertThat(cell(total, "net_receipts").path("value").asText()).isEqualTo(golden.at("/expected/currentNetReceiptsCents").asText());
        assertThat(cell(total, "paid_gmv_baseline").path("value").asText()).isEqualTo(golden.at("/baseline/paidGmvCents").asText());
        assertThat(cell(total, "paid_gmv_delta").path("value").asText()).isEqualTo(golden.at("/expected/gmvDeltaCents").asText());
        assertThat(cell(total, "order_conversion_rate").path("value").asText()).isEqualTo("0.04");
        assertThat(cell(total, "order_conversion_rate_baseline").path("value").asText()).isEqualTo("0.0448");
        assertThat(cell(total, "order_conversion_rate_delta").path("value").asText()).isEqualTo("-0.48");
        assertThat(cell(total, "order_conversion_rate_delta").path("unit").asText()).isEqualTo("PP");
        assertThat(cell(row(result, "s1", null), "paid_gmv_delta").path("value").asText()).isEqualTo("-10500000");
        assertThat(result.path("quality").path("status").asText()).isEqualTo("COMPLETE");
        ObjectNode evidence = service.evidence(subject, result.path("evidenceId").asText());
        assertThat(evidence.path("result").toString()).isEqualTo(result.toString());
        assertThat(evidence.path("sqlTemplate").asText()).contains("tenant_id=:tenant", "dataset_version_id=:dataset", "store_id IN (:stores)");
        assertThat(evidence.has("subjectId")).isFalse();
    }

    @Test
    void skuUsesAllocatedItemsAndExactTotalsSurvivePresentationLimit() {
        ObjectNode spec = spec(List.of("paid_gmv"), List.of("store", "sku"));
        ObjectNode all = service.query(subject, null, spec);
        assertThat(cell(row(all, "s1", "p1"), "paid_gmv_delta").path("value").asText()).isEqualTo("-9900000");
        assertThat(cell(all.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
        spec.put("limit", 1);
        ObjectNode limited = service.query(subject, null, spec);
        assertThat(limited.path("truncated").asBoolean()).isTrue();
        assertThat(limited.path("rows")).hasSize(1);
        assertThat(limited.path("rowCount").asInt()).isEqualTo(6);
        assertThat(cell(limited.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
    }

    @Test
    void missingTrafficOnlyNullsDependentMetrics() {
        jdbc.update("UPDATE cl_traffic_daily SET quality_status='PARTIAL' WHERE tenant_id='t_demo' AND store_id='s1' AND biz_date='2026-09-20'");
        try {
            ObjectNode result = service.query(subject, null, spec(CommerceQuerySpec.METRICS, List.of("store")));
            assertThat(cell(result.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
            assertThat(cell(result.path("totals"), "visitor_sessions").path("value").isNull()).isTrue();
            assertThat(cell(result.path("totals"), "order_conversion_rate").path("reason").asText()).isEqualTo("MISSING_DATA");
            assertThat(result.path("quality").path("status").asText()).isEqualTo("PARTIAL");
            assertThat(cell(row(result, "s2", null), "visitor_sessions").path("value").isTextual()).isTrue();
        }
        finally { jdbc.update("UPDATE cl_traffic_daily SET quality_status='COMPLETE' WHERE tenant_id='t_demo' AND store_id='s1' AND biz_date='2026-09-20'"); }
    }

    @Test
    void zeroSessionsIsZeroDenominatorAndNotMissingData() {
        long sessions = jdbc.queryForObject("SELECT visitor_sessions FROM cl_traffic_daily WHERE tenant_id='t_demo' AND store_id='s3' AND biz_date='2026-09-20'", Long.class);
        jdbc.update("UPDATE cl_traffic_daily SET visitor_sessions=0 WHERE tenant_id='t_demo' AND store_id='s3' AND biz_date='2026-09-20'");
        try {
            ObjectNode result = service.query(subject, List.of("s3"), spec(List.of("visitor_sessions", "order_conversion_rate"), List.of()));
            assertThat(cell(result.path("totals"), "visitor_sessions").path("value").asText()).isEqualTo("0");
            assertThat(cell(result.path("totals"), "order_conversion_rate").path("reason").asText()).isEqualTo("ZERO_DENOMINATOR");
            assertThat(result.path("quality").path("status").asText()).isEqualTo("COMPLETE");
        }
        finally { jdbc.update("UPDATE cl_traffic_daily SET visitor_sessions=? WHERE tenant_id='t_demo' AND store_id='s3' AND biz_date='2026-09-20'", sessions); }
    }

    @Test
    void crossTenantAndOtherDatasetFactsNeverContaminateAggregation() {
        jdbc.update("INSERT INTO cl_payment VALUES('t_other',?,'s1','foreign_payment','same_order',TIMESTAMP '2026-09-20 02:00:00',999999999)", DATASET);
        jdbc.update("INSERT INTO cl_payment VALUES('t_demo','unpublished','s1','foreign_version','same_order',TIMESTAMP '2026-09-20 02:00:00',888888888)");
        try {
            ObjectNode result = service.query(subject, null, spec(List.of("paid_gmv"), List.of()));
            assertThat(cell(result.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
        }
        finally { jdbc.update("DELETE FROM cl_payment WHERE payment_id IN ('foreign_payment','foreign_version')"); }
    }

    @Test
    void businessDayIsShanghaiAndUsesHalfOpenUtcBoundaries() {
        jdbc.update("INSERT INTO cl_payment VALUES('t_demo',?,'s1','start_boundary','boundary_start',TIMESTAMP '2026-09-19 16:00:00',123)", DATASET);
        jdbc.update("INSERT INTO cl_payment VALUES('t_demo',?,'s1','end_boundary','boundary_end',TIMESTAMP '2026-09-20 16:00:00',999)", DATASET);
        try {
            ObjectNode result = service.query(subject, null, spec(List.of("paid_gmv"), List.of("day")));
            assertThat(result.path("rows")).hasSize(1);
            assertThat(result.path("rows").get(0).path("dimensions").path("day").asText()).isEqualTo("2026-09-20");
            assertThat(cell(result.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620123");
        }
        finally { jdbc.update("DELETE FROM cl_payment WHERE payment_id IN ('start_boundary','end_boundary')"); }
    }

    @Test
    void multipleItemsAndRefundsDoNotMultiplyPaidFacts() {
        String order = jdbc.queryForObject("SELECT order_id FROM cl_payment WHERE store_id='s1' AND paid_at_utc>=TIMESTAMP '2026-09-20 00:00:00' AND paid_at_utc<TIMESTAMP '2026-09-20 15:00:00' LIMIT 1", String.class);
        var item = jdbc.queryForMap("SELECT * FROM cl_order_item WHERE store_id='s1' AND order_id=?", order);
        long allocation = ((Number) item.get("allocated_paid_cents")).longValue();
        jdbc.update("UPDATE cl_order_item SET allocated_paid_cents=? WHERE store_id='s1' AND order_id=?", allocation - 100, order);
        jdbc.update("INSERT INTO cl_order_item VALUES('t_demo',?,'s1',?,'additional_item','p2',1,100)", DATASET, order);
        jdbc.update("INSERT INTO cl_refund VALUES('t_demo',?,'s1','additional_refund_a',?,TIMESTAMP '2026-09-20 02:00:00',100)", DATASET, order);
        jdbc.update("INSERT INTO cl_refund VALUES('t_demo',?,'s1','additional_refund_b',?,TIMESTAMP '2026-09-20 02:00:00',100)", DATASET, order);
        try {
            ObjectNode result = service.query(subject, null, spec(CommerceQuerySpec.METRICS, List.of()));
            assertThat(cell(result.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
            assertThat(cell(result.path("totals"), "paid_orders").path("value").asText()).isEqualTo("1842");
            assertThat(cell(result.path("totals"), "refund_amount").path("value").asText()).isEqualTo("2686200");
            ObjectNode sku = service.query(subject, null, spec(List.of("paid_gmv"), List.of("store", "sku")));
            assertThat(cell(sku.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("48620000");
        }
        finally {
            jdbc.update("DELETE FROM cl_order_item WHERE item_id='additional_item'");
            jdbc.update("UPDATE cl_order_item SET allocated_paid_cents=? WHERE store_id='s1' AND order_id=?", allocation, order);
            jdbc.update("DELETE FROM cl_refund WHERE refund_id IN ('additional_refund_a','additional_refund_b')");
        }
    }

    @Test
    void rejectsSqlFieldsUnsupportedCapabilitiesAndMixedUnauthorizedStores() {
        ObjectNode invalid = spec(List.of("paid_gmv"), List.of("store"));
        invalid.put("sql", "SELECT * FROM cl_payment");
        assertThatThrownBy(() -> service.query(subject, null, invalid)).isInstanceOf(CommerceException.class);
        assertThatThrownBy(() -> service.query(subject, null, spec(List.of("refund_amount"), List.of("store", "sku"))))
                .isInstanceOf(CommerceException.class);
        assertThatThrownBy(() -> service.query(subject, List.of("s1", "forbidden"), spec(List.of("paid_gmv"), List.of())))
                .isInstanceOf(CommerceException.class).extracting("status").isEqualTo(403);
        ObjectNode injected = spec(List.of("paid_gmv"), List.of("store"));
        injected.set("filters", MAPPER.valueToTree(List.of(object("field", "productId", "operator", "IN", "values", List.of("p1') OR 1=1 --")))));
        ObjectNode result = service.query(subject, null, injected);
        assertThat(cell(result.path("totals"), "paid_gmv").path("value").asText()).isEqualTo("0");
    }

    @Test
    void evidenceRechecksCurrentAuthorizationAndDatasetMustBePublished() {
        ObjectNode result = service.query(subject, List.of("s1"), spec(List.of("paid_gmv"), List.of()));
        ObjectNode member = documents.get("member", "t_demo:ops");
        documents.update("member", "t_demo:ops", member.path("_revision").asLong(), current -> current.put("authzVersion", 2));
        try {
            assertThatThrownBy(() -> service.evidence(subject, result.path("evidenceId").asText())).isInstanceOf(CommerceException.class);
        }
        finally {
            ObjectNode updated = documents.get("member", "t_demo:ops");
            documents.update("member", "t_demo:ops", updated.path("_revision").asLong(), current -> current.put("authzVersion", 1));
        }
        ObjectNode unpublished = documents.get("dataset", DATASET).deepCopy();
        unpublished.put("status", "DRAFT"); unpublished.put("datasetVersionId", "draft_snapshot");
        documents.create("dataset", "draft_snapshot", "t_demo", unpublished);
        assertThatThrownBy(() -> service.queryBound(subject, service.scope(subject, null), "draft_snapshot", spec(List.of("paid_gmv"), List.of())))
                .isInstanceOf(CommerceException.class).extracting("status").isEqualTo(404);
    }

    @Test
    void inventoryIsEvidenceAndDoesNotTurnMissingSnapshotsIntoZero() {
        JsonNode dates = object("start", "2026-09-20", "endExclusive", "2026-09-21");
        ObjectNode result = service.inventory(subject, service.scope(subject, List.of("s1")), DATASET, List.of("p1"), dates);
        assertThat(result.path("rows")).hasSize(1);
        assertThat(cell(result.path("rows").get(0), "stockout_minutes").path("value").asText()).isEqualTo("480");
        assertThat(service.evidence(subject, result.path("evidenceId").asText()).path("kind").asText()).isEqualTo("INVENTORY_SIGNAL");
        ObjectNode missing = service.inventory(subject, service.scope(subject, List.of("s1")), DATASET, List.of("unknown"), dates);
        assertThat(missing.path("rows")).isEmpty();
        assertThat(missing.path("quality").path("status").asText()).isEqualTo("PARTIAL");
    }

    @Test
    void overviewCarriesEvidenceAndServerComputedContributionWithSevenDayTrend() {
        ObjectNode overview = service.overview(subject, null, "2026-09-20", "2026-09-21", "PREVIOUS_WEEK_SAME_DAYS");
        assertThat(overview.path("trend")).hasSize(7);
        assertThat(overview.path("trendRange").path("start").asText()).isEqualTo("2026-09-14");
        assertThat(overview.path("trendComparisonRange").path("start").asText()).isEqualTo("2026-09-07");
        assertThat(overview.path("summary").path("storeId").asText()).isEqualTo("s1");
        assertThat(overview.path("summary").path("deltaCents").asText()).isEqualTo("-10500000");
        assertThat(overview.path("summary").path("netContributionRatio").asText()).isEqualTo("0.922671353251");
        assertThat(overview.path("synthetic").asBoolean()).isTrue();
        assertThat(service.evidence(subject, overview.path("evidenceId").asText()).path("result").path("totals").toString())
                .isEqualTo(overview.path("totals").toString());
    }
}
