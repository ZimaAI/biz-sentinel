# CommerceLens 实施验收记录

记录日期：2026-09-21。需求编号对应 [参考验收追踪矩阵](../docs/reference/commerce-lens/docs/15-ACCEPTANCE-TRACEABILITY.md)。本记录针对现有 Spring Boot / Nuxt 项目中的 Commerce 实现，参考文档和原型保持原样。

开发与验收顺序为：先完成后端、52 项专项测试及真实 MySQL 联调，再建立根目录 [design.md](../design.md) 并开发对应前端。当前运行使用 `DETERMINISTIC_LOCAL` 规划器和显式标注的合成业务快照；未调用真实大模型。

## 已执行的验证与证据

| 验证层级 | 已见结果 | 证据与边界 |
|---|---|---|
| 后端专项测试 | 最终 74 项全部通过：查询 11、导入 8、运行 44、API 2、监控 9；零失败、错误、跳过 | [测试目录][backend-tests]；覆盖 H2 MySQL 模式的真实事实聚合、版本 CAS、授权和故障状态，包含 pp 阈值文案及新增22项语义、全范围分解和贡献证据回归。最终日志为 `.commerce-local/backend-final-tests.log`。 |
| 前端单元测试 | 8 个测试文件、53 项通过 | Commerce 专项为 [金额、比率、日期与图表精度 28 项][format-tests] 和 [SSE 8 项][sse-tests]；另有上游既有测试 17 项。本地日志为 `.commerce-local/frontend-tests.log`。 |
| 前端生产构建 | Nuxt 生产产物构建通过 | 本地日志为 `.commerce-local/frontend-build-final.log`。构建成功与下方完整 Vue 类型检查结果分别记录，不代表既有类型错误已经消除。 |
| 完整 Vue 类型检查 | Commerce 新增范围 0 个错误；上游既有 12 个错误 | 完整检查仍有既有错误，不能写作全仓类型检查通过。既有错误位于旧服务相对导入、重复类型导出、旧测试字段及 Markdown 高亮空值处理；日志为 `.commerce-local/frontend-vue-typecheck.log`。 |
| 真实后端与数据库 | MySQL 8.4 三账号、最新代码重启后再次通过6组真实 HTTP smoke | [HTTP 验证记录](backend-http-validation.json)、[验证脚本][http-script]；最终快照为 `demo_20260921_ui_v2`。只读查询账号执行无影响的 `UPDATE ... WHERE 1=0` 仍被 MySQL 1142 拒绝。 |
| 实施契约 | 37 个 Schema、本地引用解析、15 个真实 HTTP 读取响应校验通过 | [实施 OpenAPI](openapi.yaml)、[契约校验结果](openapi-validation.json)。这 15 项为已记录的读取响应，不能外推为所有写接口均已完成 Schema 自动校验。 |
| 浏览器联调 | 已完成下方列出的真实 API 用户流程 | [浏览器验收记录](browser-validation.json)。浏览器连接 Nuxt 前端与 MySQL 后端，数字来自规范事实查询；未使用原型 JSON 冒充接口结果。移动导航检查宽度为 390 px。 |
| 浏览器 ZIP 发布 | **已验证：八个 CSV 校验通过，显式发布成功，版本列表已显示新快照** | [浏览器验收记录](browser-validation.json)；任务 `ing_4276bc41f14e4fd587eeeb02a72a09b3` 为 v3 / `PUBLISHED`，版本 `demo_20260921_ui_v2`，八表合计 **389,208 行**，质量 `COMPLETE`。1,983,119 字节 ZIP 保留原业务字段，仅更新版本 ID 与相应校验和；业务水位和发布时间分别展示已验证。 |

## FR-01—FR-11 实现与验收映射

下表 API 路径统一省略 `/api/commerce/v1` 前缀。“已验证”仅指本行明确列出的开发验证，不代表参考文档中的所有生产与模型评测目标已完成。

