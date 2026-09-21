# 18 · 上游与来源记录

读取日期：2026-09-21；来源均为上游官方仓库。以下URL指向main而非不可变SHA，可能继续变化；本包只记录选定页面/源码观察，未完整克隆或编译。文档中未注明“已观察”的架构、表、接口、产品能力与数值目标均为本项目设计。

| 编号 | 来源 | 支撑范围 |
|---|---|---|
| S01 | https://github.com/spring-ai-alibaba/DataAgent | 主要工程目录、项目定位、许可证、基础启动导航 |
| S02 | https://github.com/spring-ai-alibaba/DataAgent/blob/main/pom.xml | 读取时Java/Spring相关声明版本 |
| S03 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-frontend-nuxt/package.json | 读取时Nuxt/Vue/Vuetify/ECharts声明版本 |
| S04 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/controller/GraphController.java | 已观察的流式路由、取消时调用stop |
| S05 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/service/graph/GraphServiceImpl.java | 上游图执行与流上下文、停止/清理checkpoint路径 |
| S06 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/config/DataAgentConfiguration.java | 图节点注册、MysqlSaver/MemorySaver、编译中断配置 |
| S07 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-management/src/main/java/com/alibaba/cloud/ai/dataagent/workflow/node/SqlExecuteNode.java | 从图状态SQL到数据访问器的观察链路 |
| S08 | https://github.com/spring-ai-alibaba/DataAgent/blob/main/docs/ARCHITECTURE.md | 上游分层、StateGraph、人工反馈、检索与报告的官方设计说明 |
| S09 | https://github.com/spring-ai-alibaba/DataAgent/blob/main/docs/DEVELOPER_GUIDE.md | 上游环境/开发与扩展说明 |
| S10 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/docs/KNOWLEDGE_USAGE.md | 知识配置用途，用于区分解释性知识和本项目确定性语义层 |
| S11 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-management/src/main/resources/sql/schema.sql | 上游管理初始化表所在位置 |
| S12 | https://raw.githubusercontent.com/spring-ai-alibaba/DataAgent/main/data-agent-frontend-nuxt/nuxt.config.ts | 前端配置文件存在性；具体集成仍需固定SHA后核查 |

## 已知限制

Git远程访问在本次工作容器失败，网页读取部分源码成功。未获得可靠的当前提交SHA，因此未伪造版本号。没有对原仓库执行测试，不能证明上游整体安全/性能。所有新增内容为本次原创目标设计；不复制上游完整源码或宣称已经将补丁合入仓库。

## 数据来源

所有经营示例均由 `scripts/generate_fixtures.py` 生成；店铺名称和数值不表示任何真实商家。未使用用户私有订单、财务或消费者数据。源数据库接口、第三方平台授权、模型服务可用性不在本次已验证范围。
