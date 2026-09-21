# 待在正式后端实现的验收场景；不是本次已执行 BDD 测试。
Feature: 可信经营诊断
  Scenario: 支付与退款事实分别聚合
    Given 一个订单有两个明细和两笔成功退款
    When 查询支付GMV、成功退款额和净收款
    Then 每笔成功支付仅统计一次
    And 每笔退款按自己的成功时间统计一次

  Scenario: 旧审批不能启动新计划
    Given 运行等待确认的计划版本为2
    When 用户提交计划版本1的批准请求
    Then 返回409
    And 不执行任何诊断步骤

  Scenario: 权限撤销后不可复用旧证据
    Given 旧报告范围包含s1和s2
    And 当前用户的s2权限被撤销
    When 用户查看旧报告或任一关联证据
    Then 返回统一不可见响应
    And 不返回原聚合数字也不静默缩减到s1

  Scenario: SSE订阅不拥有运行生命周期
    Given 运行已由后台Worker开始执行
    When 浏览器断开SSE并在10秒后重新订阅
    Then 后台运行不因断开而取消
    And 前端按事件序号去重和恢复

  Scenario: SKU退款分析被拒绝
    Given 退款源只有订单级退款
    When 用户询问SKU退款率
    Then 返回不支持的指标维度说明
    And 不将整单退款重复分配给各SKU
