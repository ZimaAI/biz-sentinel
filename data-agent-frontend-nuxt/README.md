# DataAgent Frontend (Nuxt 4)

本项目是 DataAgent 的前端部分，基于 Nuxt 4 框架构建，采用 TypeScript 开发，UI 库使用 Vuetify 3。

## 🛠 技术栈

- **框架**: [Nuxt 4](https://nuxt.com.cn/docs/4.x/getting-started/installation/) (基于 Vue 3 Composition API)
- **语言**: TypeScript (严格模式)
- **UI 库**: [Vuetify 3](https://vuetifyjs.com/zh-Hans/getting-started/installation/)
- **图标库**: [Material Design Icons (MDI)](https://pictogrammers.com/library/mdi/)
- **状态管理**: Pinia
- **HTTP 客户端**: Axios
- **包管理器**: pnpm

## 📂 目录结构规范

项目遵循 **Folder-as-Context (目录即上下文)** 模式，实现物理层面的上下文隔离：

- `app/components/`: UI 组件。公共组件存放于 `common/` 目录下。
- `app/composables/`: 组合式 API (Hooks)。
- `app/services/`: 业务服务层，统一封装 API 调用。
- `app/stores/`: Pinia 状态管理。
- `app/pages/`: 页面路由。
- `docs/`: 项目相关文档及 AI 上下文治理说明。
- `scripts/`: 自动化工具脚本。

## 🚀 快速开始

### 前置条件
- Node.js 20.x 或更高版本
- 已安装 pnpm (`npm install -g pnpm`)

### 安装依赖
```bash
pnpm install
```

### 启动开发服务器
```bash
pnpm dev
```

### 构建生产版本
```bash
pnpm build
```

## 🤖 AI 上下文治理 (Context Governance)

本项目引入了自动化的 AI 文档生成体系，确保 AI 能够精确理解代码逻辑：

- **生成文档**: 运行 `pnpm gen:ctx` 自动提取 JSDoc/TSDoc 并生成模块 `README.md`。
- **规范**: 开发者需在代码中编写标准的 JSDoc 注释，详情请参考 [docs/CONTEXT_GOVERNANCE.md](./docs/CONTEXT_GOVERNANCE.md)。

## 🔄 Git 工作流

Biz Sentinel 是个人开发项目，前后端统一使用 `main` 分支。开发前同步主分支，修改并验证后直接提交和推送：

```bash
git switch main
git pull --ff-only origin main
# 修改代码并完成相关验证
git add <本次修改的文件>
git commit -m "feat(frontend): describe your change"
git push origin main
```

仓库约定和上游同步步骤见 [Biz Sentinel 二次开发指南](../docs/BIZ_SENTINEL_DEVELOPMENT.md)。

## 📝 开发规范摘要

- **组件命名**: 使用 PascalCase (如 `ModelConfig.vue`)。
- **插槽语法**: 统一使用 `#slot` 简写，禁止使用 `v-slot:`。
- **属性顺序**: `class` -> `其他属性` -> `v-if` -> `事件绑定 (@click)`。
- **严格类型**: 严禁使用 `any`，所有变量和函数必须有明确类型定义。
- **文档先行**: 修改或新增代码时，必须同步更新 JSDoc 并运行 `pnpm gen:ctx`。

---
*Powered by DataAgent Team*
