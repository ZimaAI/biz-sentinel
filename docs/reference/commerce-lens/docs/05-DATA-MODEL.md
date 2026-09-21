# 05 · 数据库与领域对象

可执行形式的 MySQL DDL 草案见 `database/001-business.sql`、`002-management.sql`。DDL 是新增模块草案，必须在目标 MySQL 实际迁移测试后使用。本次未验证 MySQL 执行与性能。

## 1. 8 张业务表

| 表 | 粒度 / 主键组成（均含 tenant_id、dataset_version_id） | 关键字段 |
|---|---|---|
| `cl_store` | store_id | name、platform、business_timezone |
| `cl_product` | store_id、product_id | sku_code、name、category |
| `cl_order` | store_id、order_id | created_at_utc、source_status、expected_paid_cents |
| `cl_order_item` | store_id、order_id、item_id | product_id、quantity、allocated_paid_cents |
| `cl_payment` | store_id、payment_id；另唯一 order_id | order_id、paid_at_utc、amount_cents |
| `cl_refund` | store_id、refund_id | order_id、succeeded_at_utc、amount_cents |
| `cl_traffic_daily` | store_id、biz_date | visitor_sessions、quality_status |
| `cl_inventory_daily` | store_id、product_id、biz_date | closing_stock、stockout_minutes、quality_status |

所有跨表关系必须同时匹配 tenant、dataset、store，避免不同店铺相同 order_id 连接。源平台 ID 保留为字符串；业务事实不收集消费者姓名/电话/地址。

订单和明细保留全部规范记录，但指标时间锚定支付/退款事实。库存快照不是订单库存扣减流水，只能支持缺货相关信号。流量表不含商品粒度，不能臆造 SKU 转化率。

## 2. 管理扩展对象

`tenant/member/store_grant`：建立应用身份到租户/店铺的访问范围。生产可对接统一身份认证，数据库只存映射，不发明明文密码机制。

`dataset_version/ingest_job`：导入任务、来源水位、覆盖范围、文件校验和、状态、质量报告。唯一发布快照不可修改。

`metric_definition`：复合主键 tenant/metric/version；JSON 定义只引用编译器白名单。`run`：固定范围、状态、预算、计划版本、批准哈希、数据/指标/策略版本、租约。

`run_step/query_artifact/evidence`：步骤、参数化 SQL 与结果、可引用证据。`run_event`：按 run 单调递增 sequence；`report`：不可变版本化报告；`audit_event`：权限、审批、导出、规则修改等审计。

`anomaly_rule/anomaly_event`：规则版本、指标、店铺范围、阈值、观测窗口、告警状态和去重键。元数据表多于 8 张不违背“8 张业务事实/维表”边界。

## 3. 状态枚举

Dataset：DRAFT → VALIDATING → PUBLISHED / REJECTED；PUBLISHED → RETIRED 只改变可用性，不改历史数据。

Run：QUEUED、PLANNING、WAITING_APPROVAL、RUNNING、CANCEL_REQUESTED、SUCCEEDED、PARTIAL、FAILED、CANCELLED、EXPIRED、ACCESS_REVOKED。

步骤：PENDING、RUNNING、SUCCEEDED、FAILED、SKIPPED。证据断言：SUPPORTED、CONTRADICTED、INSUFFICIENT。异常事件：OPEN、ACKNOWLEDGED、RESOLVED、SUPPRESSED。

## 4. 关键约束与索引

金额非负（派生净收款除外）、sessions/stock 数量非负、stockout_minutes 0..1440。退款累计上限、订单项分摊一致、源完整性跨行规则在 staging 校验，不虚称普通 CHECK 能完成跨表聚合约束。

支付与退款索引优先 `(tenant_id,dataset_version_id,store_id,event_time)`，并保留订单 Join 唯一键。Store/day 统计可加合适聚合缓存；不先上分库分表。以 EXPLAIN 与真实压测检验，不能保证单个 LIMIT 能限制扫描量。

## 5. 证据记录形状

```json
{
  "evidenceId":"E-001", "runId":"run_demo_01", "kind":"QUERY_RESULT",
  "datasetVersionId":"demo_20260921_v1", "metricManifestHash":"metrics-v1",
  "scope":{"storeIds":["s1","s2","s3"]},
  "queryArtifactId":"Q-001", "resultHash":"sha256:...",
  "supportedClaims":[{"claimId":"C-001","rowKey":"TOTAL","field":"paid_gmv"}],
  "quality":{"status":"COMPLETE"}
}
```

证据不得只存一段自然语言。结果至少保留列、类型、单位、完整行数、是否截断、聚合范围、校验和。大结果存受控文件对象，前端只拿业务 ID，不能直接给永久公开 URL。

## 6. 保留期和删除

建议默认：运行事件 30 天、聚合证据/报告 180 天、快照至少覆盖有效报告保留期；安全审计 180 天。以上是项目默认配置，不是通用法规要求。实际部署按组织要求调整。

清理按引用关系执行：有效报告引用的证据/快照不能删除；归档到期后报告显示“证据已过期，无法重算”，不能展示复现成功。租户注销的实际数据删除/合规要求另行审核。首版不宣称法律合规认证。
