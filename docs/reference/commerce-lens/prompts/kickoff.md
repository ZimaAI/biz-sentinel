# 交给 Codex 的开工提示词

你要基于 spring-ai-alibaba/DataAgent 实现 CommerceLens，而不是从零替换上游。

先实际读取 README.md、CODEX-HANDOFF.md、docs/02-UPSTREAM-ANALYSIS.md、docs/14-IMPLEMENTATION-PLAN.md。

第一步完成 Gate 0：在实际工作区取得上游源码，固定提交 SHA，核对模块路径、依赖版本、认证/SQL执行/Graph生命周期与前端结构；运行上游基线构建和必要测试。不能把文档中的 main 分支观察当作不可变源码事实。

接着阅读完整规格、contracts/openapi.yaml、contracts/metrics.json、database/ 和 evaluation/。按里程碑实现：指标及数据 → 权限与安全查询 → Agent运行与证据 → 正式Nuxt界面 → 故障和安全测试 → 交付。

每次新增或修改页面，先读取实际项目根目录 design.md。prototype/ 只是离线模拟参考，严禁直接把里面的定时器、虚拟身份、前端金额计算或样例事件当作生产后端能力。

保持首版8业务表8指标。模型使用受控工具，不执行任意SQL/Python。发现新需求需要成本、SKU退款映射或广告事实时，先报告边界，不编造字段和业务口径。

分别记录：已经实现并测试、已经实现但未验证、尚未实现。遇到外部平台或模型凭据缺失时说明阻塞点，不写假连接成功或假测评成绩。每个Gate结束产出可复现验收记录，按CODEX-HANDOFF中的验收策略推进。
