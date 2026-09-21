# Nuxt 正式前端接入参考（非已编译项目）

此目录不是另一套需要替代上游的前端。正式实现应在 DataAgent 现有 `data-agent-frontend-nuxt` 模块中新增 `/commerce/*` 页面，沿用当前 Nuxt / Vue / Vuetify / Pinia / ECharts 体系。

本目录提供与目标 API 对齐的类型、请求封装和 SSE 订阅参考。没有复制上游工程，也没有完成 `pnpm install`、Nuxt 构建或类型检查。`prototype/` 是单独的 HTML/CSS/JavaScript 交互原型；里面的本地模拟逻辑不能直接用作生产 API。

## 建议落位

```
app/
  pages/commerce/{index,analysis,anomalies,metrics,data,reports,runs}.vue
  components/commerce/{ScopePicker,MetricCard,PlanReview,EvidenceDrawer,ClaimCard}.vue
  composables/useRunEvents.ts
  services/commerceApi.ts
  types/commerce.ts
```

页面实现前必须实际读取根目录 `design.md`。路由、状态与逐页验收见 `docs/09-FRONTEND-SPEC.md`。运行页必须固定创建时的 storeIds、数据版本和时间范围；修改筛选创建新运行，不修改旧报告含义。

## 接入规则

同源 Cookie；写操作注入真实 CSRF token；不能使用硬编码身份或 tenantId。任何经营数字以服务端 `Cell.value` 为准，字符串金额用 Decimal 库或 BigInt 分处理，禁止默认 `Number()` 处理任意规模金额。原型的 Number 聚合仅适用于受安全整数检查的小型固定演示数据。

SSE handler 接收行动摘要和证据 ID，不接收私有思维链。收到 evidence.ready 后通过证据 API 再鉴权获取内容；不要仅凭事件缓存展示经营数据。收到 access.revoked 清空所有相关状态。原生 EventSource 不暴露 HTTP 状态码，断流后通过 getRun 判别终态/权限/资源可见性；代码使用有限退避重新订阅，410 由读取快照重新设定 after 处理。

`useRunEvents.ts` 是待集成参考：Vue 生命周期、后端的事件重放/事件保留期/部署反向代理都必须在真实 Nuxt+Java 环境测试，不能把这里的源码存在当作恢复能力已实现。
