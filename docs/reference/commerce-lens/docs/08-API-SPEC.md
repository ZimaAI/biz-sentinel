# 08 · 接口与事件规格

规范入口为 `contracts/openapi.yaml`。本章与 OpenAPI 描述的是**拟新增** `/api/commerce/v1` API，不是上游已有接口。数据类型、分页、错误和事件语义以本章约束共同生效。

## 1. 通用约定

同源会话 Cookie 鉴权；所有写接口校验 CSRF。租户从会话解析。资源 ID 不可预测但不可预测性不是权限。金额/分子分母返回十进制字符串；比率也是字符串，不返回 Infinity/NaN。分页使用不透明 cursor、limit 默认20上限100；排序在服务端白名单枚举中。

正常响应包含 requestId 和 data；错误体固定 `code/message/requestId/details`，message 可读但不泄漏内部 SQL 异常。`details` 仅包含安全的字段错误、缺失数据范围或冲突版本。

409 表示版本/状态冲突，422 表示合法 JSON 但业务范围不支持，403 表示无权执行，404 用于未知或不可见资源以防枚举（具体读取接口对无权限资源统一404），410 表示事件游标/报告证据已过期，429 表示预算或并发限制，503 表示依赖不可用。

## 2. 主要接口

| 方法 | 路径（省略前缀） | 行为 |
|---|---|---|
| GET | `/me` | 身份、角色、当前授权店铺、authzVersion |
| GET | `/overview` | 指定店铺/日期/对比的指标卡、趋势、店铺表 |
| GET | `/metrics` | 当前可见的已发布指标定义 |
| POST | `/queries` | 同步受控 QuerySpec 聚合，不接 SQL |
| POST | `/runs` | 幂等创建诊断运行，返回202和运行资源 |
| GET | `/runs` | 列表，仅当前可见运行 |
| GET | `/runs/{runId}` | 当前状态、计划版本、最新事件序号 |
| POST | `/runs/{runId}/approval` | 审批已绑定版本的计划 |
| POST | `/runs/{runId}/cancel` | 请求取消，幂等，返回202当前状态 |
| GET | `/runs/{runId}/events` | SSE，支持 Last-Event-ID 重放 |
| GET | `/evidence/{evidenceId}` | 聚合证据、SQL与元数据，逐次鉴权 |
| GET | `/reports` | 可见报告列表 |
| GET | `/reports/{reportId}` | 不可变结构化报告 |
| GET | `/reports/{reportId}/export` | Markdown 导出，重新鉴权 |
| GET/POST | `/anomaly-rules` | 规则列表与管理员创建 |
| PATCH | `/anomaly-rules/{ruleId}` | 带 expectedVersion 修改并产生新规则版本 |
| GET | `/anomalies` | 异常事件列表 |
| POST | `/anomalies/{anomalyId}/acknowledge` | 确认知晓，不代表异常已解决 |
| GET | `/datasets` | 已授权的数据版本与质量摘要 |
| POST | `/ingestions` | 上传规范化 ZIP 包进入 staging，不直接发布 |
| GET | `/ingestions/{jobId}` | 校验进度与逐文件错误 |
| POST | `/ingestions/{jobId}/publish` | 版本检查后原子发布快照 |

## 3. 创建与审批

```json
{
  "question": "昨天支付 GMV 为什么下降？",
  "requestedStoreIds": ["s1","s2","s3"],
  "dateRange": {"start":"2026-09-20","endExclusive":"2026-09-21"},
  "comparison": "PREVIOUS_WEEK_SAME_DAYS",
  "requireApproval": true
}
```

POST /runs 必须带 Idempotency-Key（客户端 UUID）。服务端以 `(tenant,subject,operation,key)` 查重，24h 内同键同规范请求返回同一 run；同键不同 body 返回409。requestId 不是幂等键。

返回 runId、conversationId、status、runVersion、scope、datasetVersionId、metricManifestHash、eventsUrl。`eventsUrl` 是本系统相对路径，不含令牌。自动选择数据集时必须选择覆盖完整范围/来源的最新已发布版本并回显，不能选择未发布版本。

审批请求：`decision=APPROVE|REJECT`、`planVersion`、`planHash`、`expectedRunVersion`、可选 comment。批准时范围/授权/快照/预算均重新核验。拒绝可以重新规划，次数计入预算；如果要求范围改变则新建 run。

## 4. SSE 协议

SSE 同源 Cookie 授权，不把 token 放在 URL。连接只订阅事件，不创建或恢复运行。原生 EventSource 可重连，首连在历史场景使用 `after` 参数；已有浏览器重连优先 Last-Event-ID。两者冲突时用 Last-Event-ID，且只在当前 run 解释序号。

```text
id: 17
event: evidence.ready
data: {"schemaVersion":"1.0","runId":"run_demo_01","sequence":17,"type":"evidence.ready","occurredAt":"2026-09-21T01:00:03Z","payload":{"evidenceId":"E-001"}}

```

事件类型：run.created、run.state.changed、plan.ready、approval.required、step.started、step.summary、evidence.ready、report.ready、run.completed、run.failed、run.cancelled、access.revoked。每15s发送无持久序号的心跳注释。UI不接收私有推理文本，step.summary只展示工具行动摘要。

数据库事件记录 `(runId,sequence)` 唯一，状态变更和事件写入同一事务。每 run 顺序保证，跨 run 不保证。客户端按 sequence 去重，断开后按旧序号重放；超过保留期返回410，客户端读取运行快照重建状态后从 latestSequence 继续。低权限下事件不得直接嵌入敏感结果，引用证据再次鉴权。

服务端先补发历史事件，再持续从持久事件存储扫描新事件；边界使用 sequence 而不是订阅瞬间避免漏事件。终态事件也在重放范围，前端收到后 close()。

## 5. 查询结果契约

返回 columns（field、label、unit）、rows（rowKey、dimensions、cells）、totals、comparisonRange、metricVersions、datasetVersionId、scope、quality、queryArtifactId、evidenceId、truncated、rowCount。真实无匹配事实与不完整数据是不同状态。

查询结果最多1000聚合行/1MB；传入模型最多100行。截断时必须带 `truncated=true`，不能用于“全部店铺”归因，分组贡献工具须先在数据库中得到精确总量和其余分组，或拒绝。

## 6. 取消与权限变更

取消请求由 CAS 标记 CANCEL_REQUESTED；Worker 在安全点停止，正在执行的 JDBC 请求尝试 cancel/超时，结果提交前再检查 runVersion/fencingToken，迟到结果不能覆盖 CANCELLED。取消已终态运行返回当前终态，不再新建事件。

权限变化的运行返回 ACCESS_REVOKED；SSE发送不含业务数据的 access.revoked 后关闭。旧报告/证据读取返回不可见，不自动按更小范围重新解释同一个报告。

## 7. 导入与导出

导入包必须含 `manifest.json` 和8个命名 CSV；限制压缩前后体积、文件数量与单行长度，拒绝路径穿越、符号链接和 ZIP bomb。不可上传任意 SQL。导入任务状态不能由用户直接修改为成功。

首版导出 Markdown，不附原始客户数据；Content-Disposition 文件名由服务端安全生成。导出审计包含 reportId、version、scopeHash 与发起者，不记录敏感正文。
