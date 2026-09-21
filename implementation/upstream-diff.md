# CommerceLens 上游基线

- 固定提交：`db6bb683fb4e29b3768719d81620df2df09a65d4`。
- 仓库初始工作区干净，无 `.codegraph` 索引；未创建索引。
- Java 17 编译目标；本机 JDK 21.0.8 / Maven 3.9.16；保留 Spring Boot 3.4.8、Spring AI 1.1.2、Alibaba Graph 1.1.2.2。
- 保留 Nuxt 4.3 / Vue 3.5 / Vuetify 3.11 / ECharts 6 工程和依赖锁。
- 实际安全配置仅保护 `/api/stream/search` API Key，其他上游管理路径默认 permitAll。电商 profile 仅扫描 `commerce` 包，同时统一拒绝非 Commerce API，避免旧入口绕过。
- 原 GraphController 以订阅取消停止图；新运行通过独立 Worker 持久运行，SSE 只订阅事件。
- 复用 StateGraph 和 LlmService 接口；新增指标语义、授权上下文、查询编译、导入、运行/审批/证据/报告/监控，不宣称这些是上游原生功能。
- 原有 DataAgentApplication 仅增加可配置扫描包；默认值保留上游行为。

## 基线验证

`mvn -pl data-agent-management -Dtest=AgentApiKeyReactiveAuthenticationManagerTest,GraphControllerTest -DfailIfNoTests=false -Dcheckstyle.skip -Dspotless.skip=true test`

实际匹配并执行 GraphControllerTest 6 项，0 失败、0 错误；另一个名称未匹配，不计作通过。真实通用模型样例未执行（无模型凭据）。
