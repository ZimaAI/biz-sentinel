# 商脉 CommerceLens 实施版

本项目在原 Spring Boot / Nuxt 项目中新增 Commerce 模块。`commerce` profile 仅扫描电商领域包，保留上游默认启动方式；此 profile 对旧问数、数据源管理、任意 SQL / MCP 等入口返回 404。

- [实施版 API 契约](openapi.yaml)：OpenAPI 3.1，接口/事件契约版本 `1.0`。
- [实现决策](decisions.md)、[实施状态](STATUS.md)：实现取舍与最新交付状态。
- [参考需求](../docs/reference/commerce-lens/README.md)：原始文档、原型和固定数据；参考契约保持不变。
- [真实后端 HTTP 验证记录](backend-http-validation.json)：MySQL 8.4 联调范围。
- [前端设计规范](../design.md)、[需求验收矩阵](acceptance.md)、[浏览器验收记录](browser-validation.json)：页面样式、需求映射与实际交互验证。

## 本地启动

需要可用的 Docker Desktop、JDK 17 或更高版本、Maven 3.9，以及现有 Nuxt 项目使用的 Node.js / pnpm。以下命令在仓库根目录运行；数据库、后端、前端分别启动，后两项保持终端运行。

```powershell
# 1. 初始化 / 启动专用 MySQL 8.4 容器
.\scripts\commerce-dev.ps1 database

# 等待 MySQL 完成初始化；可查看容器日志
docker logs --tail 30 biz-sentinel-commerce-mysql

# 2. 启动 Commerce 后端，默认 http://127.0.0.1:8065
.\scripts\commerce-dev.ps1 backend

# 3. 首次安装现有 Nuxt 项目的依赖
pnpm --dir data-agent-frontend-nuxt install --frozen-lockfile

# 4. 另开终端启动前端，默认 http://127.0.0.1:3000
.\scripts\commerce-dev.ps1 frontend

# 5. 查看本机自动生成的管理员凭据
.\scripts\commerce-dev.ps1 credentials
```

