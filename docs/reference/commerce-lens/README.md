# 商脉 CommerceLens · 电商经营分析与异常诊断 Data Agent

**交付版本：设计基线 v1.0 · 2026-09-21**  
基于 `spring-ai-alibaba/DataAgent` 的领域化二次开发方案。面向多店铺运营团队，让每一个经营结论都能回到指标口径、查询结果和数据版本。

> 本包交付的是完整开发规格、契约与数据资产、离线交互原型；不是已经接入电商平台的生产系统。原型中的企业、店铺、订单、异常和运行记录均为合成演示数据。未经实测的性能与评测数字均为验收目标，不是已达成成果。

## 从哪里开始

| 目的 | 文件 |
|---|---|
| 完整文档阅读 | `handbook.html` / `CommerceLens_Complete_Specification.md` |
| 快速体验产品 | `prototype/index.html`，直接用浏览器打开，无外部 CDN |
| 交给 Codex 开发 | `CODEX-HANDOFF.md` → `docs/02-UPSTREAM-ANALYSIS.md` → `docs/14-IMPLEMENTATION-PLAN.md` |
| 阅读完整需求 | `docs/01-PRD.md` |
| 理解业务与口径 | `docs/03-DOMAIN-AND-METRICS.md`、`contracts/metrics.json` |
| 理解架构与 Agent | `docs/04-ARCHITECTURE.md`、`docs/06-AGENT-DESIGN.md` |
| 接口与数据库 | `contracts/openapi.yaml`、`database/` |
| 前端设计与接入 | `design.md`、`docs/09-FRONTEND-SPEC.md`、`frontend-reference/` |
| 验收与复现实验 | `evaluation/`、`docs/12-TEST-AND-EVAL.md` |
| 查看上游取证 | `docs/18-SOURCES.md`、`upstream-baseline.json` |

## 已固定的产品边界

首版 8 张业务表：店铺、商品、订单、订单明细、成功支付、成功退款、店铺日流量、商品日库存。8 个核心指标：支付 GMV、支付订单数、客单价、成功退款额、净收款额、退款金额强度、访客会话数、支付订单转化比。

首版支持一个租户多个店铺、单一 MySQL 分析方言、人民币、Asia/Shanghai 业务日、只读经营分析、可审批的诊断计划、证据与报告。**不做自动调价、退款、投放、补货；不做预测；不把净收款写成利润；不开放任意 SQL/Python；没有订单项退款映射时拒绝 SKU 退款分析。** 平台自身的任务/审批/规则元数据允许写入管理库，不属于经营数据写操作。

## 文件结构

- `docs/`：产品、业务、架构、安全、数据、Agent、前端、运维、实施、验收等专项文档。
- `contracts/`：OpenAPI、查询/报告 JSON Schema、指标目录。
- `database/`：业务库和管理扩展库的 MySQL 建表草案、示例查询、CSV 导入说明。
- `fixtures/`：可复现的合成数据、原型数据、金标准期望结果。
- `prototype/`：离线可交互 HTML/CSS/JavaScript 原型，提供拆分源文件和单文件入口。
- `frontend-reference/`：Nuxt 集成位置与类型化 API 边界参考，不声称已完成上游前端合并构建。
- `evaluation/`：边界用例、固定问答集、数据校验和原型浏览器测试。
- `prompts/`：领域规划与证据报告提示词，以及开工交接提示词。
- `previews/`：从实际原型页面截取的页面预览。

## 原型运行

直接打开 `prototype/index.html` 即可。也可在本目录运行 `python -m http.server 8080`，访问 `http://localhost:8080/prototype/`。后者仅是静态文件服务，没有后端鉴权或真正的模型调用。原型页面始终显示“演示数据”。

## 验证与限制

本包的验证详情以 `evaluation/VALIDATION-REPORT.md` 为准。MySQL DDL、Java 改造建议及 Nuxt 接入参考需要在开发环境执行集成测试；本次未在容器中拉取、编译或运行上游完整仓库。上游网页核验基于读取时的 `main`，未获得不可变提交 SHA，开工第一步必须固定并复核版本。禁止把本包描述为生产已上线或安全审计通过。
