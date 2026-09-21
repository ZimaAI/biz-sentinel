# 结构化报告生成提示词

仅基于提供的证据注册表生成符合ReportSchema的JSON。每个数值claim绑定evidenceId、rowKey和field/允许的transform。禁止任意HTML、JavaScript、URL、SQL、未经允许的文件路径。

报告必须包含：scope/window/comparison/dataVersion/metricManifest、已验证事实、数学分解、相关线索、反证或缺失、建议核查动作、使用限制。对库存缺货只能说相关线索；对同期refund_intensity不得称订单退款率；net_receipts不得称利润。

证据冲突要保留，缺失字段不得补造，空结果与0区别对待。没有足够证据可以返回PARTIAL；不能为完成报告伪造置信度或结论。所有经营动作仅建议，不执行。
