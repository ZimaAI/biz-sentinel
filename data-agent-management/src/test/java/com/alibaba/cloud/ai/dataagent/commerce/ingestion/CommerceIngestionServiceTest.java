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
package com.alibaba.cloud.ai.dataagent.commerce.ingestion;

import com.alibaba.cloud.ai.dataagent.commerce.persistence.CommerceStore;
import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommerceIngestionServiceTest {

    @TempDir Path temp;
    private CommerceStore store;
    private JdbcTemplate jdbc;
    private JdbcTemplate managementJdbc;
    private CommerceIngestionService service;
    private final CommerceSubject admin = new CommerceSubject("t_demo", "admin", Set.of("s1", "s2", "s3"), Set.of("TENANT_ADMIN"), 1);

    @BeforeEach void setup() {
        String id = UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource management = new DriverManagerDataSource("jdbc:h2:mem:meta" + id + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        DriverManagerDataSource business = new DriverManagerDataSource("jdbc:h2:mem:business" + id + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("commerce/business-schema.sql")).execute(business);
        store = new CommerceStore(management);
        managementJdbc = new JdbcTemplate(management);
        store.create("member", "t_demo:admin", "t_demo", object("storeIds", Set.of("s1", "s2", "s3"), "roles", Set.of("TENANT_ADMIN"), "authzVersion", 1, "enabled", true));
        jdbc = new JdbcTemplate(business);
        service = new CommerceIngestionService(store, jdbc, temp.resolve("storage").toString());
    }

    @AfterEach void closeDatabases() {
        if (jdbc != null) jdbc.execute("SHUTDOWN");
        if (managementJdbc != null) managementJdbc.execute("SHUTDOWN");
    }

    @Test void validatesThenPublishesImmutableSnapshotAndUsesIndependentFacts() throws Exception {
        ObjectNode job = service.upload(admin, archive(validFiles(), "v1"), "first");
        assertThat(job.path("status").asText()).isEqualTo("READY_TO_PUBLISH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cl_payment", Integer.class)).isZero();
        ObjectNode dataset = service.publish(admin, job.path("jobId").asText(), 2);
        assertThat(dataset.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(dataset.path("quality").path("status").asText()).isEqualTo("COMPLETE");
        assertThat(jdbc.queryForObject("SELECT SUM(amount_cents) FROM cl_payment", Long.class)).isEqualTo(10000);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_cents) FROM cl_refund", Long.class)).isEqualTo(3000);
        assertThat(jdbc.queryForObject("SELECT SUM(allocated_paid_cents) FROM cl_order_item", Long.class)).isEqualTo(10000);
        assertThat(store.get("dataset", "v1").path("manifest").path("currency").asText()).isEqualTo("CNY");
        assertThat(service.get(admin, job.path("jobId").asText()).path("version").asLong()).isEqualTo(3);
        assertThatThrownBy(() -> service.publish(admin, job.path("jobId").asText(), 2)).isInstanceOf(CommerceException.class);
        var changed = validFiles(); changed.put("stores.csv", changed.get("stores.csv").replace("主店", "改名"));
        ObjectNode another = service.upload(admin, archive(changed, "v1"), "second");
        assertThatThrownBy(() -> service.publish(admin, another.path("jobId").asText(), 2)).isInstanceOf(CommerceException.class)
                .hasMessageContaining("版本已存在");
        assertThat(jdbc.queryForObject("SELECT name FROM cl_store", String.class)).isEqualTo("主店");
    }

    @Test void rejectsRepeatedSuccessfulPaymentRefundOverflowAndAllocationMismatch() throws Exception {
        var duplicate = validFiles(); duplicate.put("payments.csv", duplicate.get("payments.csv") + "t_demo,v1,s1,pay2,o1,2026-09-13 03:00:00,10000\n");
        assertRejected(duplicate, "DUPLICATE_PAYMENT");
        var refund = validFiles(); refund.put("refunds.csv", refund.get("refunds.csv").replace(",2000\n", ",10000\n"));
        assertRejected(refund, "REFUND_EXCEEDS_PAYMENT");
        var allocation = validFiles(); allocation.put("order_items.csv", allocation.get("order_items.csv").replace(",4000\n", ",3999\n"));
        assertRejected(allocation, "ALLOCATION_MISMATCH");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cl_order", Integer.class)).isZero();
    }

    @Test void rejectsTenantCrossStoreReferencesAndInvalidEventTimes() throws Exception {
        var tenant = validFiles(); tenant.put("payments.csv", tenant.get("payments.csv").replace("t_demo,v1,s1,pay1", "t_other,v1,s1,pay1"));
        assertRejected(tenant, "SCOPE_MISMATCH");
        var foreignStore = validFiles(); foreignStore.put("payments.csv", foreignStore.get("payments.csv").replace("v1,s1,pay1", "v1,s2,pay1"));
        assertRejected(foreignStore, "MISSING_STORE");
        var time = validFiles(); time.put("payments.csv", time.get("payments.csv").replace("2026-09-13 02:00:00", "2026-09-12 02:00:00"));
        assertRejected(time, "INVALID_EVENT_TIME");
    }

    @Test void preservesMissingTrafficAsPartialInsteadOfInventingZero() throws Exception {
        var files = validFiles();
        files.put("traffic_daily.csv", files.get("traffic_daily.csv").replace("t_demo,v1,s1,2026-09-20,1000,COMPLETE\n", ""));
        ObjectNode job = service.upload(admin, archive(files, "v1"), "partial");
        assertThat(job.path("status").asText()).isEqualTo("READY_TO_PUBLISH");
        assertThat(job.path("quality").path("status").asText()).isEqualTo("PARTIAL");
        ObjectNode dataset = service.publish(admin, job.path("jobId").asText(), 2);
        assertThat(dataset.path("quality").path("missingCoverage").toString()).contains("traffic_daily", "2026-09-20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cl_traffic_daily WHERE biz_date='2026-09-20'", Integer.class)).isZero();
    }

    @Test void enforcesIdempotencyAndAuthorizationBeforeReadingBusinessData() throws Exception {
        byte[] bytes = archive(validFiles(), "v1");
        ObjectNode original = service.upload(admin, bytes, "same");
        assertThat(service.upload(admin, bytes, "same").path("jobId")).isEqualTo(original.path("jobId"));
        assertThatThrownBy(() -> service.upload(admin, archive(validFiles(), "v2"), "same")).isInstanceOf(CommerceException.class);
        CommerceSubject viewer = new CommerceSubject("t_demo", "viewer", Set.of("s1"), Set.of("VIEWER"), 1);
        assertThatThrownBy(() -> service.upload(viewer, bytes, "viewer")).isInstanceOf(CommerceException.class);
        CommerceSubject other = new CommerceSubject("t_other", "admin2", Set.of("s1"), Set.of("TENANT_ADMIN"), 1);
        assertThatThrownBy(() -> service.get(other, original.path("jobId").asText())).isInstanceOf(CommerceException.class);
        CommerceSubject revoked = new CommerceSubject("t_demo", "admin", Set.of("s2"), Set.of("TENANT_ADMIN"), 2);
        assertThatThrownBy(() -> service.publish(revoked, original.path("jobId").asText(), 2)).isInstanceOf(CommerceException.class);
    }

    @Test void rejectsUnsafeZipEntriesAndSymbolicLinks() throws Exception {
        var files = validFiles(); files.put("../stores.csv", files.remove("stores.csv"));
        assertThatThrownBy(() -> service.upload(admin, archive(files, "v1"), "path")).isInstanceOf(CommerceException.class);
        byte[] link = archive(validFiles(), "v1");
        ByteBuffer buffer = ByteBuffer.wrap(link).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < link.length - 46; i++) {
            if (buffer.getInt(i) == 0x02014b50) { buffer.putInt(i + 38, 0120777 << 16); break; }
        }
        assertThatThrownBy(() -> service.upload(admin, link, "link")).isInstanceOf(CommerceException.class).hasMessageContaining("符号链接");
    }

    @Test void rejectsModifiedStagingAtPublishAndRollsBackBusinessTransaction() throws Exception {
        ObjectNode job = service.upload(admin, archive(validFiles(), "v1"), "modified");
        Path staged = temp.resolve("storage/ingestions").resolve(job.path("jobId").asText()).resolve("payments.csv");
        Files.writeString(staged, "changed");
        assertThatThrownBy(() -> service.publish(admin, job.path("jobId").asText(), 2)).isInstanceOf(CommerceException.class);
        assertThat(service.get(admin, job.path("jobId").asText()).path("status").asText()).isEqualTo("REJECTED");
        ObjectNode ready = service.upload(admin, archive(validFiles(), "v2"), "rollback");
        jdbc.execute("ALTER TABLE cl_payment ADD CONSTRAINT test_failure CHECK (amount_cents < 10)");
        assertThatThrownBy(() -> service.publish(admin, ready.path("jobId").asText(), 2)).isInstanceOf(CommerceException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cl_store", Integer.class)).isZero();
        assertThat(store.findOne("dataset", "v2")).isNull();
        assertThat(service.get(admin, ready.path("jobId").asText()).path("status").asText()).isEqualTo("READY_TO_PUBLISH");
    }

    @Test void importsFullReferenceFixtureAndMatchesGoldenWithoutLoadingPrototypeData() {
        Path fixture = Path.of("../docs/reference/commerce-lens/fixtures/business/t_demo").toAbsolutePath().normalize();
        ObjectNode dataset = service.importDirectory(admin, fixture);
        assertThat(dataset.path("datasetVersionId").asText()).isEqualTo("demo_20260921_v1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM cl_payment", Integer.class)).isEqualTo(127282);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_cents) FROM cl_payment WHERE paid_at_utc >= '2026-09-19 16:00:00' AND paid_at_utc < '2026-09-20 16:00:00'", Long.class)).isEqualTo(48620000);
        assertThat(jdbc.queryForObject("SELECT SUM(amount_cents) FROM cl_refund WHERE succeeded_at_utc >= '2026-09-19 16:00:00' AND succeeded_at_utc < '2026-09-20 16:00:00'", Long.class)).isEqualTo(2686000);
        assertThat(service.importDirectory(admin, fixture).path("datasetVersionId")).isEqualTo(dataset.path("datasetVersionId"));
        CommerceSubject single = new CommerceSubject("t_demo", "single", Set.of("s1"), Set.of("STORE_OPERATOR"), 1);
        store.create("member", "t_demo:single", "t_demo", object("storeIds", Set.of("s1"), "roles", Set.of("STORE_OPERATOR"), "authzVersion", 1, "enabled", true));
        ObjectNode visible = service.datasets(single).get(0);
        assertThat(visible.path("stores").size()).isEqualTo(1);
        assertThat(visible.path("tables").get(0).path("rowCount").asLong()).isEqualTo(1);
        assertThat(visible.toString()).doesNotContain("s2", "s3");
    }

    private void assertRejected(Map<String, String> files, String code) throws Exception {
        ObjectNode job = service.upload(admin, archive(files, "v1"), UUID.randomUUID().toString());
        assertThat(job.path("status").asText()).isEqualTo("REJECTED");
        assertThat(job.path("errors").get(0).path("code").asText()).isEqualTo(code);
    }

    private Map<String, String> validFiles() {
        Map<String, String> files = new LinkedHashMap<>();
        for (CommerceCsv.Table table : CommerceCsv.Table.values()) files.put(table.filename(), String.join(",", table.columns) + "\n");
        files.compute("stores.csv", (k, v) -> v + "t_demo,v1,s1,主店,TMALL,Asia/Shanghai\n");
        files.compute("products.csv", (k, v) -> v + "t_demo,v1,s1,p1,SKU1,商品一,日用品\nt_demo,v1,s1,p2,SKU2,商品二,日用品\n");
        files.compute("orders.csv", (k, v) -> v + "t_demo,v1,s1,o1,2026-09-13 00:00:00,CLOSED,10000\n");
        files.compute("order_items.csv", (k, v) -> v + "t_demo,v1,s1,o1,i1,p1,1,6000\nt_demo,v1,s1,o1,i2,p2,1,4000\n");
        files.compute("payments.csv", (k, v) -> v + "t_demo,v1,s1,pay1,o1,2026-09-13 02:00:00,10000\n");
        files.compute("refunds.csv", (k, v) -> v + "t_demo,v1,s1,r1,o1,2026-09-19 02:00:00,1000\nt_demo,v1,s1,r2,o1,2026-09-20 02:00:00,2000\n");
        for (LocalDate day = LocalDate.parse("2026-09-13"); day.isBefore(LocalDate.parse("2026-09-21")); day = day.plusDays(1)) {
            String date = day.toString();
            files.compute("traffic_daily.csv", (k, v) -> v + "t_demo,v1,s1," + date + ",1000,COMPLETE\n");
            files.compute("inventory_daily.csv", (k, v) -> v + "t_demo,v1,s1,p1," + date + ",20,0,COMPLETE\nt_demo,v1,s1,p2," + date + ",20,0,COMPLETE\n");
        }
        return files;
    }

    private byte[] archive(Map<String, String> files, String dataset) throws Exception {
        Map<String, byte[]> content = new LinkedHashMap<>();
        for (var entry : files.entrySet()) content.put(entry.getKey(), entry.getValue().replace(",v1,", "," + dataset + ",").getBytes(StandardCharsets.UTF_8));
        ArrayNode declarations = CommerceJson.MAPPER.createArrayNode();
        for (var entry : content.entrySet()) {
            String text = new String(entry.getValue(), StandardCharsets.UTF_8);
            declarations.add(object("name", entry.getKey(), "rows", text.lines().count() - 1,
                    "sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(entry.getValue()))));
        }
        ArrayNode days = CommerceJson.MAPPER.createArrayNode();
        for (LocalDate date = LocalDate.parse("2026-09-13"); date.isBefore(LocalDate.parse("2026-09-21")); date = date.plusDays(1)) days.add(date.toString());
        ObjectNode manifest = object("schemaVersion", "1.0", "tenantId", "t_demo", "datasetVersionId", dataset, "currency", "CNY",
                "timezone", "Asia/Shanghai", "synthetic", true, "businessWatermark", "2026-09-21T00:00:00+08:00", "files", declarations,
                "coverage", object("start", "2026-09-13", "endExclusive", "2026-09-21", "completeBusinessDates", days));
        content.put("manifest.json", manifest.toString().getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (var entry : content.entrySet()) {
                ZipEntry next = new ZipEntry(entry.getKey()); next.setTime(0);
                zip.putNextEntry(next); zip.write(entry.getValue()); zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
