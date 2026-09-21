# 02 · 上游核验与二次开发映射

核验日期 2026-09-21。以下“已观察”是上游文档或选定源码的事实；“拟新增”是本项目的设计。未执行全仓库编译、依赖漏洞扫描或完整安全审计。来源编号见 `18-SOURCES.md`。

## 1. 技术与入口基线

上游采用 `data-agent-management` 与 `data-agent-frontend-nuxt` 两个主要工程目录。根 POM 声明 Java 17、Spring Boot 3.4.8、Spring AI 1.1.2、Spring AI Alibaba 1.1.2.2；前端声明 Nuxt ^4.3.0、Vue ^3.5.27、Vuetify ^3.11.8、ECharts ^6.0.0。[S01-S03] 这些是读取时声明，不是锁文件解析结果，也不是新项目必须升级到的版本。

保留上游版本组合，在 Gate 0 固定 SHA、依赖锁和基线测试；不要为了二开随意升级框架。本文无法给出可靠的当前提交 SHA，`upstream-baseline.json` 中明确留空。

## 2. 已核查的实际扩展位置

下表后端路径均相对 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/`。

| 路径 | 已观察用途 | 二开方式 |
|---|---|---|
| `config/DataAgentConfiguration.java` | 注册 StateGraph、节点、边和 checkpoint 配置 | 新增独立 Commerce 图，不原地混入所有电商逻辑；显式 Qualifier 解决多图 Bean 歧义 |
| `service/graph/GraphServiceImpl.java` | 图执行、流上下文、人工计划恢复、结束清理 | 参考其接入方式，新增持久化 RunService/RunWorker，不能直接复用流订阅生命周期 |
| `controller/GraphController.java` | `GET /api/stream/search`、`POST /api/stream/stop` | 新增 `/api/commerce/v1` 任务型 API；旧入口关闭或统一授权 |
| `workflow/node/SqlExecuteNode.java` | 取生成 SQL，构造 DbQueryParameter，调用数据访问 | Commerce 路径替换为指标编译+安全只读执行器，不允许直接传入生成 SQL |
| `workflow/node/*` | 规划、SQL、语义检查、报告等节点集合 | 复用框架与模型服务；新增领域节点与严格结果 Schema |
| `src/main/resources/sql/schema.sql`（相对模块） | 上游管理表 | 只做有版本的新增迁移，不修改上游初始化脚本来代替迁移 |
| `data-agent-frontend-nuxt/package.json`（相对根） | Nuxt/Vue/Vuetify/ECharts 依赖 | 沿用原技术栈，新增经营路由及专用组件 |

这些源码路径由 [S04-S07] 核验；拟新增的 `commerce` 类名不存在于本次核验的上游事实集合中。

## 3. 可以复用与必须新建的边界

上游文档描述模型注册、业务/智能体知识检索、工作流、人工反馈与报告功能。[S08-S10] 复用这些基础设施不意味着上游已具备电商指标计算、店铺行级隔离或证据充分性验证。

| 能力 | 处理决策 |
|---|---|
| StateGraph、模型接入、基础观测 | 复用，并增加运行版本与领域字段 |
| 业务术语/知识检索 | 复用检索接口；仅召回当前租户/已授权范围知识 |
| Prompt 化语义知识 | 保留作解释；另建机器可执行指标目录，不能以 Prompt 代替口径执行器 |
| 通用 Text-to-SQL | 电商标准路径不执行其任意 SQL；可作为封闭管理员实验，默认关闭 |
| SQL 执行节点 | 新建 `SafeQueryExecutor` + `MetricSqlCompiler`，边界位于真实数据访问前 |
| 人工计划 | 参考上游机制，新增 `planVersion`、planHash、授权/快照绑定及审批 CAS |
| HTML 报告 | 改为 JSON 结构化报告+可信组件渲染，禁止任意 HTML/JS |
| Python 沙盒/MCP | 首版关闭经营用户入口，避免绕过领域工具策略 |

## 4. 任务恢复的关键差异

源码显示 GraphController 的订阅取消会触发停止；GraphServiceImpl 的停止路径会清理上下文并释放 checkpoint。[S04-S05] 因而“框架有 checkpoint”不能直接推导出“浏览器刷新后原任务可继续”。

新版采用持久化运行状态、独立 Worker、事件日志和独立 SSE 订阅。断开连接只删除订阅，不调用任务取消；只有显式取消才停止运行。checkpoint 与业务结果分开存储，租约和 fencing token 防止多 Worker 同时提交。

## 5. 持久化 checkpoint 已存在，但不是全部答案

上游配置可选择 MysqlSaver 或 MemorySaver，并配置人工反馈中断点。[S06] 新图应复用经过验证的 saver 接入方式，同时建立自己的运行状态和工具结果幂等存储。不要把“新增 MySQL checkpoint”包装为本项目原创，也不要认为换成 MySQL 就解决了授权撤回、重复执行、事件重放和审批并发。

## 6. 建议新增包与集成规则

```text
commerce/
  api/                  # 只接受领域 DTO 的 HTTP 接入
  application/          # RunService / OverviewService / ReportService
  domain/metric/        # MetricDefinition / QuerySpec / MetricRegistry
  domain/security/      # ScopeContext / PolicyDecision
  domain/diagnosis/     # Hypothesis / Evidence / DiagnosticReport
  infrastructure/query/# MetricSqlCompiler / SafeQueryExecutor
  infrastructure/run/  # RunRepository / RunLease / EventStore
  workflow/             # CommerceGraphConfiguration 与领域节点
  ingestion/            # CSV staging / 校验 / 快照发布
```

为既有单图 Bean 补充限定名/条件，确保通用图与 Commerce 图不会因 `StateGraph`、`CompileConfig`、`BaseCheckpointSaver` 按类型注入产生冲突。先写上下文启动测试验证，再扩展功能。

## 7. 必须补查的事项

Gate 0 核查实际认证拦截器、MCP/API Key 权限、各数据库访问链路、前端 `app/` 目录、pnpm lock、模型与向量库初始化依赖、所有可执行入口以及全量取消/异常流程。本文没有因未看到安全检查就断言上游没有安全机制；生产边界必须以实际调用链和对抗测试为准。

保留 Apache-2.0 相关许可和上游版权声明，明确新增模块与改动。第三方依赖许可证与发布要求由交付者在发布前核查。[S01]
