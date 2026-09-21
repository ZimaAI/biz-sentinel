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
import com.alibaba.cloud.ai.dataagent.commerce.security.CommercePolicy;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static com.alibaba.cloud.ai.dataagent.commerce.ingestion.CommerceCsv.Table;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;

/** Admin-only standard-package ingestion. Query visibility is committed only after all rows are written. */
@Service
@Profile("commerce")
public class CommerceIngestionService {

    private static final long MAX_ARCHIVE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final Set<String> FILES;
    static {
        Set<String> names = new HashSet<>();
        names.add("manifest.json");
        for (Table table : Table.values()) names.add(table.filename());
        FILES = Set.copyOf(names);
    }

    private final CommerceStore store;
    private final CommercePolicy policy;
    private final JdbcTemplate ingest;
    private final TransactionTemplate businessTransaction;
    private final Path storageRoot;
    private final CommerceDatasetValidator validator = new CommerceDatasetValidator();

    public CommerceIngestionService(CommerceStore store,
            @Qualifier("commerceIngestJdbc") JdbcTemplate ingest,
            @Value("${commerce.storage-path:.commerce-data}") String storagePath) {
        this.store = store;
        this.policy = new CommercePolicy(store);
        this.ingest = ingest;
        this.businessTransaction = new TransactionTemplate(new DataSourceTransactionManager(ingest.getDataSource()));
        this.storageRoot = Path.of(storagePath).toAbsolutePath().normalize().resolve("ingestions");
    }

    public synchronized ObjectNode upload(CommerceSubject subject, byte[] zip, String idempotencyKey) {
        policy.assertCurrent(subject);
        requireAdmin(subject);
        validateKey(idempotencyKey);
        if (zip == null || zip.length == 0 || zip.length > MAX_ARCHIVE_BYTES) {
            throw new CommerceException(413, "ARCHIVE_SIZE_LIMIT", "导入 ZIP 不能为空且最多为 64 MiB");
        }
        String archiveHash = digest(zip);
        String keyId = CommerceJson.sha256(subject.tenantId() + "\u0000" + subject.subjectId() + "\u0000ingestion\u0000" + idempotencyKey);
        ObjectNode previous = store.findOne("ingestion-idempotency", keyId);
        if (previous != null) {
            if (!archiveHash.equals(previous.path("archiveHash").asText())) throw new CommerceException(409, "IDEMPOTENCY_CONFLICT", "相同幂等键不能上传不同文件");
            return get(subject, previous.path("jobId").asText());
        }
        for (ObjectNode job : store.find("ingestion", subject.tenantId())) {
            if (archiveHash.equals(job.path("archiveHash").asText()) && !"FAILED".equals(job.path("status").asText())) {
                store.create("ingestion-idempotency", keyId, subject.tenantId(), object("archiveHash", archiveHash, "jobId", job.path("jobId").asText()));
                return get(subject, job.path("jobId").asText());
            }
        }
        validateCentralDirectory(zip);
        String id = "ing_" + UUID.randomUUID().toString().replace("-", "");
        Path staging = stagingDirectory(id);
        try {
            Files.createDirectories(staging);
            extract(zip, staging);
        }
        catch (CommerceException e) { cleanupStaging(staging); throw e; }
        catch (IOException e) { cleanupStaging(staging); throw new CommerceException(400, "INVALID_ARCHIVE", "无法读取规范 ZIP 包"); }
        return stage(subject, id, staging, archiveHash, keyId);
    }

    public ObjectNode get(CommerceSubject subject, String id) {
        policy.assertCurrent(subject);
        requireAdmin(subject);
        return publicJob(visibleJob(subject, id));
    }