| 需求 | 实际实现文件 / 前端入口 | 实际 API | 已有验证与当前边界 |
|---|---|---|---|
| **FR-01 权限** | [CommerceSessionFilter][session]、[CommercePolicy][policy]、[CommerceMemberController][members]；[useCommerceContext][context]、[数据中心][data-page]、[CommerceMemberDialog][member-dialog] | `GET/POST/DELETE /auth/session`；`GET /me`；`GET/POST /members`；`PATCH /members/{id}`；所有历史运行、报告、证据、事件读取重新鉴权 | [CommerceApiTest][api-tests] 验证会话、CSRF 和关闭旧入口；[查询测试][query-tests] 验证跨租户/快照隔离、混合越权店铺拒绝、历史证据撤权；[运行测试][run-tests] 验证授权变化终止待审批任务。真实 MySQL smoke 验证成员授权隔离、历史证据撤权。浏览器已登录并切换可见店铺。成员更新的 `expectedVersion` 对应 `authzVersion`。 |
| **FR-02 指标** | [CommerceQuerySpec][query-spec]、[CommerceQueryService][query]；[指标字典][metrics-page]、[commerceFormat][format] | `GET /metrics`；`POST /queries` | [查询测试][query-tests] 覆盖八指标金标准、SKU 分摊、防多明细/多退款扇出、独立事实聚合、零分母、缺流量、上海业务日与 UTC 左闭右开边界。前端 28 项格式测试区分元/分、比率/百分点、真实零/缺失值。真实 MySQL 总值与固定事实一致；浏览器指标查询和证据入口已操作。 |
| **FR-03 总览** | [CommerceQueryService.overview][query]；[经营总览][overview-page]、[CommerceScopeBar][scope-bar]、[CommerceTrendChart][trend] | `GET /overview`，参数包含 `storeIds/start/endExclusive/comparison` | `overviewCarriesEvidenceAndServerComputedContributionWithSevenDayTrend` 验证总览、贡献摘要、七日趋势和证据引用；MySQL smoke 核对真实事实总计及趋势。浏览器全店支付 GMV 为 **486,200 元**，切换单店后为 **255,000 元**；证据抽屉展示 SQL。总览活动异常仅包含 `OPEN/ACKNOWLEDGED`。 |
| **FR-04 问数** | [CommerceRunService][run]、[CommercePlanner][planner]、[CommerceQuerySpec][query-spec]；[经营分析][analysis-page] | `POST /queries`；`POST /runs`；`GET /runs/{id}` | [运行测试][run-tests] 的利润拒绝、参数化不支持意图澄清及规范指标意图保留，结合 [查询测试][query-tests] 的任意 SQL / 未支持维度 / SKU 退款拒绝。浏览器已创建真实分析并看到计划。[固定集真实 HTTP 复测](deterministic-evaluation.json) 为31通过、0失败、17跳过，原问题/黄金断言未调整；该结果只代表已执行的确定性路径。 |
| **FR-05 动态诊断** | [CommerceRunGraph][graph]、[CommercePlanner][planner]、[CommerceRunService][run]、[CommerceReportBuilder][report-builder]；[经营分析][analysis-page] | `POST /runs`；`GET /runs/{id}`；`GET /runs/{id}/events` | `changedEvidenceChangesInvestigatedShopAndProductWithoutFixtureIdentifiers` 验证证据变化会改变调查店铺/商品；缺库存生成 `PARTIAL` 并保留限制。浏览器审批后执行到 `SUCCEEDED` 并产生报告。当前验证为本地确定性规划路径；真实模型规划、盲测和完整动态性实验未执行。 |
| **FR-06 审批** | [CommerceRunService][run]、[CommerceStore][store]；[经营分析][analysis-page] | `POST /runs/{id}/approval`，提交 `decision/planVersion/planHash/expectedRunVersion`；`POST /runs/{id}/cancel` | [运行测试][run-tests] 及 MySQL smoke 验证计划绑定、旧版本/并发审批冲突、幂等创建和授权变化。浏览器展示店铺、日期、预算和工具计划，经显式批准后执行成功。未把浏览器点击批准等同于全部故障注入用例已通过。 |
| **FR-07 证据** | [CommerceQueryService][query]、[CommerceReportBuilder][report-builder]、[CommerceRunService][run]；[CommerceEvidenceDrawer][evidence-drawer]、[CommerceClaimCard][claim-card]、[报告页][reports-page] | `GET /evidence/{id}`；`GET /reports`；`GET /reports/{id}`；`GET /reports/{id}/export`；`PATCH /reports/{id}/visibility` | [运行测试][run-tests] 拒绝编造数字、跨快照绑定和未知变换；[查询测试][query-tests] 验证结果截断不损坏精确总计及历史证据授权。HTTP smoke 验证报告与导出。浏览器已查看 SQL、证据及报告，导出操作收到成功反馈。库存与数学贡献作为线索/分解，未当作已证明因果。 |
| **FR-08 恢复** | [CommerceRunService][run]、[CommerceStore][store]；[useCommerceRunEvents][sse]、[运行审计页][runs-page] | `GET /runs/{id}/events` 支持 `Last-Event-ID` / `after`；`GET /runs/{id}`；`POST /runs/{id}/cancel` | [运行测试][run-tests] 覆盖过期租约接管、复用已提交步骤、不重复证据、取消后不能复活；[SSE 测试][sse-tests] 覆盖序号去重、缺口恢复、历史事件、换任务与撤权清理。MySQL smoke 验证持久事件重放；浏览器已回放 **24 条运行审计事件**。提交窗口进程崩溃、SQL 迟到等完整生产故障演练不因此自动视作完成。 |
| **FR-09 接入** | [CommerceCsv][csv]、[CommerceDatasetValidator][validator]、[CommerceIngestionService][ingestion]、[business-schema.sql][business-schema]；[数据中心][data-page]、[CommerceIngestionPanel][ingestion-panel] | `POST /ingestions`，multipart 字段为 `file`，携带 `Idempotency-Key`；`GET /ingestions/{id}`；`POST /ingestions/{id}/publish`；`GET /datasets` | [导入测试][ingestion-tests] 8 项覆盖完整 127,282 条支付事实导入、重复支付、对账、退款上限、租户/店铺 FK、事件时序、ZIP 路径/符号链接、SHA/暂存篡改、事务回滚、幂等与 `PARTIAL`。MySQL 初始快照和浏览器导入的新快照均已实际发布；`demo_20260921_ui_v2` 的八表合计 389,208 行、质量 `COMPLETE`，任务 v3 / `PUBLISHED`。数据中心按实际 `quality.sourceStatus` 与 `tables[].name/rowCount` 展示质量和八表计数，版本列表及独立水位/发布时间已由[浏览器验证](browser-validation.json)。 |
| **FR-10 监控** | [CommerceAnomalyService][anomaly]；[异常中心][anomalies-page]、[CommerceRuleDialog][rule-dialog] | `GET/POST /anomaly-rules`；`PATCH /anomaly-rules/{id}`；`GET /monitor-status`；`GET /anomalies`；`POST /anomalies/{id}/acknowledge` | [监控服务测试][anomaly-tests] 8 项及 [阈值测试][threshold-tests] 1 项覆盖至少四个参考日、缺失来源、MAD=0、pp/最小分母、同版本去重、冷却合并、规则 CAS、版本替代和两连续完整正常日恢复。浏览器已新增规则，真实 scheduler 产生告警，再标记为 `ACKNOWLEDGED`，没有误标为 `RESOLVED`。pp 文案修复及原转化比测试中的新增断言已在上一轮 52 项后端测试中通过：相对阈值为 null/缺失时，仅描述绝对变化阈值。 |
| **FR-11 评测** | [CommercePlanner][planner]、[CommerceRunService][run]、[CommerceAudit][audit]；[运行审计页][runs-page]；[HTTP 验证脚本][http-script]、[固定集执行脚本][evaluation-script] | 运行、证据、报告与事件接口保存数据集版本、指标清单哈希、计划和执行记录 | 已有可复现合成事实、金标准、测试和 HTTP 记录；运行页明确显示“未调用模型”或 Usage“未提供”。参考48项的[确定性执行结果](deterministic-evaluation.json)为 **31通过、0失败、17跳过**，缺少专用快照或故障/裁剪/浏览器前置条件的项目逐项保留原因。首轮10项失败的[原始记录](deterministic-evaluation-initial.json)保留，最终全部修复。真实 LLM、独立盲测、模型对照/消融、提供方 Usage 与 P50/P95 成本时延实验未执行。 |

