# 03 · 领域模型与指标口径

## 1. 时间、金额与事实边界

所有业务日按 `Asia/Shanghai` 定义；查询内部将日边界转换为 UTC 时间戳，使用 `[start,end)`。不能用浏览器本地时区，也不能对索引时间字段先格式化再过滤。原型固定演示日为 2026-09-20；日对比为 2026-09-13；与机器当前时间无关。

金额单位存储为整数分（BIGINT），API 使用十进制字符串表示，避免 JavaScript 大整数精度问题。Java 使用 BigDecimal 运算，金额展示保留 2 位，比例内部至少 8 位精度，最终仅展示时 HALF_UP。人民币单币种；首版商品实付不含运费/税费，源数据无法拆分时拒绝导入或明确另建指标版本，不能静默混算。

`payments` 只保存成功支付的归一化事实；一个订单首版恰有最多一条规范成功支付，不支持拆单多次成功支付和冲正。`refunds` 只保存成功的商品退款，可多次退款，以成功时间归属。关闭/退款后的订单仍保留支付事实，不能用当前订单状态抹掉历史 GMV。

## 2. 八个核心指标

| ID | 中文名 | 精确定义 | 支持维度 | 空/零规则 |
|---|---|---|---|---|
| `paid_gmv` | 支付 GMV | 窗口内成功支付的商品实付分之和 | day、store；SKU 走订单项分摊金额专用计划 | 无事实为 0，但数据缺失为 null |
| `paid_orders` | 支付订单数 | 窗口内规范成功支付对应订单数 | day、store | 无事实为 0 |
| `aov` | 客单价 | paid_gmv / paid_orders | day、store | 分母为 0 → null / ZERO_DENOMINATOR |
| `refund_amount` | 成功退款额 | 窗口内退款成功的商品金额之和 | day、store | 同 paid_gmv |
| `net_receipts` | 净收款额 | 同窗口 paid_gmv − refund_amount | day、store | 可为负；不是收入确认，更不是利润 |
| `refund_intensity` | 退款金额强度 | 同窗口 refund_amount / paid_gmv | day、store | GMV=0 → null；允许 >100% |
| `visitor_sessions` | 访客会话数 | 店铺日流量的 sessions 求和 | day、store | 非去重人数；不能称跨店 UV |
| `order_conversion_rate` | 支付订单转化比 | paid_orders / visitor_sessions | day、store | 不是用户级转化率；分母0→null |

**重要命名**：退款金额强度并非某批订单的退款率；它混合了当前支付与历史订单当期退款。若用户问“本月订单退款率”，必须澄清这是 cohort 口径，首版不支持，不能换名偷答。

支付订单转化比是店铺日级订单数与会话数的经营代理指标，没有逐会话支付漏斗。超过 100% 时不截断，要提示源定义/数据质量异常；不用于声称用户行为因果。

## 3. 聚合规则与 Join 风险

金额和订单数可按互斥店铺/日期相加；比率与客单价不可对日值/店铺值直接平均，必须合并分子和分母。访问会话可加总但不宣称跨店用户去重。

不能直接连接 `payments × order_items × refunds` 再 SUM，会产生扇出重复。总览采用各事实表先独立聚合到相同 `(tenant,dataset,store,day)` 粒度，再做集合/连接；SKU GMV 专用查询只连接支付、订单项和商品，按订单项的分摊实付求和，校验每订单项合计等于支付金额。

SKU 仅支持 `paid_gmv` 和以它为基础的变化贡献。SKU 支付订单数、SKU 转化比、SKU 退款额/退款率、SKU 净收款均不在首版支持矩阵。禁止从订单退款随意分摊到 SKU；不能把整单支付额赋给每个 SKU。

## 4. 对比与基线

默认单日经营对比：当前完整日与前一周同一星期日；7 日对比：当前连续 7 个完整日与紧邻前 7 日。日区间在响应中给出实际日期，UI 不只写“同比”。首版不做去年同比。

变化量 `delta=current−baseline`；相对变化 `delta/baseline`，baseline=0 时相对变化为 null 并显示“无可比基线”；0→正数可以显示“新增”，不能显示无穷百分比。比率差以百分点 pp 表达，不能混淆 `4.00%−4.48% = −0.48 pp` 与相对降幅约 10.71%。

监控使用历史 8 个同星期完整日为候选参考，至少 4 个有效日才启用规则；数据不足标记 INSUFFICIENT_HISTORY。经营页面的对比与监控参考集合必须分别显示，不能把不同基线混为同一数。

## 5. 指标版本发布

定义包括 metricId、version、displayName、unit、grain、supportedDimensions、sourceFacts、eventTimeField、requiredDatasets、compilerKey、dependencies、zeroPolicy 和状态。仅允许预先实现的 compilerKey；管理员不能通过 UI 保存任意 SQL 或表达式代码。

草稿→固定数据校验→审批发布；发布后不可原地修改，生成新版本。运行创建时绑定完整版本清单 `metricManifestHash`；报告绑定同一清单。发布新版本不会改变旧报告数值。停用指标阻止新运行，但历史报告仍按当前权限读取。

## 6. QuerySpec 示例与边界

```json
{
  "metricIds": ["paid_gmv", "paid_orders", "aov"],
  "dimensions": ["store"],
  "dateRange": {"start": "2026-09-20", "endExclusive": "2026-09-21"},
  "comparison": "PREVIOUS_WEEK_SAME_DAYS",
  "filters": [],
  "limit": 100
}
```

这里不允许 tenantId、SQL、表名、数据库连接、授权店铺集合或指标版本。用户在运行请求中提供 `requestedStoreIds`，服务端验证它是当前授权的子集后形成不可由模型修改的 ScopeContext。任何显式无权店铺导致整次请求拒绝，不能静默去掉后假装分析完整。

前端或模型只选择语义 ID；Java 编译器决定事实表、时间列、Join 路径、租户/快照/店铺谓词、参数与单位。JSON Schema 只是结构校验，还必须做能力矩阵、日期跨度、维度组合和预算校验。

## 7. 数据完整性优先

空数据、真实零值、源缺失、导入失败、统计未封账是不同状态。查询响应中返回 `quality.status`：COMPLETE / PARTIAL / STALE / INVALID。缺任意必需来源时对应复合指标为 null，而不是缺源补0。

订单与支付质量校验：成功支付唯一、金额非负、支付不早于创建、订单项分摊合计一致、退款不早于支付、每订单全量累计退款不超过支付、所有 Join 的 tenant/dataset/store 一致。不能仅按当前退款窗口检查累计退款上限。
