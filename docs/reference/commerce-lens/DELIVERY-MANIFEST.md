# 交付边界与阅读入口

交付范围：18份专项规格文档、design.md、Codex交接、OpenAPI与JSON Schema、8指标目录、8业务表和15管理表DDL草案、参数化SQL模板、合成CSV/金标准/生成器、48条待执行真实Agent/集成用例、独立校验脚本、7页可交互离线前端、Nuxt接入参考、8张页面实截图。

`handbook.html` 是离线网页文档导航与全篇阅读入口；`CommerceLens_Complete_Specification.md` 是合并Markdown。以分篇文档和contracts中的规范文件为维护源，合并文件由构建脚本生成。

**不包含**上游源码副本、已经运行的Java服务、真实Nuxt构建产物、真实电商数据或凭据。生产实现仍需按Gate完成。上游main读取事实未锁定SHA，禁止称为已完成源码审计。

原型在浏览器中本地聚合小规模安全整数的合成数据；正式金额契约使用十进制字符串和服务端确定性计算。截图展示的Agent步骤是模拟，所有页面已标记。

开发入口：README → CODEX-HANDOFF → docs/02-UPSTREAM-ANALYSIS → docs/14-IMPLEMENTATION-PLAN。

上游出处与许可证核查入口：docs/18-SOURCES.md。本包未打包上游源码或字体。发布二开项目时必须保留实际使用的上游许可和版权声明。
