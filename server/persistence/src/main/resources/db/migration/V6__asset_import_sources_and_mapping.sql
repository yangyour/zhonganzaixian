-- UNEXECUTED IMPORT DRAFT. Explicit independent review/apply only; API/worker never migrates.
-- No asset insertion, quota reservation, customer dictionary decision or retention deletion is enabled.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'MOP import draft requires the dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_asset_import_source (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id), created_by uuid NOT NULL,
  purpose varchar(64) NOT NULL DEFAULT 'asset_import_source' CHECK(purpose='asset_import_source'),
  original_name varchar(200) NOT NULL, declared_format varchar(8) NOT NULL CHECK(declared_format IN ('CSV','XLSX')),
  size_bytes integer NOT NULL CHECK(size_bytes BETWEEN 1 AND 20971520),
  sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  object_key varchar(160) NOT NULL UNIQUE CHECK(object_key='asset-import-sources/'||tenant_id::text||'/'||id::text),
  permission_version bigint NOT NULL CHECK(permission_version>=1),
  resource_policy_version varchar(64) NOT NULL DEFAULT 'asset-import-technical-v1' CHECK(resource_policy_version='asset-import-technical-v1'),
  store_kind varchar(16) NOT NULL DEFAULT 'STORE_RAW' CHECK(store_kind='STORE_RAW'),
  parse_kind varchar(16) NOT NULL DEFAULT 'PARSE' CHECK(parse_kind='PARSE'),
  store_operation_id uuid NOT NULL UNIQUE, parse_operation_id uuid,
  storage_status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(storage_status IN ('PENDING','READY','FAILED')),
  parse_status varchar(24) NOT NULL DEFAULT 'NOT_REQUESTED' CHECK(parse_status IN ('NOT_REQUESTED','PENDING','PARSED','SELECTION_REQUIRED','FAILED')),
  latest_artifact_id uuid, latest_mapping_id uuid, version bigint NOT NULL DEFAULT 1 CHECK(version>=1),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,id,created_by), UNIQUE(tenant_id,id,sha256),
  UNIQUE(tenant_id,id,size_bytes,sha256), UNIQUE(tenant_id,id,store_operation_id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((parse_status='NOT_REQUESTED' AND parse_operation_id IS NULL AND latest_artifact_id IS NULL AND latest_mapping_id IS NULL)
     OR (parse_status IN ('PENDING','FAILED') AND parse_operation_id IS NOT NULL AND latest_artifact_id IS NULL AND latest_mapping_id IS NULL)
     OR (parse_status IN ('PARSED','SELECTION_REQUIRED') AND parse_operation_id IS NOT NULL AND latest_artifact_id IS NOT NULL)),
  CHECK(latest_mapping_id IS NULL OR parse_status='PARSED'),
  CHECK(parse_status='NOT_REQUESTED' OR storage_status='READY')
);
CREATE INDEX mop_asset_import_source_owner ON public.mop_asset_import_source(tenant_id,created_by,created_at,id);

CREATE TABLE public.mop_asset_import_operation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, source_id uuid NOT NULL, created_by uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version>=1), kind varchar(16) NOT NULL CHECK(kind IN ('STORE_RAW','PARSE')),
  status varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED')),
  step varchar(32) NOT NULL DEFAULT 'QUEUED', result_artifact_id uuid, error jsonb CHECK(error IS NULL OR jsonb_typeof(error)='object'),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,source_id,kind,id), UNIQUE(tenant_id,source_id,created_by,kind,id), UNIQUE(tenant_id,source_id,created_by,permission_version,kind,id),
  FOREIGN KEY(tenant_id,source_id,created_by) REFERENCES public.mop_asset_import_source(tenant_id,id,created_by),
  CHECK((status='PENDING' AND step='QUEUED' AND error IS NULL AND result_artifact_id IS NULL)
    OR (status='PENDING' AND step='RETRY_WAIT' AND error IS NOT NULL AND result_artifact_id IS NULL)
    OR (status='RUNNING' AND ((kind='STORE_RAW' AND step='STORING') OR (kind='PARSE' AND step='PARSING')) AND error IS NULL AND result_artifact_id IS NULL)
    OR (status='SUCCEEDED' AND kind='STORE_RAW' AND step='STORED' AND error IS NULL AND result_artifact_id IS NULL)
    OR (status='SUCCEEDED' AND kind='PARSE' AND step IN ('PARSED','SELECTION_REQUIRED') AND error IS NULL AND result_artifact_id IS NOT NULL)
    OR (status='FAILED' AND step IN ('AUTHORIZATION_REVOKED','STORAGE_CONFLICT','PARSE_INVALID') AND error IS NOT NULL AND result_artifact_id IS NULL))
);
CREATE INDEX mop_asset_import_operation_owner ON public.mop_asset_import_operation(tenant_id,created_by,created_at,id);
ALTER TABLE public.mop_asset_import_source ADD FOREIGN KEY(tenant_id,id,created_by,permission_version,store_kind,store_operation_id)
  REFERENCES public.mop_asset_import_operation(tenant_id,source_id,created_by,permission_version,kind,id) DEFERRABLE INITIALLY DEFERRED;