    public synchronized ObjectNode publish(CommerceSubject subject, String id, long expectedVersion) {
        policy.assertCurrent(subject);
        requireAdmin(subject);
        ObjectNode job = visibleJob(subject, id);
        if (job.path("version").asLong() != expectedVersion) throw new CommerceException(409, "VERSION_CONFLICT", "导入任务版本已变化，请刷新后重试");
        if ("PUBLISHED".equals(job.path("status").asText())) return datasetView(subject, store.get("dataset", job.path("datasetVersionId").asText()));
        if (!"READY_TO_PUBLISH".equals(job.path("status").asText())) throw new CommerceException(409, "INGESTION_NOT_READY", "只有校验通过的导入任务可以发布");
        CommerceDatasetValidator.Result validated;
        try { validated = validator.validate(subject, stagingDirectory(id)); }
        catch (CommerceCsv.InvalidData e) {
            reject(job, e);
            throw new CommerceException(422, "STAGED_DATA_CHANGED", "暂存数据或授权发生变化，重新校验未通过");
        }
        catch (IOException e) { throw new CommerceException(503, "STAGING_UNAVAILABLE", "暂存数据不可用，无法发布"); }

        return store.transaction(() -> {
            policy.assertCurrent(subject);
            ObjectNode current = visibleJob(subject, id);
            if (current.path("version").asLong() != expectedVersion || !"READY_TO_PUBLISH".equals(current.path("status").asText())) {
                throw new CommerceException(409, "VERSION_CONFLICT", "导入任务状态已变化，请刷新后重试");
            }
            String datasetId = validated.manifest().path("datasetVersionId").asText();
            if (store.findOne("dataset", datasetId) != null) throw new CommerceException(409, "DATASET_IMMUTABLE", "该数据版本已存在；修订必须使用新的版本 ID");
            ObjectNode dataset = validated.manifest().deepCopy();
            dataset.set("manifest", validated.manifest().deepCopy());
            dataset.set("stores", validated.stores());
            dataset.set("tables", validated.tables());
            dataset.set("quality", validated.quality());
            dataset.set("rowCountsByStore", validated.rowCountsByStore());
            dataset.set("scope", scope(subject, validated.stores()));
            dataset.put("subjectId", subject.subjectId());
            dataset.put("version", 1);
            dataset.put("status", "VALIDATING");
            dataset.put("jobId", id);
            dataset.put("publishedAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
            // A unique dataset document serializes competing publishers. It remains invisible until commit.
            ObjectNode reserved = store.create("dataset", datasetId, subject.tenantId(), dataset);
            try {
                businessTransaction.executeWithoutResult(status -> {
                    removeUnpublishedRows(subject.tenantId(), datasetId);
                    for (Table table : Table.values()) insertTable(stagingDirectory(id), table);
                    policy.assertCurrent(subject);
                });
            }
            catch (RuntimeException e) {
                if (e instanceof CommerceException exception) throw exception;
                throw new CommerceException(503, "INGESTION_WRITE_FAILED", "写入分析快照失败，尚未发布；可重新尝试");
            }
            ObjectNode published = store.update("dataset", datasetId, reserved.path("_revision").asLong(), value -> {
                value.put("status", "PUBLISHED");
                value.withObject("quality").put("publishedAt", value.path("publishedAt").asText());
                return value;
            });
            store.update("ingestion", id, current.path("_revision").asLong(), value -> {
                value.put("status", "PUBLISHED"); value.put("version", expectedVersion + 1); return value;
            });
            store.create("audit", "audit_" + UUID.randomUUID(), subject.tenantId(), object("action", "DATASET_PUBLISHED",
                    "subjectId", subject.subjectId(), "datasetVersionId", datasetId, "scope", published.path("scope"),
                    "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).toString()));
            return datasetView(subject, published);
        });
    }

    public List<ObjectNode> datasets(CommerceSubject subject) {
        policy.assertCurrent(subject);
        return store.find("dataset", subject.tenantId()).stream()
                .filter(value -> "PUBLISHED".equals(value.path("status").asText()) || "RETIRED".equals(value.path("status").asText()))
                .filter(value -> hasVisibleStore(subject, value))
                .map(value -> datasetView(subject, value)).toList();
    }

    /** Explicit development bootstrap uses the same validator and publisher as uploaded data. */
    public synchronized ObjectNode importDirectory(CommerceSubject subject, Path directory) {
        policy.assertCurrent(subject);
        requireAdmin(subject);
        Path source = directory.toAbsolutePath().normalize();
        String id = "ing_" + UUID.randomUUID().toString().replace("-", "");
        Path destination = stagingDirectory(id);
        try {
            long bytes = 0;
            for (String name : FILES) {
                Path file = source.resolve(name);
                if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new CommerceException(400, "INVALID_IMPORT_DIRECTORY", "导入目录必须包含清单和 8 个普通 CSV 文件");
                bytes = Math.addExact(bytes, Files.size(file));
            }
            if (bytes > MAX_EXPANDED_BYTES) throw new CommerceException(413, "ARCHIVE_SIZE_LIMIT", "导入文件总大小超过 256 MiB");
            String hash = CommerceDatasetValidator.digest(source.resolve("manifest.json"));
            for (ObjectNode job : store.find("ingestion", subject.tenantId())) {
                if (hash.equals(job.path("archiveHash").asText()) && "PUBLISHED".equals(job.path("status").asText())) {
                    return datasetView(subject, store.get("dataset", job.path("datasetVersionId").asText()));
                }
            }
            Files.createDirectories(destination);
            for (String name : FILES) Files.copy(source.resolve(name), destination.resolve(name));
            ObjectNode job = stage(subject, id, destination, hash, null);
            if (!"READY_TO_PUBLISH".equals(job.path("status").asText())) throw new CommerceException(422, "INVALID_DEMO_DATASET", "演示数据真实校验未通过：" + job.path("errors"));
            return publish(subject, id, job.path("version").asLong());
        }
        catch (IOException e) { throw new CommerceException(503, "IMPORT_IO_FAILED", "无法读取标准数据目录"); }
    }

    private ObjectNode stage(CommerceSubject subject, String id, Path staging, String hash, String keyId) {
        ObjectNode job = object("jobId", id, "version", 1, "status", "VALIDATING", "datasetVersionId", "",
                "filesValidated", 0, "errors", CommerceJson.MAPPER.createArrayNode(), "archiveHash", hash,
                "subjectId", subject.subjectId(), "tenantId", subject.tenantId(), "createdAt", OffsetDateTime.now(ZoneOffset.UTC).toString());
        job = store.create("ingestion", id, subject.tenantId(), job);
        if (keyId != null) store.create("ingestion-idempotency", keyId, subject.tenantId(), object("archiveHash", hash, "jobId", id));
        try {
            CommerceDatasetValidator.Result result = validator.validate(subject, staging);
            ObjectNode ready = store.update("ingestion", id, job.path("_revision").asLong(), value -> {
                value.put("status", "READY_TO_PUBLISH"); value.put("version", 2); value.put("filesValidated", 8);
                value.put("datasetVersionId", result.manifest().path("datasetVersionId").asText());
                value.set("quality", result.quality()); value.set("scope", scope(subject, result.stores())); return value;
            });
            return publicJob(ready);
        }
        catch (CommerceCsv.InvalidData e) { return publicJob(reject(job, e)); }
        catch (IOException e) { return publicJob(reject(job, new CommerceCsv.InvalidData("manifest.json", 0, "INVALID_PACKAGE", "数据文件不存在、编码不合法或无法读取"))); }
    }

    private ObjectNode reject(ObjectNode job, CommerceCsv.InvalidData error) {
        ObjectNode rejected = store.update("ingestion", job.path("jobId").asText(), job.path("_revision").asLong(), value -> {
            value.put("status", "REJECTED"); value.put("version", value.path("version").asLong() + 1);
            value.set("errors", CommerceJson.MAPPER.createArrayNode().add(object("file", error.file, "line", error.line,
                    "code", error.code, "message", error.getMessage()))); return value;
        });
        cleanupStaging(stagingDirectory(job.path("jobId").asText()));
        return rejected;
    }

    private void cleanupStaging(Path directory) {
        Path target = directory.toAbsolutePath().normalize();
        if (!target.startsWith(storageRoot) || target.equals(storageRoot)) throw new IllegalStateException("Invalid cleanup path");
        try {
            for (String name : FILES) Files.deleteIfExists(target.resolve(name));
            Files.deleteIfExists(target);
        }
        catch (IOException ignored) { /* The rejection is durable; storage maintenance can remove a locked staging file. */ }
    }

    private ObjectNode visibleJob(CommerceSubject subject, String id) {
        ObjectNode job = store.get("ingestion", id);
        if (!subject.tenantId().equals(job.path("tenantId").asText())) throw new CommerceException(404, "RESOURCE_NOT_FOUND", "导入任务不存在或不可见");
        JsonNode originalStores = job.path("scope").path("storeIds");
        for (JsonNode storeId : originalStores) if (!subject.storeIds().contains(storeId.asText())) throw new CommerceException(404, "RESOURCE_NOT_FOUND", "导入任务不存在或不可见");
        return job;
    }

    private ObjectNode publicJob(ObjectNode job) {
        ObjectNode view = object();
        for (String key : List.of("jobId", "version", "status", "datasetVersionId", "filesValidated", "errors", "quality")) {
            if (job.has(key)) view.set(key, job.get(key).deepCopy());
        }
        return view;
    }

    private boolean hasVisibleStore(CommerceSubject subject, ObjectNode dataset) {
        for (JsonNode value : dataset.path("stores")) if (subject.storeIds().contains(value.path("storeId").asText())) return true;
        return false;
    }

    private ObjectNode datasetView(CommerceSubject subject, ObjectNode dataset) {
        if (!subject.tenantId().equals(dataset.path("tenantId").asText()) || !hasVisibleStore(subject, dataset)) {
            throw new CommerceException(404, "RESOURCE_NOT_FOUND", "数据集不存在或不可见");
        }
        ArrayNode visibleStores = CommerceJson.MAPPER.createArrayNode();
        for (JsonNode value : dataset.path("stores")) if (subject.storeIds().contains(value.path("storeId").asText())) visibleStores.add(value.deepCopy());
        ArrayNode tables = CommerceJson.MAPPER.createArrayNode();
        for (Table table : Table.values()) {
            long count = 0;
            for (JsonNode value : visibleStores) count += dataset.path("rowCountsByStore").path(value.path("storeId").asText()).path(table.source).asLong();
            tables.add(object("name", table.source, "rowCount", count));
        }
        ObjectNode quality = dataset.withObject("quality").deepCopy();
        ArrayNode missing = CommerceJson.MAPPER.createArrayNode();
        for (JsonNode entry : quality.path("missingCoverage")) if (subject.storeIds().contains(entry.path("storeId").asText())) missing.add(entry.deepCopy());
        quality.set("missingCoverage", missing);
        quality.put("status", missing.isEmpty() ? "COMPLETE" : "PARTIAL");
        ObjectNode sourceStatus = object();
        for (Table table : Table.values()) sourceStatus.put(table.source, "COMPLETE");
        for (JsonNode entry : missing) sourceStatus.put(entry.path("source").asText(), "PARTIAL");
        quality.set("sourceStatus", sourceStatus);
        if (missing.isEmpty()) quality.set("warnings", CommerceJson.MAPPER.createArrayNode());
        return object("datasetVersionId", dataset.path("datasetVersionId").asText(), "status", dataset.path("status").asText(),
                "coverage", dataset.path("coverage").deepCopy(), "quality", quality, "tables", tables,
                "stores", visibleStores, "synthetic", dataset.path("synthetic").asBoolean(false), "scope", scope(subject, visibleStores));
    }

    private static ObjectNode scope(CommerceSubject subject, ArrayNode stores) {
        ArrayNode ids = CommerceJson.MAPPER.createArrayNode();
        for (JsonNode value : stores) ids.add(value.path("storeId").asText());
        return object("tenantId", subject.tenantId(), "storeIds", ids, "authzVersion", subject.authzVersion());
    }

    private void insertTable(Path staging, Table table) {
        List<Object[]> batch = new ArrayList<>(1000);
        try {
            CommerceCsv.read(staging.resolve(table.filename()), table, (row, line) -> {
                Object[] values = new Object[row.size()];
                for (int i = 0; i < row.size(); i++) {
                    String field = table.columns.get(i), value = row.get(i);
                    values[i] = field.endsWith("_cents") || Set.of("quantity", "visitor_sessions", "closing_stock", "stockout_minutes").contains(field)
                            ? Long.parseLong(value) : field.endsWith("_at_utc") ? CommerceDatasetValidator.timestamp(value)
                            : field.equals("biz_date") ? java.sql.Date.valueOf(LocalDate.parse(value)) : value;
                }
                batch.add(values);
                if (batch.size() == 1000) { ingest.batchUpdate(table.insertSql(), batch); batch.clear(); }
            });
            if (!batch.isEmpty()) ingest.batchUpdate(table.insertSql(), batch);
        }
        catch (IOException e) { throw new CommerceException(503, "STAGING_UNAVAILABLE", "暂存 CSV 无法读取"); }
    }

    private void removeUnpublishedRows(String tenant, String dataset) {
        List<Table> reverse = new ArrayList<>(Arrays.asList(Table.values()));
        Collections.reverse(reverse);
        for (Table table : reverse) ingest.update("DELETE FROM " + table.sqlTable + " WHERE tenant_id=? AND dataset_version_id=?", tenant, dataset);
    }

    private static void requireAdmin(CommerceSubject subject) {
        if (!subject.isAdmin()) throw new CommerceException(403, "ADMIN_REQUIRED", "只有租户管理员可以导入和发布数据");
    }
    private static void validateKey(String value) {
        if (value == null || value.isBlank() || value.length() > 96 || value.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new CommerceException(400, "INVALID_IDEMPOTENCY_KEY", "必须提供有效的 Idempotency-Key");
        }
    }
    private Path stagingDirectory(String id) {
        if (!id.matches("ing_[a-f0-9]{32}")) throw new CommerceException(404, "RESOURCE_NOT_FOUND", "导入任务不存在");
        Path path = storageRoot.resolve(id).normalize();
        if (!path.startsWith(storageRoot)) throw new IllegalStateException("Invalid staging path");
        return path;
    }
    private static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static void extract(byte[] bytes, Path directory) throws IOException {
        Set<String> names = new HashSet<>(); long expanded = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            byte[] buffer = new byte[65_536];
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !FILES.contains(name) || !names.add(name)) {
                    throw new CommerceException(400, "UNSAFE_ARCHIVE_ENTRY", "ZIP 只允许根目录下的清单和 8 个规范 CSV，不能有路径或重复文件");
                }
                try (OutputStream output = Files.newOutputStream(directory.resolve(name), StandardOpenOption.CREATE_NEW)) {
                    long entryBytes = 0; int read;
                    while ((read = zip.read(buffer)) != -1) {
                        expanded += read; entryBytes += read;
                        if (expanded > MAX_EXPANDED_BYTES || (name.equals("manifest.json") && entryBytes > 1_048_576)) {
                            throw new CommerceException(413, "ARCHIVE_SIZE_LIMIT", "ZIP 展开体积超过导入上限");
                        }
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
        if (!names.equals(FILES)) throw new CommerceException(400, "MISSING_ARCHIVE_FILES", "ZIP 必须包含 manifest.json 和全部 8 个规范 CSV");
    }

    /** Reject links/encryption/ZIP64 before extraction; extraction itself never honors archive paths or modes. */
    private static void validateCentralDirectory(byte[] bytes) {
        try {
            ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
            int eocd = -1;
            for (int i = bytes.length - 22; i >= Math.max(0, bytes.length - 65_557); i--) {
                if (buffer.getInt(i) == 0x06054b50 && i + 22 + Short.toUnsignedInt(buffer.getShort(i + 20)) == bytes.length) { eocd = i; break; }
            }
            if (eocd < 0 || buffer.getShort(eocd + 4) != 0 || buffer.getShort(eocd + 6) != 0
                    || Short.toUnsignedInt(buffer.getShort(eocd + 10)) != 9) throw new IllegalArgumentException();
            int offset = buffer.getInt(eocd + 16), size = buffer.getInt(eocd + 12);
            if (offset < 0 || size < 0 || (long) offset + size != eocd) throw new IllegalArgumentException();
            int position = offset;
            for (int i = 0; i < 9; i++) {
                if (position < 0 || position + 46 > eocd || buffer.getInt(position) != 0x02014b50) throw new IllegalArgumentException();
                int mode = (buffer.getInt(position + 38) >>> 16) & 0170000;
                if (mode == 0120000 || (Short.toUnsignedInt(buffer.getShort(position + 8)) & 1) != 0) {
                    throw new CommerceException(400, "UNSAFE_ARCHIVE_ENTRY", "ZIP 不允许符号链接或加密条目");
                }
                position += 46 + Short.toUnsignedInt(buffer.getShort(position + 28)) + Short.toUnsignedInt(buffer.getShort(position + 30))
                        + Short.toUnsignedInt(buffer.getShort(position + 32));
            }
            if (position != eocd) throw new IllegalArgumentException();
        }
        catch (CommerceException e) { throw e; }
        catch (RuntimeException e) { throw new CommerceException(400, "INVALID_ARCHIVE", "必须上传含 9 个规范文件的完整标准 ZIP 包"); }
    }
}
