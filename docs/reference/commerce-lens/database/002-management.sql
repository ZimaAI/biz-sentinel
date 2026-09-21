-- CommerceLens management extension DRAFT; execute in the management schema.

-- Does not replace upstream schema.sql. Integrate with versioned migrations.

-- Identity mapping must be integrated with the actual upstream/auth provider.

SET NAMES utf8mb4;

CREATE TABLE cl_tenant (
  tenant_id VARCHAR(64) NOT NULL,
  name VARCHAR(160) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_member (
  tenant_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(96) NOT NULL,
  role VARCHAR(32) NOT NULL,
  active BOOLEAN NOT NULL,
  authz_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id,subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_store_grant (
  tenant_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(96) NOT NULL,
  store_id VARCHAR(64) NOT NULL,
  granted_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,subject_id,store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_dataset_version (
  tenant_id VARCHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  coverage_start DATE NOT NULL,
  coverage_end_exclusive DATE NOT NULL,
  manifest_json JSON NOT NULL,
  manifest_hash CHAR(64) NOT NULL,
  quality_json JSON NOT NULL,
  published_at DATETIME(6) NULL,
  retired_at DATETIME(6) NULL,
  row_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id,dataset_version_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_ingest_job (
  tenant_id VARCHAR(64) NOT NULL,
  job_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(96) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  state VARCHAR(32) NOT NULL,
  file_sha256 CHAR(64) NOT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  errors_json JSON NULL,
  created_at DATETIME(6) NOT NULL,
  row_version BIGINT NOT NULL DEFAULT 1,
  PRIMARY KEY (tenant_id,job_id),
  UNIQUE KEY uk_ingest_idempotency (tenant_id,subject_id,idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_metric_definition (
  tenant_id VARCHAR(64) NOT NULL,
  metric_id VARCHAR(64) NOT NULL,
  metric_version INT NOT NULL,
  state VARCHAR(24) NOT NULL,
  compiler_key VARCHAR(96) NOT NULL,
  definition_json JSON NOT NULL,
  definition_hash CHAR(64) NOT NULL,
  published_at DATETIME(6) NULL,
  PRIMARY KEY (tenant_id,metric_id,metric_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_run (
  tenant_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  conversation_id VARCHAR(64) NOT NULL,
  graph_thread_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(96) NOT NULL,
  question TEXT NOT NULL,
  state VARCHAR(32) NOT NULL,
  scope_json JSON NOT NULL,
  scope_hash CHAR(64) NOT NULL,
  authz_version BIGINT NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  metric_manifest_hash CHAR(64) NOT NULL,
  metric_manifest_json JSON NOT NULL,
  date_range_json JSON NOT NULL,
  comparison_json JSON NOT NULL,
  plan_json JSON NULL,
  plan_version INT NOT NULL DEFAULT 0,
  plan_hash CHAR(64) NULL,
  approved_plan_hash CHAR(64) NULL,
  approval_expires_at DATETIME(6) NULL,
  budget_json JSON NOT NULL,
  usage_json JSON NOT NULL,
  idempotency_key VARCHAR(96) NOT NULL,
  request_hash CHAR(64) NOT NULL,
  latest_sequence BIGINT NOT NULL DEFAULT 0,
  row_version BIGINT NOT NULL DEFAULT 1,
  lease_owner VARCHAR(96) NULL,
  lease_expires_at DATETIME(6) NULL,
  fencing_token BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,run_id),
  UNIQUE KEY uk_run_idempotency (tenant_id,subject_id,idempotency_key),
  UNIQUE KEY uk_graph_thread (tenant_id,graph_thread_id),
  KEY idx_run_poll (state,lease_expires_at),
  KEY idx_run_subject (tenant_id,subject_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_run_step (
  tenant_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  action_id VARCHAR(64) NOT NULL,
  plan_version INT NOT NULL,
  state VARCHAR(24) NOT NULL,
  tool_name VARCHAR(64) NOT NULL,
  arguments_json JSON NOT NULL,
  query_hash CHAR(64) NULL,
  attempt INT NOT NULL DEFAULT 0,
  fencing_token BIGINT NOT NULL,
  result_ref VARCHAR(64) NULL,
  error_code VARCHAR(64) NULL,
  started_at DATETIME(6) NULL,
  finished_at DATETIME(6) NULL,
  PRIMARY KEY (tenant_id,run_id,plan_version,action_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_query_artifact (
  tenant_id VARCHAR(64) NOT NULL,
  query_artifact_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NULL,
  scope_json JSON NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  metric_manifest_hash CHAR(64) NOT NULL,
  query_hash CHAR(64) NOT NULL,
  sql_template TEXT NOT NULL,
  redacted_parameters_json JSON NOT NULL,
  result_json JSON NOT NULL,
  result_hash CHAR(64) NOT NULL,
  quality_json JSON NOT NULL,
  duration_ms BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,query_artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_evidence (
  tenant_id VARCHAR(64) NOT NULL,
  evidence_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NULL,
  kind VARCHAR(32) NOT NULL,
  query_artifact_id VARCHAR(64) NULL,
  metadata_json JSON NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,evidence_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_run_event (
  tenant_id VARCHAR(64) NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  sequence BIGINT NOT NULL,
  event_type VARCHAR(48) NOT NULL,
  schema_version VARCHAR(16) NOT NULL,
  payload_json JSON NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,run_id,sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_report (
  tenant_id VARCHAR(64) NOT NULL,
  report_id VARCHAR(64) NOT NULL,
  report_version INT NOT NULL,
  run_id VARCHAR(64) NOT NULL,
  owner_subject_id VARCHAR(96) NOT NULL,
  visibility VARCHAR(24) NOT NULL,
  report_json JSON NOT NULL,
  report_hash CHAR(64) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,report_id,report_version),
  UNIQUE KEY uk_report_run_version (tenant_id,run_id,report_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_anomaly_rule (
  tenant_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  rule_version INT NOT NULL,
  definition_json JSON NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_by VARCHAR(96) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,rule_id,rule_version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_anomaly_event (
  tenant_id VARCHAR(64) NOT NULL,
  anomaly_id VARCHAR(64) NOT NULL,
  rule_id VARCHAR(64) NOT NULL,
  rule_version INT NOT NULL,
  scope_hash CHAR(64) NOT NULL,
  dataset_version_id VARCHAR(64) NOT NULL,
  dedup_hash CHAR(64) NOT NULL,
  state VARCHAR(24) NOT NULL,
  event_json JSON NOT NULL,
  supersedes_event_id VARCHAR(64) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,anomaly_id),
  UNIQUE KEY uk_anomaly_dedup (tenant_id,dedup_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE cl_audit_event (
  tenant_id VARCHAR(64) NOT NULL,
  audit_id VARCHAR(64) NOT NULL,
  subject_id VARCHAR(96) NOT NULL,
  action VARCHAR(64) NOT NULL,
  resource_type VARCHAR(48) NOT NULL,
  resource_id VARCHAR(96) NOT NULL,
  decision VARCHAR(24) NOT NULL,
  metadata_json JSON NOT NULL,
  occurred_at DATETIME(6) NOT NULL,
  PRIMARY KEY (tenant_id,audit_id),
  KEY idx_audit_time (tenant_id,occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
