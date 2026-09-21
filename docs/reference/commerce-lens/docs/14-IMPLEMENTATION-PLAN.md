# 14 · 分阶段实施计划

按验收门槛推进，不用不可验证的工期承诺。每阶段都产出代码、测试、执行记录和剩余问题，不能仅完成文档后标记功能完成。

| 阶段 | 输入与实施 | 产出 | 放行标准 |
|---|---|---|---|
| Gate 0 上游基线 | 固定SHA、依赖锁、认证与执行链路、原有测试；核查main变化 | upstream-diff、基线运行记录、需修改文件列表 | 上游最小样例可运行；主要源码路径核实 |
| Gate 1 领域数据 | 8表、3数据库身份、导入校验、不可变版本、fixture对账 | migration、导入服务、数据集API | 完整与拒绝用例均通过，未发布不可查 |
| Gate 2 可信查询 | ScopeContext、8指标、QuerySpec、Compiler/Executor、审计 | /queries、/overview、指标目录 | 数值与权限测试100%，无任意SQL入口 |
| Gate 3 诊断运行 | 独立Commerce图、8受控工具、持久run/步骤/事件、审批与预算 | 真实Agent循环、证据登记与报告校验 | 动态分支扰动测试、审批冲突与取消通过 |
| Gate 4 产品前端 | 在原Nuxt工程实现页面和组件、用正式API替换Mock | 经营/诊断/异常/证据/报告完整闭环 | 无静默Mock；loading/error/权限状态可验 |
| Gate 5 监控与恢复 | 规则调度、去重、重放、租约/崩溃恢复、观测 | 自动异常事件、恢复Runbook | 数据缺失不误报；重连不重执行 |
| Gate 6 交付评测 | 固定集/盲测/基线对照/压测/故障注入、安全入口审计 | 完整报告、部署手册、演示录像 | P0用例全部通过，未达目标如实披露 |

## 最小垂直闭环

先做“给定一个授权店铺和一个日期→查询 paid_gmv→返回数值/口径/证据→前端显示”。该链路验证真实数据源与权限后，再增加指标、Agent、监控。不要把所有页面先做完再补数据安全。

## 后端实施顺序

domain metric/security→snapshot repository→compiler/executor→evidence→run state/event store→tools→graph→report validator→scheduler。避免Controller直接写SQL或调用模型拼接表达式。每个新包都要有职责和依赖规则，禁止循环依赖。

## 前端实施顺序

全局design tokens/布局→ScopeBar/MetricCard/QualityBadge→总览→证据抽屉→诊断状态机/审批→报告→异常与数据中心。前端新会话必须读取design.md、OpenAPI和后端实际DTO，不能只按截图推断字段。

## 待验证而非预先认定的技术风险

上游多图Bean注入、Graph状态序列化、MysqlSaver版本兼容、会话认证整合、SQL取消、Nuxt SSR与EventSource生命周期、默认模型/Embedding初始化依赖。每项先写最小集成测试，失败时记录替代决策而不是盲目整体升级。

## 变更治理

需求变更先更新PRD/指标/契约和ADR，再改代码与fixture。接口破坏性变更提升schemaVersion。旧报告保留原版本；前端不得用新公式重算旧报告。新增平台/币种/退款粒度属于新版本，不夹带进入v1。