## 浏览器验收清单

| 用户流程 | 本次结果 |
|---|---|
| 登录、进入 Commerce 工作台 | 已完成，使用真实会话和 CSRF。 |
| 全店总览 → 单店总览 | 已完成，支付 GMV 从 486,200 元切换到 255,000 元。该操作证明范围筛选；权限隔离另由后端授权测试与 HTTP smoke 证明。 |
| 打开查询证据与 SQL | 已完成，展示真实证据记录。 |
| 创建分析 → 核对计划 → 批准 → 执行完成 | 已完成，运行状态到达 `SUCCEEDED`，模式为 `DETERMINISTIC_LOCAL`。 |
| 查看报告与导出 | 已完成查看，浏览器导出收到成功反馈；HTTP 导出路径另有 smoke 验证。 |
| 历史运行审计 | 已完成，24 条事件回放。 |
| 指标字典查询当前范围 | 已完成，打开对应查询证据。 |
| 新增规则 → scheduler 告警 → 标记已知晓 | 已完成，实际状态为 `ACKNOWLEDGED`；恢复条件仍需两个连续完整正常日。 |
| 390 px 移动导航 | 已完成，移动导航展开与页面入口可操作。 |
| 导入完整八 CSV ZIP | 已上传、校验并显式发布成功。任务 `ing_4276bc41f14e4fd587eeeb02a72a09b3` 为 v3 / `PUBLISHED`；版本列表显示 `demo_20260921_ui_v2`，八表 389,208 行、质量 `COMPLETE`，水位与发布时间分别展示。 |

