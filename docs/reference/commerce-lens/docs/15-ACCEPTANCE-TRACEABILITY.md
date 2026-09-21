# 15 · 验收追踪矩阵

| 需求 | 设计位置 | API/组件 | 必验用例 |
|---|---|---|---|
| FR-01 权限 | 07 §1–4 | ScopeContext / PolicyService | SEC-01~08；历史证据与SSE越权 |
| FR-02 指标 | 03 | MetricRegistry / metrics.json | MET-01~08、ZERO、JOIN |
| FR-03 总览 | 03/09 | overview / MetricCard | 当前/基准/7日比率均与SQL一致 |
| FR-04 问数 | 03/06 | QuerySpec / resolve_metric | 歧义销售额、SKU退款、利润拒绝 |
| FR-05 动态诊断 | 06/10 | CommerceGraph / tools | DYN-01~04，下一工具随证据变化 |
| FR-06 审批 | 06/08 | approval / DiagnosisPlan | 旧版本、重复审批、范围变更、过期 |
| FR-07 证据 | 05/06/09 | evidence / report validator | 数字、引用、版本、截断、因果边界 |
| FR-08 恢复 | 04/08 | RunWorker / RunEventStore | 断线重连、崩溃、fencing、取消迟到 |
| FR-09 接入 | 05/11 | ingestion / snapshot | 支付重复、跨日退款、完整性、重导 |
| FR-10 监控 | 10 | anomaly-rule / scheduler | MAD=0、历史不足、冷却、恢复 |
| FR-11 评测 | 12/13 | trace / evaluation | Usage缺失不能写0，复现实验记录 |

## 端到端验收脚本（生产实现必须执行）

1. 管理员导入并发布标准快照；再导入缺失流量快照验证拒绝或显式PARTIAL。
2. 运营经理授权s1/s2/s3，确认总览486,200元；单店运营授权s1，看不到其他店数据。
3. 发起“昨天支付GMV为什么下降”，确认对比日期与计划；篡改storeId或planVersion被拒绝。
4. 批准并看到实际工具运行；断开浏览器后任务继续，重连不生成新run或重复结果。
5. 查看GMV、店铺贡献、订单/AOV分解、SKU与库存证据；缺货只显示相关线索。
6. 导出报告，所有数字可以追溯到证据；旧报告不被全局范围切换改变。
7. 撤回s1权限后，原多店报告、证据、事件均不可再读取；不能只隐藏部分表格。
8. 重复运行监控不重复告警；连续两个完整正常日才恢复；缺失日不算正常。

## 不通过条件

任何经营库写操作、任意SQL绕过、越权聚合总计、模型补造字段/数字、把同期退款强度叫订单退款率、把净收款叫利润、原型Mock被当成真实结果、伪造压力测试或模型评测数据，均直接不通过。

本包静态/数据/浏览器检查结果在 VALIDATION-REPORT 中；上述真实后端步骤未实现前一律标记“待执行”。
