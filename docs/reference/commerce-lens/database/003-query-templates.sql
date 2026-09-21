-- DESIGN TEMPLATE, NOT A RAW MYSQL SCRIPT.
-- Named parameters (:name and :store_ids list) are bound by Java NamedParameterJdbcTemplate.
-- Scope is server-controlled; date bounds are UTC; quality checked BEFORE interpreting absence as zero.

-- Q1: shop-grain, two facts aggregate independently to avoid payment x refund multiplication.
WITH payment_agg AS (
  SELECT store_id, SUM(amount_cents) AS paid_gmv, COUNT(*) AS paid_orders
  FROM cl_payment
  WHERE tenant_id = :tenant_id AND dataset_version_id = :dataset_version_id
    AND store_id IN (:store_ids)
    AND paid_at_utc >= :start_utc AND paid_at_utc < :end_utc
  GROUP BY store_id
), refund_agg AS (
  SELECT store_id, SUM(amount_cents) AS refund_amount
  FROM cl_refund
  WHERE tenant_id = :tenant_id AND dataset_version_id = :dataset_version_id
    AND store_id IN (:store_ids)
    AND succeeded_at_utc >= :start_utc AND succeeded_at_utc < :end_utc
  GROUP BY store_id
), traffic_agg AS (
  SELECT store_id, SUM(visitor_sessions) AS visitor_sessions
  FROM cl_traffic_daily
  WHERE tenant_id = :tenant_id AND dataset_version_id = :dataset_version_id
    AND store_id IN (:store_ids)
    AND biz_date >= :start_date AND biz_date < :end_date
    AND quality_status = 'COMPLETE'
  GROUP BY store_id
)
SELECT s.store_id, COALESCE(p.paid_gmv,0) AS paid_gmv,
       COALESCE(p.paid_orders,0) AS paid_orders,
       COALESCE(r.refund_amount,0) AS refund_amount,
       COALESCE(p.paid_gmv,0)-COALESCE(r.refund_amount,0) AS net_receipts,
       COALESCE(p.paid_gmv,0) / NULLIF(p.paid_orders,0) AS aov,
       COALESCE(r.refund_amount,0) / NULLIF(p.paid_gmv,0) AS refund_intensity,
       t.visitor_sessions,
       COALESCE(p.paid_orders,0) / NULLIF(t.visitor_sessions,0) AS order_conversion_rate
FROM cl_store s
LEFT JOIN payment_agg p ON p.store_id=s.store_id
LEFT JOIN refund_agg r ON r.store_id=s.store_id
LEFT JOIN traffic_agg t ON t.store_id=s.store_id
WHERE s.tenant_id=:tenant_id AND s.dataset_version_id=:dataset_version_id
  AND s.store_id IN (:store_ids)
ORDER BY s.store_id
LIMIT :limit;

-- Q2: SKU GMV uses item allocated payment, never repeats whole-order payment per item.
SELECT i.store_id, i.product_id, SUM(i.allocated_paid_cents) AS paid_gmv
FROM cl_order_item i
JOIN cl_payment p
  ON p.tenant_id=i.tenant_id AND p.dataset_version_id=i.dataset_version_id
 AND p.store_id=i.store_id AND p.order_id=i.order_id
WHERE i.tenant_id=:tenant_id AND i.dataset_version_id=:dataset_version_id
  AND i.store_id IN (:store_ids)
  AND p.paid_at_utc>=:start_utc AND p.paid_at_utc<:end_utc
GROUP BY i.store_id,i.product_id
ORDER BY paid_gmv DESC, i.store_id, i.product_id
LIMIT :limit;

-- Q3: approval CAS (management database only).
-- Run in same transaction as appending a run_event; recompute authorization beforehand.
UPDATE cl_run
SET state='RUNNING', approved_plan_hash=plan_hash, row_version=row_version+1,
    updated_at=:now_utc
WHERE tenant_id=:tenant_id AND run_id=:run_id
  AND state='WAITING_APPROVAL' AND row_version=:expected_run_version
  AND plan_version=:plan_version AND plan_hash=:plan_hash
  AND authz_version=:current_authz_version AND approval_expires_at>:now_utc;
-- affected_rows must be exactly 1, otherwise return a state/version conflict.