ALTER TABLE public.mop_asset_import_source ADD FOREIGN KEY(tenant_id,id,created_by,parse_kind,parse_operation_id)
  REFERENCES public.mop_asset_import_operation(tenant_id,source_id,created_by,kind,id);

CREATE TABLE public.mop_asset_import_job (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, source_id uuid NOT NULL, operation_id uuid NOT NULL UNIQUE,
  kind varchar(16) NOT NULL CHECK(kind IN ('STORE_RAW','PARSE')),
  content bytea, selection_json jsonb,
  content_size integer GENERATED ALWAYS AS (pg_catalog.octet_length(content)) STORED,
  content_sha256 char(64) GENERATED ALWAYS AS (pg_catalog.encode(pg_catalog.sha256(content),'hex')) STORED,
  store_operation_id uuid GENERATED ALWAYS AS (CASE WHEN kind='STORE_RAW' THEN operation_id END) STORED,
  state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','SUCCEEDED','FAILED')),
  lease_owner varchar(64), lease_until timestamptz(3), fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), next_at timestamptz(3) DEFAULT statement_timestamp(),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  FOREIGN KEY(tenant_id,source_id,kind,operation_id) REFERENCES public.mop_asset_import_operation(tenant_id,source_id,kind,id),
  FOREIGN KEY(tenant_id,source_id,content_size,content_sha256)
    REFERENCES public.mop_asset_import_source(tenant_id,id,size_bytes,sha256),
  FOREIGN KEY(tenant_id,source_id,store_operation_id)
    REFERENCES public.mop_asset_import_source(tenant_id,id,store_operation_id),
  CHECK((kind='STORE_RAW' AND content IS NOT NULL AND content_size IS NOT NULL AND content_size BETWEEN 1 AND 20971520 AND content_sha256 IS NOT NULL AND store_operation_id IS NOT NULL AND selection_json IS NULL)
    OR (kind='PARSE' AND content IS NULL AND content_size IS NULL AND content_sha256 IS NULL AND store_operation_id IS NULL AND selection_json IS NOT NULL AND jsonb_typeof(selection_json)='object')),
  CHECK((state='LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND next_at IS NULL)
    OR (state='PENDING' AND lease_owner IS NULL AND lease_until IS NULL AND next_at IS NOT NULL)
    OR (state IN ('SUCCEEDED','FAILED') AND lease_owner IS NULL AND lease_until IS NULL AND next_at IS NULL))
);
CREATE INDEX mop_asset_import_job_pending ON public.mop_asset_import_job(next_at,created_at,id) WHERE state='PENDING';
CREATE INDEX mop_asset_import_job_expired ON public.mop_asset_import_job(lease_until,created_at,id) WHERE state='LEASED';