启动后打开 [商脉登录页](http://127.0.0.1:3000/commerce/login)，登录成功进入 `/commerce/overview`；`/commerce` 也会转到经营总览。前端通过 Nuxt 的 `/api/**` 代理请求后端，浏览器保持同源。后端存活检查为 `GET http://127.0.0.1:8065/api/commerce/v1/health`；它只说明服务已启动，不替代数据库和业务校验。

脚本首次运行会在被 Git 忽略的 `.commerce-local/development.env` 生成随机数据库密码及管理员密码，默认用户名 `admin`。密码没有固定演示值。后端首次启动通过正常导入校验路径发布 `docs/reference/commerce-lens/fixtures/business/t_demo` 的合成快照；已有数据版本时不重复导入。演示完整业务日为 **2026-09-20**，对比日 **2026-09-13**，不随机器日期改变。

MySQL 容器名为 `biz-sentinel-commerce-mysql`，本机绑定 `127.0.0.1:13316`，数据卷同名。停止数据库使用 `docker stop biz-sentinel-commerce-mysql`，再次启动脚本会复用容器和数据。保留数据卷时也应保留配套的本地环境文件；修改该文件不会自动更新已有 MySQL 用户密码或已创建管理员的 BCrypt 密码。

## 三个数据库身份

| 账号 | 数据库 | 本地脚本授予的权限 | 用途 |
|---|---|---|---|
| `cl_management` | `commerce_management` | SELECT / INSERT / UPDATE / DELETE / CREATE / INDEX / REFERENCES | 成员、审计、运行、审批、事件、证据、报告等版本化管理文档 |
| `cl_ingestion` | `commerce_analysis` | SELECT / INSERT / UPDATE / DELETE / CREATE / INDEX / REFERENCES | 初始化业务表，写入尚未发布的新快照 |
| `cl_query` | `commerce_analysis` | **仅 SELECT** | 指标查询与诊断工具 |

领域管理状态保存在 `cl_document`，使用行版本 CAS 和管理事务；状态与事件日志在同一个运行文档内提交。分析数据使用八张规范业务表，所有主键、查询谓词和关联都包含租户及快照版本。发布后的快照通过应用接口保持不可变，修订必须使用新的 `datasetVersionId`。

运行以持久租约和 fencing token 协调 worker。重启后重新获取租约，使用已提交步骤恢复；不依赖 SSE 连接存活，也不承诺外部模型调用 exactly-once。已有浏览器会话使用进程内 WebFlux session，后端重启后需要重新登录；运行和报告仍保存在管理库。

## 配置

下列变量由 `application-commerce.yml` 读取。脚本会加载本地环境文件，其中数据库密码、首次管理员密码和 fixture 路径已有生成值；模型配置可在启动后端前设置为进程环境变量，或写入本机环境文件。

| 环境变量 | 行为 / 默认值 |
|---|---|
| `COMMERCE_MANAGEMENT_URL` | 默认本机 13316 的 `commerce_management` MySQL JDBC URL |
| `COMMERCE_ANALYSIS_URL` | 默认本机 13316 的 `commerce_analysis`，导入和只读账号共用分析库 |
| `COMMERCE_MANAGEMENT_USER` / `COMMERCE_MANAGEMENT_PASSWORD` | 默认用户 `cl_management`；密码必填 |
| `COMMERCE_INGESTION_USER` / `COMMERCE_INGESTION_PASSWORD` | 默认用户 `cl_ingestion`；密码必填 |
| `COMMERCE_QUERY_USER` / `COMMERCE_QUERY_PASSWORD` | 默认用户 `cl_query`；密码必填 |
| `COMMERCE_INITIALIZE_SCHEMA` | 默认 `false`；本地脚本设为 `true`，允许导入账号执行建表 |
| `COMMERCE_STORAGE_PATH` | 导入暂存目录根路径，默认 `.commerce-data` |
| `COMMERCE_ADMIN_USER` / `COMMERCE_ADMIN_PASSWORD` | 首次管理员账号；用户名默认 `admin`，密码为空时跳过 bootstrap |
| `COMMERCE_TENANT` / `COMMERCE_STORES` | 首次管理员租户与店铺，默认 `t_demo` / `s1,s2,s3` |
| `COMMERCE_FIXTURE_DIRECTORY` | 显式开发数据目录；留空时不自动导入合成数据 |
| `COMMERCE_ALLOWED_ORIGINS` | 默认 `http://localhost:3000,http://127.0.0.1:3000` |
| `COMMERCE_COOKIE_SECURE` | 本地 HTTP 默认 `false`；HTTPS 部署设 `true` |
| `COMMERCE_PLANNER` | 默认 `deterministic`；使用模型时设 `model` |
| `COMMERCE_MODEL_BASE_URL` | 模型提供方基础地址；默认请求路径 `/v1/chat/completions` |
| `COMMERCE_MODEL_NAME` | 提供方认可的模型名 |
| `COMMERCE_MODEL_API_KEY` | 仅服务端读取的模型凭据 |

`COMMERCE_PLANNER=deterministic` 显式使用本地确定性规划器：根据真实聚合证据选择店铺比较、订单/客单价分解、SKU 和库存调查，并在运行/报告中标记 `DETERMINISTIC_LOCAL`。此模式没有调用大模型。`model` 模式沿用 `LlmService` 与 Spring AI 接口，模型仅能生成批准计划内的结构化工具行动；它无法提交 SQL 或扩大租户、店铺和日期范围。

模型模式示例（替换为实际提供方配置）：

```powershell
$env:COMMERCE_PLANNER = 'model'
$env:COMMERCE_MODEL_BASE_URL = 'https://your-provider.example'
$env:COMMERCE_MODEL_NAME = 'your-model'
# COMMERCE_MODEL_API_KEY 通过本地环境或秘密管理服务注入
.\scripts\commerce-dev.ps1 backend
```

部署到 HTTPS 环境时配置实际受信任 Origin、Secure Cookie 和持久存储，关闭开发 fixture bootstrap；为三种数据库身份保留独立凭据。数据库建表权限可在初始化阶段使用，运行期权限按部署流程收敛。模型调用可能向配置的模型提供方发送用户问题、批准计划和有范围的聚合证据；不发送数据库连接凭据或原始客户明细。

## 会话与写接口

1. `GET /api/commerce/v1/auth/session` 获取匿名 `SESSION` Cookie 和 `data.csrfToken`。
2. 携带相同 Cookie、`X-CSRF-Token`，向同一路径 `POST {"username":"…","password":"…"}`。
3. 登录成功后保存更新后的 Cookie 和新的 CSRF token。所有 `POST` / `PATCH` / `DELETE`，包括退出登录，都携带该 token。
4. `GET /me` 返回当前角色、授权店铺和 `authzVersion`；租户来源于服务端会话，不能通过请求自选。

创建运行与上传导入包需要 `Idempotency-Key`。创建运行在 24 小时内同键同规范请求返回原任务，同键不同请求返回 409。审批必须提交当前 `expectedRunVersion`、`planVersion` 和 `planHash`；拒绝审批会终止当前任务，修改问题后重新创建。

`GET /runs/{runId}/events` 仅订阅持久化 SSE。原生 EventSource 使用 Cookie，重连优先采用 `Last-Event-ID`，初次历史重放使用 `after`；事件包含序号，客户端需去重。取消通过显式接口执行；关闭页面或断开 SSE 不会取消运行。

## 标准 ZIP 导入

只有租户管理员可以导入及发布。ZIP 根目录必须恰好包含以下九个普通文件，不能嵌套文件夹、重复文件、符号链接或加密条目：

```text
manifest.json
stores.csv
products.csv
orders.csv
order_items.csv
payments.csv
refunds.csv
traffic_daily.csv
inventory_daily.csv
```

文件头、字段顺序与时间格式以 [固定示例目录](../docs/reference/commerce-lens/fixtures/business/t_demo) 和 [业务数据模型](../docs/reference/commerce-lens/docs/05-DATA-MODEL.md) 为准。CSV 使用 UTF-8；`*_at_utc` 为无时区 UTC 时间，业务日期使用 `Asia/Shanghai`。清单声明 `schemaVersion=1.0`、`currency=CNY`、`timezone=Asia/Shanghai`、租户/快照 ID、覆盖日期、完整业务日、水位及八个 CSV 的行数/SHA-256。

HTTP 上传字段名是 **`file`**，接口为 `POST /ingestions`，还需 Cookie、CSRF 和幂等键。HTTP 压缩包上限 **32 MiB**，解压合计上限 **256 MiB**，清单上限 **1 MiB**。不要使用参考契约草案中的旧字段名 `archive`。

服务校验唯一支付、跨表租户/快照/店铺一致性、支付/退款时间、每订单项分摊和全部累计退款上限等。响应可能为 `READY_TO_PUBLISH` 或 `REJECTED`；逐文件错误位于 `errors`。数据缺失与真实零值分开处理，流量/库存局部缺失可作为 `PARTIAL` 质量记录；受影响的指标返回 null。

完成校验后，向 `POST /ingestions/{jobId}/publish` 提交 `{"expectedVersion":任务当前版本}`。发布时重新校验暂存文件和授权，写入新分析快照并提交可见的发布标记；SQL 写入失败不会暴露为已发布数据。由于管理库与分析库使用独立事务，管理提交前崩溃可能留下不可见的新版本分析行，重试会清理这些未发布行后重建。已发布版本不能原地覆盖。

## 数字与证据

八个指标固定为已实现的 v1 目录。金额和比率由后端 BigDecimal 计算，以十进制字符串返回；金额单位是 **分**。总览汇总先合并分子/分母，再计算客单价和比率。支付、退款、会话独立聚合；SKU GMV 只累加订单项分摊实付。

查询单元格的 `<metric>_baseline` / `_delta` / `_change_ratio` 分别为基期、绝对变化、相对变化。比率的 `_delta` 已转换为百分点 `PP`。分母为零、无可比基线和缺失来源有各自的 null 原因。总览额外返回服务端计算的贡献摘要、总览证据 ID、趋势证据 ID 和真实趋势日期范围。

报告中的数值主张绑定到证据行/字段和注册变换。数学分解与库存相关信号不会表述为已证明的运营因果。报告可在 `PRIVATE` / `TEAM` 间切换，团队共享仍检查同租户和完整店铺授权；报告、证据、导出、历史运行和 SSE 每次访问都重新鉴权。

## 验证范围

后端专项测试命令：

```powershell
mvn -pl data-agent-management -am '-Dtest=Commerce*Test' `
  '-Dsurefire.failIfNoSpecifiedTests=false' '-Dspotless.skip=true' test
```

已执行的验证包括原始 127,282 条支付 fixture 导入、H2 MySQL 模式的精确金标准、上海日界/UTC 左闭右开、订单多明细/多退款防扇出、零分母/缺流量、越权范围、证据授权撤回、审批 CAS、运行恢复与报告数值绑定，以及真实 Spring HTTP 会话/CSRF 校验。MySQL 8.4 的 HTTP 联调另外验证了总览、7 日趋势、运行、报告、SSE 重放和监控；记录见本目录 JSON。

实施契约的本地引用、37 个数据结构及真实 HTTP 读取响应校验记录见 [openapi-validation.json](openapi-validation.json)。最新测试数量和交付检查以 [STATUS.md](STATUS.md) 与 `data-agent-management/target/surefire-reports` 为准。真实模型凭据、平台生产数据、完整模型评测集及生产负载压测尚未执行；当前测试结果不表示已达到文档中的模型准确率、48 案例在线模型基准或生产吞吐目标。

前端运行 `pnpm --dir data-agent-frontend-nuxt test:unit` 和 `pnpm --dir data-agent-frontend-nuxt build`；新增页面均位于 `app/pages/commerce`，金额格式化与 SSE 恢复测试随工程保存。完整 Vue 类型检查需使用与项目 TypeScript 5.9.3 兼容的 vue-tsc；现有上游模块的 12 项类型错误记录在验收矩阵中。

运行 `python scripts/evaluate-commerce.py` 对默认合成快照执行固定集的真实 HTTP 确定性评测。如果已发布等价的测试快照，显式提供 `--dataset-version demo_20260921_ui_v2`；脚本仍严格核对版本、原始问题和黄金数值，不会静默切换基准。结果分别列出通过、失败及缺少前置数据/故障钩子的跳过项；重跑会写入 `implementation/deterministic-evaluation.json`。
