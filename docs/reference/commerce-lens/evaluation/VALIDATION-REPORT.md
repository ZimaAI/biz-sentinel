# 实际验证记录 · 2026-09-21

## 已执行

| 层级 | 通过检查项 | 失败 | 实际证明范围 |
|---|---:|---:|---|
| 合成 CSV / 算术 | 94 | 0 | 校验和、行数、复合键、引用、事件时间、金额对账、SKU、分解及小型反例 |
| 契约静态检查 | 178 | 0 | JSON Schema、OpenAPI本地引用/参数、示例、无效QuerySpec与表声明计数 |
| 离线原型浏览器 | 36 | 0 | 页面、筛选、计划确认、取消、证据、报告导出、规则、边界及响应式视口 |

这些是**检查项数量**，不是三百多条生产端到端测试。尤其 OpenAPI 引用检查不能证明实际服务器按契约实现。

## 数据核对

共检查 2 个租户、16 个 CSV，合计 389,229 行，不含表头。主要演示租户有 3 店、57 日；另一租户包含相同外部订单 ID 的隔离反例。

当前（2026-09-20）支付 GMV 486,200.00 元，基准（2026-09-13）600,000.00 元；净变化 -113,800.00 元。3 店贡献分别 -105,000.00、-10,000.00、+1,200.00 元。主店核心 SKU 从252,000.00降至153,000.00元，差额-99,000.00元。当前净收款459,340.00元，不是利润。

CSV校验脚本独立读取事实聚合，不调用原型函数或生成器聚合函数。另用小型 SQLite 反例展示“支付×明细×退款”错误连接造成 fan-out；这不是 MySQL SQL 兼容性测试。

## 浏览器执行条件

安装的 Chromium + Playwright，页面内容通过 `page.set_content` 注入。当前容器浏览器对 `file://` 和 `http://127.0.0.1` 导航返回 `ERR_BLOCKED_BY_ADMINISTRATOR`，因此未验证这两种实际打开方式。页面所有 JavaScript 和 CSS 均已内嵌；无远端网络资源依赖。

检查到未捕获 JavaScript 错误 0 个。验证过 1600 像素主视口以及 1440、1024、800、390 像素宽度；移动验证为浏览器视口模拟，不是实机测试。截图来自实际渲染页面。

## 未执行，不能宣称通过

上游全仓库拉取、不可变SHA确认、Java编译、MySQL建表/迁移/查询、真实Nuxt构建、真实电商数据接入、真实LLM工具调用、真实SSE重连、鉴权中间件/租约/fencing/审批CAS故障测试、并发压测、安全审计及LLM评测均未执行。

`cases.jsonl` 的48条用例和 `acceptance.feature` 用于后续真实实现验收；它们的状态是 NOT_RUN_REAL_AGENT，不计入已通过数量。文档中的95%/100%等为验收目标，非本次模型效果成绩。

## 复现

```bash
python scripts/generate_fixtures.py
python evaluation/validate_fixtures.py
python -m pip install -r evaluation/requirements.txt
python evaluation/validate_contracts.py
python scripts/build_prototype.py
# 本机有 Chromium 时设置其路径，Windows可按自己的安装位置设置环境变量
CHROMIUM_PATH=/usr/bin/chromium python evaluation/test_prototype.py
```

依赖版本范围仅用于这组本地验证脚本；不是上游生产依赖锁。各检查的逐项名称及结果在对应 `*-validation.json`，报告导出样例在 `exported-report-sample.md`。