CREATE TABLE public.mop_asset_import_artifact (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, source_id uuid NOT NULL, operation_id uuid NOT NULL UNIQUE,
  kind varchar(16) NOT NULL DEFAULT 'PARSE' CHECK(kind='PARSE'),
  parse_hash char(64) NOT NULL CHECK(parse_hash ~ '^[0-9a-f]{64}$'), source_sha256 char(64) NOT NULL CHECK(source_sha256 ~ '^[0-9a-f]{64}$'),
  selection_json jsonb NOT NULL CHECK(jsonb_typeof(selection_json)='object'), artifact_json jsonb NOT NULL CHECK(jsonb_typeof(artifact_json)='object'),
  schema_version varchar(64) NOT NULL CHECK(schema_version='contract-m3-6-standard15-v1'),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,source_id,id), UNIQUE(tenant_id,source_id,operation_id,id), UNIQUE(tenant_id,source_id,id,parse_hash),
  FOREIGN KEY(tenant_id,source_id,kind,operation_id) REFERENCES public.mop_asset_import_operation(tenant_id,source_id,kind,id),
  FOREIGN KEY(tenant_id,source_id,source_sha256) REFERENCES public.mop_asset_import_source(tenant_id,id,sha256)
);
ALTER TABLE public.mop_asset_import_operation ADD FOREIGN KEY(tenant_id,source_id,id,result_artifact_id)
  REFERENCES public.mop_asset_import_artifact(tenant_id,source_id,operation_id,id);
ALTER TABLE public.mop_asset_import_source ADD FOREIGN KEY(tenant_id,id,parse_operation_id,latest_artifact_id)
  REFERENCES public.mop_asset_import_artifact(tenant_id,source_id,operation_id,id);

CREATE TABLE public.mop_asset_import_mapping (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, source_id uuid NOT NULL, artifact_id uuid NOT NULL,
  created_by uuid NOT NULL, permission_version bigint NOT NULL CHECK(permission_version>=1),
  version bigint NOT NULL CHECK(version>=1), source_parse_hash char(64) NOT NULL CHECK(source_parse_hash ~ '^[0-9a-f]{64}$'),
  preview_hash char(64) NOT NULL CHECK(preview_hash ~ '^[0-9a-f]{64}$'), schema_version varchar(64) NOT NULL CHECK(schema_version='contract-m3-6-standard15-v1'),
  target_source_columns jsonb NOT NULL CHECK(jsonb_typeof(target_source_columns)='object'),
  ignored_source_columns jsonb NOT NULL CHECK(jsonb_typeof(ignored_source_columns)='array'),
  mapped_raw_rows jsonb NOT NULL CHECK(jsonb_typeof(mapped_raw_rows)='array'),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,source_id,version), UNIQUE(tenant_id,source_id,id), UNIQUE(tenant_id,source_id,artifact_id,id),
  FOREIGN KEY(tenant_id,source_id,created_by) REFERENCES public.mop_asset_import_source(tenant_id,id,created_by),
  FOREIGN KEY(tenant_id,source_id,artifact_id,source_parse_hash) REFERENCES public.mop_asset_import_artifact(tenant_id,source_id,id,parse_hash)
);
ALTER TABLE public.mop_asset_import_source ADD FOREIGN KEY(tenant_id,id,latest_artifact_id,latest_mapping_id)
  REFERENCES public.mop_asset_import_mapping(tenant_id,source_id,artifact_id,id);

REVOKE ALL ON public.mop_asset_import_source,public.mop_asset_import_operation,public.mop_asset_import_job,
  public.mop_asset_import_artifact,public.mop_asset_import_mapping FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_asset_import_source,public.mop_asset_import_operation,public.mop_asset_import_job,
  public.mop_asset_import_artifact,public.mop_asset_import_mapping TO mop_test_runtime;
GRANT UPDATE(storage_status,parse_status,parse_operation_id,latest_artifact_id,latest_mapping_id,version,updated_at)
  ON public.mop_asset_import_source TO mop_test_runtime;
GRANT UPDATE(status,step,result_artifact_id,error,updated_at) ON public.mop_asset_import_operation TO mop_test_runtime;
GRANT UPDATE(state,lease_owner,lease_until,fencing_token,attempts,next_at) ON public.mop_asset_import_job TO mop_test_runtime;
-- Raw bytes/metadata, parse artifacts and mapping history are immutable; runtime has no DELETE or DDL.
