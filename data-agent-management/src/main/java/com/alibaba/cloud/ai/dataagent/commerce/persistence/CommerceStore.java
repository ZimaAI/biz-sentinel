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
package com.alibaba.cloud.ai.dataagent.commerce.persistence;

import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceException;
import com.alibaba.cloud.ai.dataagent.commerce.support.CommerceJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

/** Versioned domain documents; one row CAS atomically commits a run and its event log. */
@Repository
@Profile("commerce")
public class CommerceStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    public CommerceStore(@Qualifier("commerceManagementDataSource") DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        jdbc.execute("CREATE TABLE IF NOT EXISTS cl_document (kind VARCHAR(48) NOT NULL, id VARCHAR(160) NOT NULL, tenant_id VARCHAR(64) NOT NULL, revision BIGINT NOT NULL, body LONGTEXT NOT NULL, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(kind,id))");
        jdbc.execute("CREATE TABLE IF NOT EXISTS cl_schema_version (version INTEGER PRIMARY KEY, applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM cl_schema_version WHERE version=1", Integer.class) == 0) {
            try { jdbc.update("INSERT INTO cl_schema_version(version) VALUES(1)"); } catch (DuplicateKeyException ignored) { }
        }
    }
    public ObjectNode create(String kind, String id, String tenant, JsonNode body) {
        ObjectNode copy = ((ObjectNode) body).deepCopy(); copy.put("_revision", 1);
        try { jdbc.update("INSERT INTO cl_document(kind,id,tenant_id,revision,body) VALUES(?,?,?,?,?)", kind,id,tenant,1,copy.toString()); }
        catch (DuplicateKeyException ex) { throw new CommerceException(409,"ALREADY_EXISTS","资源已经存在"); }
        return copy;
    }
    public ObjectNode findOne(String kind, String id) {
        List<ObjectNode> result = jdbc.query("SELECT body FROM cl_document WHERE kind=? AND id=?", (rs,n) -> CommerceJson.read(rs.getString(1)),kind,id);
        return result.isEmpty() ? null : result.get(0);
    }
    public ObjectNode get(String kind, String id) {
        ObjectNode value=findOne(kind,id);
        if (value==null) throw new CommerceException(404,"RESOURCE_NOT_FOUND","资源不存在或不可见");
        return value;
    }
    public List<ObjectNode> find(String kind, String tenant) {
        return jdbc.query("SELECT body FROM cl_document WHERE kind=? AND tenant_id=? ORDER BY updated_at DESC,id", (rs,n)->CommerceJson.read(rs.getString(1)),kind,tenant);
    }
    public List<ObjectNode> all(String kind) {
        return jdbc.query("SELECT body FROM cl_document WHERE kind=? ORDER BY updated_at,id", (rs,n)->CommerceJson.read(rs.getString(1)),kind);
    }
    public ObjectNode update(String kind, String id, long expectedRevision, UnaryOperator<ObjectNode> mutation) {
        ObjectNode old=get(kind,id);
        if(old.path("_revision").asLong()!=expectedRevision) throw conflict();
        ObjectNode next=mutation.apply(old.deepCopy()); next.put("_revision",expectedRevision+1);
        int changed=jdbc.update("UPDATE cl_document SET revision=?,body=?,updated_at=CURRENT_TIMESTAMP WHERE kind=? AND id=? AND revision=?",expectedRevision+1,next.toString(),kind,id,expectedRevision);
        if(changed!=1) throw conflict();
        return next;
    }
    public <T> T transaction(Supplier<T> work) { return transactions.execute(status -> work.get()); }
    private CommerceException conflict() { return new CommerceException(409,"VERSION_CONFLICT","内容已变化，请刷新后重试"); }
}
