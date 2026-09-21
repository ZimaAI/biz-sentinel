# 数据库资产说明

`001-business.sql`：8张规范化业务表，目标MySQL8.4；`002-management.sql`：15张Commerce管理扩展表，不覆盖上游；`003-query-templates.sql`：Java命名参数模板，不能直接交给mysql客户端执行。

本次没有运行MySQL或迁移工具。上线前用Testcontainers/MySQL真实执行DDL、约束、导入、查询、索引和权限测试。普通CHECK不能校验跨行退款总额；必须在staging完成全量关联校验。

## CSV映射与导入顺序

stores→cl_store；products→cl_product；orders→cl_order；order_items→cl_order_item；payments→cl_payment；refunds→cl_refund；traffic_daily→cl_traffic_daily；inventory_daily→cl_inventory_daily。CSV列顺序与表字段一致。时间字段保存UTC，无时区后缀；展示转换Asia/Shanghai。

每个租户的包在`fixtures/business/t_demo`或`t_other`中，有独立manifest。真实上传一个包只能对应当前授权租户，不能把多个租户混入同一导入任务。t_other仅用于隔离测试。

正式导入由Java服务读取manifest→校验sha256→逐表参数化批写staging→校验关系/金额/覆盖→发布新版本。禁止运营上传SQL，禁止使用查询账号执行导入。发布后不能修改同一dataset_version_id数据。

本包提供CSV而非巨量INSERT脚本，避免把种子数据绑死到高权限数据库会话。生成器可重复执行；样例数据不是生产平台数据。

## 数据库身份

管理账号仅写管理表；导入账号仅写staging/未发布快照；查询账号仅SELECT指定分析表或受控视图。不要把root密码写入代码。数据库部署由开发环境注入凭据，示例不包含密码。

`001`与`002`部署在不同schema下；表间跨schema引用由应用校验。管理模块与上游用户/租户的映射需要Gate0核实真实身份表后接入，不把新增cl_member当作可独立安全登录的实现。
