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

import com.alibaba.cloud.ai.dataagent.commerce.security.CommerceSubject;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.alibaba.cloud.ai.dataagent.commerce.ingestion.CommerceCsv.InvalidData;
import static com.alibaba.cloud.ai.dataagent.commerce.ingestion.CommerceCsv.Table;
import static com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson.object;

/** Cross-row validation runs on private staging files before business rows become visible. */
final class CommerceDatasetValidator {

    record Result(ObjectNode manifest, ArrayNode stores, ArrayNode tables, ObjectNode quality,
                  ObjectNode rowCountsByStore) { }
    private record Order(LocalDateTime createdAt, long expected) { }
    private record Payment(LocalDateTime paidAt, long amount) { }

    Result validate(CommerceSubject subject, Path directory) throws IOException {
        ObjectNode manifest;
        try {
            if (Files.size(directory.resolve("manifest.json")) > 1_048_576) fail("manifest.json", 0, "INVALID_MANIFEST", "清单过大");
            JsonNode parsed = CommerceJson.MAPPER.readTree(Files.readString(directory.resolve("manifest.json")));
            if (!(parsed instanceof ObjectNode)) throw new IllegalArgumentException();
            manifest = (ObjectNode) parsed;
            if (!"1.0".equals(required(manifest, "schemaVersion")) || !"CNY".equals(required(manifest, "currency"))
                    || !"Asia/Shanghai".equals(required(manifest, "timezone"))) {
                fail("manifest.json", 0, "UNSUPPORTED_DATASET", "仅支持 v1.0、人民币和 Asia/Shanghai 业务时区");
            }
            if (!subject.tenantId().equals(required(manifest, "tenantId"))) fail("manifest.json", 0, "TENANT_MISMATCH", "清单租户与当前授权租户不一致");
            if (!required(manifest, "datasetVersionId").matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,63}")) {
                fail("manifest.json", 0, "INVALID_DATASET_ID", "数据版本 ID 格式不合法");
            }
            OffsetDateTime.parse(required(manifest, "businessWatermark"));
        }
        catch (InvalidData e) { throw e; }
        catch (RuntimeException e) { throw new InvalidData("manifest.json", 0, "INVALID_MANIFEST", "数据清单字段或时间格式错误"); }

        LocalDate start;
        LocalDate end;
        Set<String> completeDates = new LinkedHashSet<>();
        try {
            start = LocalDate.parse(required(manifest.path("coverage"), "start"));
            end = LocalDate.parse(required(manifest.path("coverage"), "endExclusive"));
            if (!end.isAfter(start) || ChronoUnit.DAYS.between(start, end) > 3660) throw new IllegalArgumentException();
            JsonNode dates = manifest.path("coverage").path("completeBusinessDates");
            if (!dates.isArray()) throw new IllegalArgumentException();
            for (JsonNode d : dates) {
                LocalDate date = LocalDate.parse(d.asText());
                if (date.isBefore(start) || !date.isBefore(end) || !completeDates.add(d.asText())) throw new IllegalArgumentException();
            }
            LocalDate watermark = OffsetDateTime.parse(manifest.path("businessWatermark").asText()).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate();
            if (end.isAfter(watermark.plusDays(1)) || completeDates.stream().anyMatch(day -> !LocalDate.parse(day).isBefore(watermark))) {
                throw new IllegalArgumentException();
            }
        }
        catch (RuntimeException e) { throw new InvalidData("manifest.json", 0, "INVALID_COVERAGE", "覆盖日期、完整业务日或业务水位不合法"); }

        Map<String, JsonNode> declared = new HashMap<>();
        JsonNode files = manifest.path("files");
        if (!files.isArray() || files.size() != 8) fail("manifest.json", 0, "INVALID_FILES", "清单必须声明 8 个规范 CSV");
        for (JsonNode f : files) {
            String name = f.path("name").asText();
            if (declared.put(name, f) != null || !f.path("rows").isIntegralNumber() || !f.path("rows").canConvertToLong() || f.path("rows").asLong(-1) < 0
                    || !f.path("sha256").asText().matches("[0-9a-fA-F]{64}")) {
                fail("manifest.json", 0, "INVALID_FILES", "文件声明重复或行数/校验和无效");
            }
        }

