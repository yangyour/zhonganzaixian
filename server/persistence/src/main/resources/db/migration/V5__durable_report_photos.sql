-- Reviewed photo migration, applied only through the explicit mop_test_migrator step.
-- API / file worker startup never migrates. No lifecycle, deletion or RabbitMQ consumer is enabled.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'MOP photo draft requires dedicated mop_test database and migration role';
  END IF;
END $$;

ALTER TABLE public.mop_ticket DROP CONSTRAINT mop_ticket_version_check;
ALTER TABLE public.mop_ticket ADD CHECK(version>=1);
ALTER TABLE public.mop_ticket_history DROP CONSTRAINT mop_ticket_history_version_check;
ALTER TABLE public.mop_ticket_history ADD CHECK(version>=1);
-- Registered text, contacts and authority fields remain immutable to the application.
GRANT UPDATE(version,updated_at) ON public.mop_ticket TO mop_test_runtime;

CREATE TABLE public.mop_file (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id), created_by uuid NOT NULL,
  purpose varchar(64) NOT NULL CHECK(purpose='customer_report_photo'), original_name varchar(200) NOT NULL,
  media_type varchar(64) NOT NULL CHECK(media_type IN ('image/jpeg','image/png')),
  size_bytes integer NOT NULL CHECK(size_bytes BETWEEN 1 AND 5000000), sha256 char(64) NOT NULL CHECK(sha256 ~ '^[0-9a-f]{64}$'),
  object_key varchar(160) NOT NULL UNIQUE CHECK(object_key='photos/'||tenant_id::text||'/'||id::text),
  operation_id uuid NOT NULL UNIQUE, permission_version bigint NOT NULL CHECK(permission_version>=1),
  upload_status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK(upload_status IN ('PENDING','READY','FAILED')),
  ticket_id uuid, version bigint NOT NULL DEFAULT 1 CHECK(version>=1),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  CHECK(ticket_id IS NULL OR upload_status='READY')
);
CREATE INDEX mop_file_ticket ON public.mop_file(tenant_id,ticket_id);
CREATE INDEX mop_file_uploader ON public.mop_file(tenant_id,created_by,created_at,id);

-- Original bytes stay private and durable until a customer-approved retention decision exists (Q10/Q28).
-- The runtime has no permission to rewrite bytes, hashes, object keys or delete any row.
CREATE TABLE public.mop_photo_job (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, file_id uuid NOT NULL UNIQUE,
  content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 5000000),
  state varchar(32) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','SUCCEEDED','FAILED')),
  lease_owner varchar(64), lease_until timestamptz(3), fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token>=0),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  next_at timestamptz(3) DEFAULT statement_timestamp(), created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  FOREIGN KEY(tenant_id,file_id) REFERENCES public.mop_file(tenant_id,id),
  CHECK((state='LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL AND next_at IS NULL)
     OR (state='PENDING' AND lease_owner IS NULL AND lease_until IS NULL AND next_at IS NOT NULL)
     OR (state IN ('SUCCEEDED','FAILED') AND lease_owner IS NULL AND lease_until IS NULL AND next_at IS NULL))
);
CREATE INDEX mop_photo_job_pending ON public.mop_photo_job(next_at,created_at,id) WHERE state='PENDING';
CREATE INDEX mop_photo_job_expired ON public.mop_photo_job(lease_until,created_at,id) WHERE state='LEASED';

CREATE TABLE public.mop_file_grant (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, file_id uuid NOT NULL, actor_id uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version>=1),
  expires_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()+interval '15 minutes',
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  FOREIGN KEY(tenant_id,file_id) REFERENCES public.mop_file(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id), CHECK(expires_at>created_at)
);
CREATE INDEX mop_file_grant_actor ON public.mop_file_grant(tenant_id,actor_id,expires_at);

ALTER TABLE public.mop_operation ALTER COLUMN ticket_id DROP NOT NULL;
ALTER TABLE public.mop_operation ADD COLUMN file_id uuid;
ALTER TABLE public.mop_operation ADD FOREIGN KEY(tenant_id,file_id) REFERENCES public.mop_file(tenant_id,id);
ALTER TABLE public.mop_operation DROP CONSTRAINT mop_operation_kind_check;
ALTER TABLE public.mop_operation ADD CHECK(kind IN ('CUSTOMER_REPORT_REGISTRATION','FILE_UPLOAD'));
ALTER TABLE public.mop_operation DROP CONSTRAINT mop_operation_status_check;
ALTER TABLE public.mop_operation ADD CHECK(status IN ('PENDING','RUNNING','SUCCEEDED','FAILED'));
ALTER TABLE public.mop_operation DROP CONSTRAINT mop_operation_step_check;
ALTER TABLE public.mop_operation ADD CHECK(step IN ('AWAITING_TICKET_CONFIGURATION','QUEUED','UPLOADING','RETRY_WAIT','STORED','AUTHORIZATION_REVOKED','STORAGE_CONFLICT'));
ALTER TABLE public.mop_operation ALTER COLUMN error DROP NOT NULL;
ALTER TABLE public.mop_operation ADD CHECK(
  (kind='CUSTOMER_REPORT_REGISTRATION' AND ticket_id IS NOT NULL AND file_id IS NULL AND status='PENDING' AND step='AWAITING_TICKET_CONFIGURATION' AND error IS NOT NULL)
  OR (kind='FILE_UPLOAD' AND ticket_id IS NULL AND file_id IS NOT NULL AND (
    (status='PENDING' AND step='QUEUED' AND error IS NULL)
    OR (status='PENDING' AND step='RETRY_WAIT' AND error IS NOT NULL)
    OR (status='RUNNING' AND step='UPLOADING' AND error IS NULL)
    OR (status='SUCCEEDED' AND step='STORED' AND error IS NULL)
    OR (status='FAILED' AND step IN ('AUTHORIZATION_REVOKED','STORAGE_CONFLICT') AND error IS NOT NULL)
  ))
);
-- Each file and its operation must point back to the same file, not merely exist in one tenant.
ALTER TABLE public.mop_operation ADD UNIQUE(tenant_id,file_id);
ALTER TABLE public.mop_operation ADD UNIQUE(tenant_id,file_id,id);
ALTER TABLE public.mop_file ADD FOREIGN KEY(tenant_id,id,operation_id) REFERENCES public.mop_operation(tenant_id,file_id,id) DEFERRABLE INITIALLY DEFERRED;

REVOKE ALL ON public.mop_file,public.mop_photo_job,public.mop_file_grant FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_file,public.mop_photo_job,public.mop_file_grant TO mop_test_runtime;
GRANT UPDATE(upload_status,ticket_id,version,updated_at) ON public.mop_file TO mop_test_runtime;
GRANT UPDATE(state,lease_owner,lease_until,fencing_token,attempts,next_at) ON public.mop_photo_job TO mop_test_runtime;
GRANT UPDATE(status,step,error,updated_at) ON public.mop_operation TO mop_test_runtime;
-- mop_ticket_history/audit/outbox remain append-only; the file worker does not claim other outboxes.
