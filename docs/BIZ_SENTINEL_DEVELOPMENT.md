# Biz Sentinel 二次开发指南

## 仓库与分支

本项目基于 Spring AI Alibaba DataAgent，保留原项目完整提交历史。
初始化基线：`27e7baa85db868a7364a977031f8f705d5988f1c`。

- `origin`：`git@github.com:ZimaAI/biz-sentinel.git`，日常推送目标。
- `upstream`：`https://github.com/spring-ai-alibaba/DataAgent.git`，用于同步上游。
- `main`：稳定主分支，初始化时对应上游基线；后续接收验证过的版本。
- `develop`：二次开发集成分支，包含本指南。
- `feature/<功能名>`：从 `develop` 创建，每个功能独立开发，通过 PR 合入 `develop`。

本机已设置 `remote.pushDefault=origin`。其他机器克隆后如需同步上游，应执行：

```bash
git remote add upstream https://github.com/spring-ai-alibaba/DataAgent.git
git config remote.pushDefault origin
```

## 首次启动

准备 JDK 17+、MySQL 5.7+、Node.js 22+、pnpm 11+。Python 分析步骤还需要可访问的 Docker 服务。
默认使用 simple 向量库，无需先部署独立向量数据库。

在项目根目录操作，先创建管理数据库并导入表结构：

```bash
mysql -u root -p -e 'CREATE DATABASE IF NOT EXISTS saa_data_agent CHARACTER SET utf8mb4;'
mysql -u root -p saa_data_agent < data-agent-management/src/main/resources/sql/schema.sql
```

需要上游演示数据时，再导入 `sql/data.sql`；需要电商演示表时，再导入
`sql/product_schema.sql`、`sql/product_data.sql`。这些路径均相对于
`data-agent-management/src/main/resources/`。仅在自己的开发数据库中初始化示例。

在运行后端的终端中配置连接；密码用交互方式输入：

```bash
export DATA_AGENT_DATASOURCE_URL='jdbc:mysql://127.0.0.1:3306/saa_data_agent?useUnicode=true&characterEncoding=utf-8&allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai'
export DATA_AGENT_DATASOURCE_USERNAME=root
read -rsp 'MySQL password: ' DATA_AGENT_DATASOURCE_PASSWORD
echo
export DATA_AGENT_DATASOURCE_PASSWORD
./mvnw -pl data-agent-management spring-boot:run
```

默认后端端口为 `8065`。也可以在 IDE 中启动 `DataAgentApplication`，并为运行配置设置上述环境变量。
默认自动初始化关闭，保持 `DATA_AGENT_DATASOURCE_SQL_INIT=never` 即可。
根目录 `.env` 已被 Git 忽略，但 Spring Boot 不会自动读取它；应使用终端或 IDE 注入环境变量。

另开一个终端启动前端：

```bash
cd data-agent-frontend-nuxt
pnpm install
pnpm dev
```

访问 `http://localhost:3000`。前端在 `nuxt.config.ts` 中将 `/api/**` 和
`/nl2sql/**` 代理到 `http://localhost:8065`；修改后端端口时需同步调整。

首次使用按以下顺序配置：

1. 在模型配置中添加对话模型、Embedding 模型及对应 API Key。
2. 创建智能体，添加待分析数据库，选择数据表并初始化数据源。
3. 配置业务术语、语义模型和业务知识，按页面提示同步向量库。
4. 先验证一次简单 SQL 问答，再验证报告与 Python 分析。

管理数据库保存智能体等平台配置；待分析数据库在页面中单独添加。
模型密钥和数据库密码不要写入提交。

## 修改代码的位置

后端 Java 包根目录为 `data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/`。

| 开发内容 | 主要位置 |
| --- | --- |
| 页面、导航、组件与样式 | `data-agent-frontend-nuxt/app/pages/`、`components/`、`layouts/`、`assets/` |
| 前端接口、状态与复用逻辑 | `data-agent-frontend-nuxt/app/services/`、`stores/`、`composables/` |
| 后端接口与业务逻辑 | Java 包根目录下 `controller/`、`service/` |
| 数据结构与持久化 | Java 包根目录下 `entity/`、`mapper/`，以及 `src/main/resources/sql/` |
| Agent 节点行为与条件路由 | Java 包根目录下 `workflow/node/`、`workflow/dispatcher/` |
| 工作流节点注册与连接 | Java 包根目录下 `config/DataAgentConfiguration.java` |
| 提示词 | `data-agent-management/src/main/resources/prompts/` |
| 后端配置 | `data-agent-management/src/main/resources/application.yml` |

建议先跑通原版流程，再按独立功能修改。增加业务接口时同时维护请求/响应结构、业务服务、
持久化和前端调用；增加 Agent 节点时同时维护节点、图注册、路由、状态传递和失败处理。
已有数据库的结构变更应提供独立升级脚本，仅修改 `schema.sql` 不会自动升级已有表。

## 日常开发与验证

```bash
git switch develop
git pull --ff-only origin develop
git switch -c feature/your-feature
# 修改代码并完成验证
git add <本次修改的文件>
git commit -m "feat: describe your feature"
git push -u origin feature/your-feature
```

在自己的 GitHub 仓库创建 PR，目标选 `develop`；集成验证后，再将 `develop` 合入 `main`。

根据改动执行相应检查：

```bash
# 根目录：后端测试与构建
./mvnw clean verify

# 前端目录：单元测试与构建
cd data-agent-frontend-nuxt
pnpm test:unit
pnpm build
```

涉及模型、数据库或 Docker 的集成测试需配置对应依赖。
上游 `.github/workflows/build-and-test.yml` 的作业限定了原仓库名，迁入本仓库后会跳过；
正式启用自动化验证时，需要调整该条件及工作流的分支过滤，使其覆盖 `develop`。
此次初始化未修改 CI，也未执行应用构建或端到端测试。

## 同步上游

通过单独分支合并并验证，保留共享分支历史：

```bash
git fetch upstream
git switch develop
git pull --ff-only origin develop
git switch -c chore/sync-upstream-YYYYMMDD
git merge upstream/main
# 如有冲突，解决后 git add 对应文件，再 git commit；随后运行相关测试
git push -u origin chore/sync-upstream-YYYYMMDD
```

创建 PR 合入自己的 `develop`。同步分支名中的日期每次替换为实际日期。

## 进一步阅读

- [上游快速开始](QUICK_START.md)
- [架构设计](ARCHITECTURE.md)
- [开发者指南](DEVELOPER_GUIDE.md)
- [知识配置](KNOWLEDGE_USAGE.md)
- [高级功能](ADVANCED_FEATURES.md)
- [原项目许可证](../LICENSE)