## 尚未执行 / 不计入本次通过结果

- 真实 LLM 调用及完整模型正确率、引用率、Token/费用/时延评测；当前确定性执行不能替代模型评测。
- 独立盲测、基线对照与消融实验，以及固定集17项跳过案例所需的专用快照或可控故障前置条件。31项确定性通过不代表48项全部通过。
- 真实电商平台生产数据接入、生产并发负载和百万订单压测、生产硬件吞吐目标。
- 全套预发布故障演练与备份恢复目标验收；现有租约/取消/SSE 测试仅证明其明确覆盖的场景。
- 全仓 Vue 类型检查通过：Commerce 范围无错误，但上游既有 12 项错误仍存在。

[backend-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/
[session]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/security/CommerceSessionFilter.java
[policy]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/security/CommercePolicy.java
[audit]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/security/CommerceAudit.java
[members]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/api/CommerceMemberController.java
[query-spec]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/query/CommerceQuerySpec.java
[query]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/query/CommerceQueryService.java
[run]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/run/CommerceRunService.java
[graph]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/run/CommerceRunGraph.java
[planner]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/run/CommercePlanner.java
[report-builder]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/run/CommerceReportBuilder.java
[store]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/persistence/CommerceStore.java
[csv]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/ingestion/CommerceCsv.java
[validator]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/ingestion/CommerceDatasetValidator.java
[ingestion]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/ingestion/CommerceIngestionService.java
[business-schema]: ../data-agent-management/src/main/resources/commerce/business-schema.sql
[anomaly]: ../data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/commerce/anomaly/CommerceAnomalyService.java
[api-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/CommerceApiTest.java
[query-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/query/CommerceQueryServiceTest.java
[run-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/run/CommerceRunServiceTest.java
[ingestion-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/ingestion/CommerceIngestionServiceTest.java
[anomaly-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/anomaly/CommerceAnomalyServiceTest.java
[threshold-tests]: ../data-agent-management/src/test/java/com/alibaba/cloud/ai/dataagent/commerce/CommerceAnomalyTest.java
[context]: ../data-agent-frontend-nuxt/app/composables/useCommerceContext.ts
[sse]: ../data-agent-frontend-nuxt/app/composables/useCommerceRunEvents.ts
[sse-tests]: ../data-agent-frontend-nuxt/app/composables/useCommerceRunEvents.test.ts
[format]: ../data-agent-frontend-nuxt/app/utils/commerceFormat.ts
[format-tests]: ../data-agent-frontend-nuxt/app/utils/commerceFormat.test.ts
[overview-page]: ../data-agent-frontend-nuxt/app/pages/commerce/overview.vue
[analysis-page]: ../data-agent-frontend-nuxt/app/pages/commerce/analysis.vue
[reports-page]: ../data-agent-frontend-nuxt/app/pages/commerce/reports.vue
[runs-page]: ../data-agent-frontend-nuxt/app/pages/commerce/runs.vue
[metrics-page]: ../data-agent-frontend-nuxt/app/pages/commerce/metrics.vue
[anomalies-page]: ../data-agent-frontend-nuxt/app/pages/commerce/anomalies.vue
[data-page]: ../data-agent-frontend-nuxt/app/pages/commerce/data.vue
[scope-bar]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceScopeBar.vue
[trend]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceTrendChart.vue
[evidence-drawer]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceEvidenceDrawer.vue
[claim-card]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceClaimCard.vue
[rule-dialog]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceRuleDialog.vue
[member-dialog]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceMemberDialog.vue
[ingestion-panel]: ../data-agent-frontend-nuxt/app/components/commerce/CommerceIngestionPanel.vue
[http-script]: ../scripts/verify-commerce.py
[evaluation-script]: ../scripts/evaluate-commerce.py
