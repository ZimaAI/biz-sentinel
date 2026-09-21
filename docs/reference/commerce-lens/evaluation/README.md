# 评测资产导航

先读 `VALIDATION-REPORT.md`，区分已执行的静态/数据/原型检查与尚未执行的生产测试。

`validate_fixtures.py`：仅标准库，独立检查CSV和金标准，生成fixture-validation.json。

`validate_contracts.py`：需要jsonschema、PyYAML，检查目标契约，生成contract-validation.json。

`test_prototype.py`：需要Playwright与Chromium，注入单文件HTML测试真实DOM交互并截图。生成prototype-validation.json；实际浏览器安装路径由CHROMIUM_PATH指定。

`cases.jsonl`：48条真实Agent/后端集成待测用例。其中权限、零值/缺失、重复支付、故障恢复和动态决策案例需要另外搭建对应状态或边界数据；不是仅靠主CSV即可全部执行。

`acceptance.feature`：关键业务BDD验收描述，尚未绑定Java步骤实现。

不将计划中的用例算进已通过检查，不把浏览器动画说成模型推理，不把0次真实模型调用说成低Token优化成果。
