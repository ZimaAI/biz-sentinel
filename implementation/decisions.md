# 实施决策

## ADR-009 电商部署隔离与登录

保留上游默认启动行为，新增 `commerce` profile 仅扫描领域包。会话 Cookie + CSRF/Origin 验证；账号凭据使用 BCrypt，租户/授权店铺来自管理库成员映射。启动配置只用于首次建立管理员映射。生产统一身份可替换登录适配器，不能相信浏览器提供 tenantId。新增 `/auth/session` 协议补足参考 OpenAPI 缺失的登录与 CSRF 获取。

## ADR-010 持久领域文档与 CAS

管理对象保存在新增 `cl_document(kind,id,tenant_id,revision,body)` 表中，业务查询仍使用完整八张规范化分析表。使用有版本的 JSON 文档减少运行状态、步骤和事件变更时的跨表同步：运行状态及事件日志在同一行 CAS 中原子提交；管理事务负责审批/审计等多文档一致性。不同 worker 通过租约、fencing 与 revision 约束提交。该实现代替参考草案的十五张管理表，不修改上游初始化表。扩展大规模列表/事件保留期时可迁移到专门表，当前分页规模限制需要纳入压测。

## ADR-011 模型与本地确定性诊断

`commerce.planner=model` 使用上游 LlmService 约定及 Spring AI 兼容模型接入，输出仅为受控领域动作。没有模型凭据时显式使用 `deterministic` 规划器，仍执行真实数据库查询和证据驱动分支，并在运行中标记规划模式；不得把该模式的结果包装成真实模型评测。模型 Token 缺失标 UNKNOWN。

## ADR-012 正式契约补充

保留参考契约作为设计源。正式实现补充运行 dateRange/comparison/steps/budget/usage/clarification、报告 visibility、数据源水位、监控质量门禁状态。对比数字以查询 cells 的 `_baseline`、`_delta`、`_change_ratio` 字段返回；前端只格式化显示。指标目录为编译器支持的不可变 v1 定义，不允许用户上传 SQL 或表达式。金额仍为分的十进制字符串。
