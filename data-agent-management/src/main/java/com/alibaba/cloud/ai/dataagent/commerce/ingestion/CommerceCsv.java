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

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Fixed CSV schemas; identifiers never originate in uploaded data. */
final class CommerceCsv {

    enum Table {
        STORES("stores", "cl_store", "tenant_id,dataset_version_id,store_id,name,platform,business_timezone", 2),
        PRODUCTS("products", "cl_product", "tenant_id,dataset_version_id,store_id,product_id,sku_code,name,category", 2, 3),
        ORDERS("orders", "cl_order", "tenant_id,dataset_version_id,store_id,order_id,created_at_utc,source_status,expected_paid_cents", 2, 3),
        ORDER_ITEMS("order_items", "cl_order_item", "tenant_id,dataset_version_id,store_id,order_id,item_id,product_id,quantity,allocated_paid_cents", 2, 3, 4),
        PAYMENTS("payments", "cl_payment", "tenant_id,dataset_version_id,store_id,payment_id,order_id,paid_at_utc,amount_cents", 2, 3),
        REFUNDS("refunds", "cl_refund", "tenant_id,dataset_version_id,store_id,refund_id,order_id,succeeded_at_utc,amount_cents", 2, 3),
        TRAFFIC("traffic_daily", "cl_traffic_daily", "tenant_id,dataset_version_id,store_id,biz_date,visitor_sessions,quality_status", 2, 3),
        INVENTORY("inventory_daily", "cl_inventory_daily", "tenant_id,dataset_version_id,store_id,product_id,biz_date,closing_stock,stockout_minutes,quality_status", 2, 3, 4);

        final String source;
        final String sqlTable;
        final List<String> columns;
        final int[] keys;

        Table(String source, String sqlTable, String header, int... keys) {
            this.source = source;
            this.sqlTable = sqlTable;
            this.columns = List.of(header.split(","));
            this.keys = keys;
        }

        String filename() { return source + ".csv"; }

        String key(List<String> row) {
            return Arrays.stream(keys).mapToObj(row::get).reduce((a, b) -> a + '\u0000' + b).orElseThrow();
        }

        String insertSql() {
            return "INSERT INTO " + sqlTable + " (" + String.join(",", columns) + ") VALUES ("
                    + String.join(",", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        }
    }

    @FunctionalInterface
    interface RowConsumer { void accept(List<String> row, long line); }

    static long read(Path file, Table table, RowConsumer consumer) throws IOException {
        var decoder = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(file), decoder))) {
            String header = limitedLine(reader, table.filename(), 1);
            if (header != null && header.startsWith("\uFEFF")) header = header.substring(1);
            if (header == null || !parse(header, table.filename(), 1).equals(table.columns)) {
                throw new InvalidData(table.filename(), 1, "INVALID_HEADER", "CSV 表头必须与标准字段及顺序一致");
            }
            long count = 0;
            String line;
            while ((line = limitedLine(reader, table.filename(), count + 2)) != null) {
                List<String> values = parse(line, table.filename(), count + 2);
                if (values.size() != table.columns.size()) {
                    throw new InvalidData(table.filename(), count + 2, "INVALID_COLUMN_COUNT", "CSV 字段数量错误");
                }
                consumer.accept(values, count + 2);
                if (++count > 2_000_000) throw new InvalidData(table.filename(), count + 1, "TOO_MANY_ROWS", "单表超过导入行数上限");
            }
            return count;
        }
    }

    private static String limitedLine(BufferedReader reader, String file, long line) throws IOException {
        StringBuilder value = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1) {
            if (c == '\n') break;
            if (c == '\r') {
                reader.mark(1);
                if (reader.read() != '\n') reader.reset();
                break;
            }
            value.append((char) c);
            if (value.length() > 16_384) throw new InvalidData(file, line, "ROW_TOO_LONG", "CSV 单行超过长度上限");
        }
        return c == -1 && value.isEmpty() ? null : value.toString();
    }

    private static List<String> parse(String line, String file, long number) {
        List<String> values = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        boolean endedQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\u0000') throw new InvalidData(file, number, "INVALID_CSV", "CSV 含非法控制字符");
            if (quoted) {
                if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') { cell.append('"'); i++; }
                else if (c == '"') { quoted = false; endedQuote = true; }
                else cell.append(c);
            }
            else if (c == ',') { values.add(cell.toString()); cell.setLength(0); endedQuote = false; }
            else if (c == '"' && cell.isEmpty() && !endedQuote) quoted = true;
            else if (endedQuote || c == '"') throw new InvalidData(file, number, "INVALID_CSV", "CSV 引号不合法");
            else cell.append(c);
        }
        if (quoted) throw new InvalidData(file, number, "INVALID_CSV", "CSV 引号未闭合；规范导入字段不支持嵌入换行");
        values.add(cell.toString());
        return values;
    }

    static final class InvalidData extends RuntimeException {
        final String file;
        final long line;
        final String code;
        InvalidData(String file, long line, String code, String message) {
            super(message); this.file = file; this.line = line; this.code = code;
        }
    }

    private CommerceCsv() { }
}
