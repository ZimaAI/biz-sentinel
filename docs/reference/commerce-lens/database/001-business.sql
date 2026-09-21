-- CommerceLens v1 business schema DRAFT. Target MySQL 8.4.

-- Run in a dedicated analysis schema after review. No credentials or source OLTP writes.

-- Published dataset rows must be immutable via service/account permissions.

-- All DATETIME fields explicitly store UTC; business dates use Asia/Shanghai.

SET NAMES utf8mb4;

CREATE TABLE cl_store (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  name VARCHAR(160) NOT NULL,
  platform VARCHAR(32) NOT NULL,
  business_timezone VARCHAR(64) NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_product (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(96) NOT NULL,
  sku_code VARCHAR(96) NOT NULL,
  name VARCHAR(256) NOT NULL,
  category VARCHAR(96) NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,product_id),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id) REFERENCES cl_store(tenant_id,dataset_version_id,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_order (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  order_id VARCHAR(96) NOT NULL,
  created_at_utc DATETIME(6) NOT NULL,
  source_status VARCHAR(32) NOT NULL,
  expected_paid_cents BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,order_id),
  KEY idx_order_created (tenant_id,dataset_version_id,store_id,created_at_utc),
  CHECK (expected_paid_cents >= 0),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id) REFERENCES cl_store(tenant_id,dataset_version_id,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_order_item (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  order_id VARCHAR(96) NOT NULL,
  item_id VARCHAR(96) NOT NULL,
  product_id VARCHAR(96) NOT NULL,
  quantity INT NOT NULL,
  allocated_paid_cents BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,order_id,item_id),
  KEY idx_item_product (tenant_id,dataset_version_id,store_id,product_id),
  CHECK (quantity > 0),
  CHECK (allocated_paid_cents >= 0),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id,order_id) REFERENCES cl_order(tenant_id,dataset_version_id,store_id,order_id),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id,product_id) REFERENCES cl_product(tenant_id,dataset_version_id,store_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_payment (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  payment_id VARCHAR(96) NOT NULL,
  order_id VARCHAR(96) NOT NULL,
  paid_at_utc DATETIME(6) NOT NULL,
  amount_cents BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,payment_id),
  UNIQUE KEY uk_payment_order (tenant_id,dataset_version_id,store_id,order_id),
  KEY idx_payment_time (tenant_id,dataset_version_id,store_id,paid_at_utc),
  CHECK (amount_cents >= 0),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id,order_id) REFERENCES cl_order(tenant_id,dataset_version_id,store_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_refund (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  refund_id VARCHAR(96) NOT NULL,
  order_id VARCHAR(96) NOT NULL,
  succeeded_at_utc DATETIME(6) NOT NULL,
  amount_cents BIGINT NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,refund_id),
  KEY idx_refund_time (tenant_id,dataset_version_id,store_id,succeeded_at_utc),
  KEY idx_refund_order (tenant_id,dataset_version_id,store_id,order_id),
  CHECK (amount_cents >= 0),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id,order_id) REFERENCES cl_order(tenant_id,dataset_version_id,store_id,order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_traffic_daily (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  biz_date DATE NOT NULL,
  visitor_sessions BIGINT NOT NULL,
  quality_status VARCHAR(16) NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,biz_date),
  CHECK (visitor_sessions >= 0),
  CHECK (quality_status IN ('COMPLETE','PARTIAL','INVALID')),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id) REFERENCES cl_store(tenant_id,dataset_version_id,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_inventory_daily (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  product_id VARCHAR(96) NOT NULL,
  biz_date DATE NOT NULL,
  closing_stock BIGINT NOT NULL,
  stockout_minutes INT NOT NULL,
  quality_status VARCHAR(16) NOT NULL,
  PRIMARY KEY (tenant_id,dataset_version_id,store_id,product_id,biz_date),
  CHECK (closing_stock >= 0),
  CHECK (stockout_minutes BETWEEN 0 AND 1440),
  CHECK (quality_status IN ('COMPLETE','PARTIAL','INVALID')),
  FOREIGN KEY (tenant_id,dataset_version_id,store_id,product_id) REFERENCES cl_product(tenant_id,dataset_version_id,store_id,product_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