        Set<String> stores = new LinkedHashSet<>();
        Set<String> products = new HashSet<>();
        Map<String, Order> orders = new HashMap<>();
        Map<String, Long> itemAmounts = new HashMap<>();
        Map<String, Payment> payments = new HashMap<>();
        Map<String, Long> refundAmounts = new HashMap<>();
        Set<String> trafficDays = new HashSet<>();
        Set<String> inventoryDays = new HashSet<>();
        ArrayNode storeList = CommerceJson.MAPPER.createArrayNode();
        ArrayNode tables = CommerceJson.MAPPER.createArrayNode();
        ArrayNode missing = CommerceJson.MAPPER.createArrayNode();
        ObjectNode byStore = object();
        ObjectNode sourceStatus = object();
        for (Table table : Table.values()) sourceStatus.put(table.source, "COMPLETE");

        for (Table table : Table.values()) {
            JsonNode file = declared.get(table.filename());
            Path csv = directory.resolve(table.filename());
            if (file == null || !Files.isRegularFile(csv)) fail(table.filename(), 0, "MISSING_FILE", "缺少规范 CSV 或清单声明");
            if (!digest(csv).equalsIgnoreCase(file.path("sha256").asText())) fail(table.filename(), 0, "CHECKSUM_MISMATCH", "文件 SHA-256 与清单不一致");
            Set<String> uniqueKeys = new HashSet<>();
            long rows;
            try {
                rows = CommerceCsv.read(csv, table, (row, line) -> {
                    validateFields(table, row, line);
                    if (!row.get(0).equals(subject.tenantId()) || !row.get(1).equals(manifest.path("datasetVersionId").asText())) {
                        fail(table.filename(), line, "SCOPE_MISMATCH", "记录的租户或数据版本与清单不一致");
                    }
                    String store = row.get(2);
                    if (!subject.storeIds().contains(store)) fail(table.filename(), line, "STORE_FORBIDDEN", "数据包含未授权管理的店铺");
                    if (!uniqueKeys.add(table.key(row))) fail(table.filename(), line, "DUPLICATE_KEY", "同一租户、版本和店铺内的业务主键重复");
                    if (table != Table.STORES && !stores.contains(store)) fail(table.filename(), line, "MISSING_STORE", "店铺引用不存在");
                    ObjectNode counts = byStore.has(store) ? (ObjectNode) byStore.get(store) : object();
                    counts.put(table.source, counts.path(table.source).asLong() + 1);
                    byStore.set(store, counts);
                    try {
                        switch (table) {
                            case STORES -> {
                                if (!"Asia/Shanghai".equals(row.get(5))) fail(table.filename(), line, "INVALID_TIMEZONE", "店铺业务时区必须为 Asia/Shanghai");
                                stores.add(store);
                                storeList.add(object("storeId", store, "name", row.get(3), "platform", row.get(4)));
                            }
                            case PRODUCTS -> products.add(key(store, row.get(3)));
                            case ORDERS -> orders.put(key(store, row.get(3)), new Order(timestamp(row.get(4)), amount(row.get(6))));
                            case ORDER_ITEMS -> {
                                String order = key(store, row.get(3));
                                require(orders.containsKey(order) && products.contains(key(store, row.get(5))), table, line, "MISSING_REFERENCE", "订单项的订单或商品引用不存在");
                                require(amount(row.get(6)) > 0 && amount(row.get(6)) <= Integer.MAX_VALUE, table, line, "INVALID_QUANTITY", "商品数量必须为正整数");
                                itemAmounts.merge(order, amount(row.get(7)), Math::addExact);
                            }
                            case PAYMENTS -> {
                                String orderKey = key(store, row.get(4));
                                Order order = orders.get(orderKey);
                                require(order != null, table, line, "MISSING_ORDER", "支付订单引用不存在");
                                Payment payment = new Payment(timestamp(row.get(5)), amount(row.get(6)));
                                require(payments.putIfAbsent(orderKey, payment) == null, table, line, "DUPLICATE_PAYMENT", "同一订单只允许一笔规范成功支付");
                                require(!payment.paidAt().isBefore(order.createdAt()), table, line, "INVALID_EVENT_TIME", "支付时间早于订单创建时间");
                                require(payment.amount() == order.expected(), table, line, "PAYMENT_AMOUNT_MISMATCH", "支付金额与订单商品实付不一致");
                                require(inRange(businessDate(payment.paidAt()), start, end), table, line, "OUTSIDE_COVERAGE", "支付业务日超出清单覆盖范围");
                            }
                            case REFUNDS -> {
                                String orderKey = key(store, row.get(4));
                                Payment payment = payments.get(orderKey);
                                require(payment != null, table, line, "MISSING_PAYMENT", "退款必须引用本快照内的成功支付");
                                LocalDateTime time = timestamp(row.get(5));
                                require(!time.isBefore(payment.paidAt()), table, line, "INVALID_EVENT_TIME", "退款成功时间早于支付时间");
                                require(inRange(businessDate(time), start, end), table, line, "OUTSIDE_COVERAGE", "退款业务日超出清单覆盖范围");
                                refundAmounts.merge(orderKey, amount(row.get(6)), Math::addExact);
                                require(refundAmounts.get(orderKey) <= payment.amount(), table, line, "REFUND_EXCEEDS_PAYMENT", "订单全量累计退款超过成功支付金额");
                            }
                            case TRAFFIC -> {
                                LocalDate day = LocalDate.parse(row.get(3));
                                require(inRange(day, start, end), table, line, "OUTSIDE_COVERAGE", "流量业务日超出覆盖范围");
                                amount(row.get(4));
                                if (quality(table, row.get(5), line)) trafficDays.add(key(store, day.toString()));
                                else missing(missing, sourceStatus, table.source, store, day.toString(), "SOURCE_" + row.get(5));
                            }
                            case INVENTORY -> {
                                require(products.contains(key(store, row.get(3))), table, line, "MISSING_PRODUCT", "库存商品引用不存在");
                                LocalDate day = LocalDate.parse(row.get(4));
                                require(inRange(day, start, end), table, line, "OUTSIDE_COVERAGE", "库存业务日超出覆盖范围");
                                amount(row.get(5));
                                require(amount(row.get(6)) <= 1440, table, line, "INVALID_STOCKOUT_MINUTES", "每日缺货时长必须在 0–1440 分钟内");
                                if (quality(table, row.get(7), line)) inventoryDays.add(key(store, row.get(3), day.toString()));
                                else missing(missing, sourceStatus, table.source, store, day.toString(), "SOURCE_" + row.get(7));
                            }
                        }
                    }
                    catch (InvalidData e) { throw e; }
                    catch (RuntimeException e) { throw new InvalidData(table.filename(), line, "INVALID_VALUE", "字段类型、数值范围或时间格式错误"); }
                });
            }
            catch (java.nio.charset.CharacterCodingException e) { throw new InvalidData(table.filename(), 0, "INVALID_ENCODING", "CSV 必须采用合法 UTF-8 编码"); }
            if (rows != file.path("rows").asLong()) fail(table.filename(), 0, "ROW_COUNT_MISMATCH", "实际记录数与清单不一致");
            tables.add(object("name", table.source, "rowCount", rows));
        }
        if (stores.isEmpty()) fail("stores.csv", 0, "EMPTY_STORES", "快照必须包含至少一家已授权店铺");
        for (var entry : orders.entrySet()) {
            if (!itemAmounts.containsKey(entry.getKey()) || itemAmounts.get(entry.getKey()) != entry.getValue().expected()) {
                fail("order_items.csv", 0, "ALLOCATION_MISMATCH", "每个订单的明细分摊合计必须等于商品实付金额");
            }
        }
        for (String store : stores) {
            for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
                String day = date.toString();
                if (!completeDates.contains(day)) {
                    missing(missing, sourceStatus, "payments", store, day, "BUSINESS_DAY_NOT_CLOSED");
                    missing(missing, sourceStatus, "refunds", store, day, "BUSINESS_DAY_NOT_CLOSED");
                }
                if (!trafficDays.contains(key(store, day))) missing(missing, sourceStatus, "traffic_daily", store, day, "MISSING_OR_INCOMPLETE_DAILY_RECORD");
            }
        }
        for (String product : products) {
            String store = product.substring(0, product.indexOf('\u0000'));
            for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
                if (!inventoryDays.contains(product + '\u0000' + date)) missing(missing, sourceStatus, "inventory_daily", store, date.toString(), "MISSING_OR_INCOMPLETE_DAILY_RECORD");
            }
        }
        JsonNode declaredStatus = manifest.path("sourceStatus");
        for (Table table : Table.values()) {
            String state = declaredStatus.path(table.source).asText("COMPLETE");
            if (!List.of("COMPLETE", "PARTIAL", "INVALID", "MISSING").contains(state)) fail("manifest.json", 0, "INVALID_SOURCE_STATUS", "来源状态不合法");
            if (!"COMPLETE".equals(state)) {
                for (String store : stores) for (LocalDate date = start; date.isBefore(end); date = date.plusDays(1)) {
                    missing(missing, sourceStatus, table.source, store, date.toString(), "SOURCE_" + state);
                }
            }
        }
        ArrayNode warnings = CommerceJson.MAPPER.createArrayNode();
        if (!missing.isEmpty()) warnings.add("部分来源或业务日不完整；受影响指标返回空值，监控跳过缺失窗口");
        ObjectNode quality = object("status", missing.isEmpty() ? "COMPLETE" : "PARTIAL",
                "businessWatermark", manifest.path("businessWatermark").asText(), "publishedAt", OffsetDateTime.now(ZoneOffset.UTC).toString(),
                "warnings", warnings, "sourceStatus", sourceStatus, "missingCoverage", missing);
        return new Result(manifest, storeList, tables, quality, byStore);
    }

    private static void validateFields(Table table, List<String> row, long line) {
        for (int i = 0; i < row.size(); i++) {
            String field = table.columns.get(i), value = row.get(i);
            int max = field.equals("name") ? (table == Table.PRODUCTS ? 256 : 160)
                    : field.equals("platform") || field.equals("source_status") ? 32
                    : field.equals("quality_status") ? 16
                    : field.equals("tenant_id") || field.equals("dataset_version_id") || field.equals("store_id") || field.equals("business_timezone") ? 64 : 96;
            if (value.isEmpty() || value.length() > max || value.chars().anyMatch(c -> c < 32 || c == 127)) {
                fail(table.filename(), line, "INVALID_FIELD", "字段为空、超过长度限制或含控制字符：" + field);
            }
        }
    }

    private static boolean quality(Table table, String value, long line) {
        require(List.of("COMPLETE", "PARTIAL", "INVALID").contains(value), table, line, "INVALID_QUALITY", "来源质量状态不合法");
        return "COMPLETE".equals(value);
    }
    private static void missing(ArrayNode missing, ObjectNode states, String source, String store, String date, String reason) {
        if (missing.size() >= 100_000) fail("manifest.json", 0, "EXCESSIVE_INCOMPLETE_COVERAGE", "不完整覆盖记录超过上限，请缩小导入窗口");
        states.put(source, "PARTIAL");
        missing.add(object("source", source, "storeId", store, "date", date, "reason", reason));
    }
    static String digest(Path file) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[65_536]; int read;
            while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) throw new IllegalArgumentException(field);
        return value.asText();
    }
    static LocalDateTime timestamp(String value) { return LocalDateTime.parse(value.replace(' ', 'T')); }
    private static long amount(String value) {
        if (!value.matches("[0-9]{1,19}")) throw new IllegalArgumentException();
        return Long.parseLong(value);
    }
    private static String key(String... parts) { return String.join("\u0000", parts); }
    private static LocalDate businessDate(LocalDateTime time) { return time.atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.of("Asia/Shanghai")).toLocalDate(); }
    private static boolean inRange(LocalDate date, LocalDate start, LocalDate end) { return !date.isBefore(start) && date.isBefore(end); }
    private static void require(boolean ok, Table table, long line, String code, String message) { if (!ok) fail(table.filename(), line, code, message); }
    private static void fail(String file, long line, String code, String message) { throw new InvalidData(file, line, code, message); }
}
