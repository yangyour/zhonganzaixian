-- Explicit project-test migration only. API/Worker never apply migrations on startup.
-- Stored claims/receipts are not proof of an actual object store request or current authorization.
CREATE TABLE public.mop_asset_export_job (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),
  accepted_role varchar(16) NOT NULL CHECK(accepted_role IN ('padmin','msp')),
  command_bytes bytea NOT NULL CHECK(octet_length(command_bytes) BETWEEN 1 AND 4096),
  input_sha256 varchar(64) GENERATED ALWAYS AS (encode(pg_catalog.sha256(command_bytes),'hex')) STORED NOT NULL,
  trace_id varchar(128) NOT NULL CHECK(trace_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
  state varchar(24) NOT NULL DEFAULT 'QUEUED' CHECK(state IN ('QUEUED','RUNNING','RECONCILING_STORAGE','SUCCEEDED','FAILED')),
  version bigint NOT NULL DEFAULT 0 CHECK(version BETWEEN 0 AND 9007199254740991),
  fencing_token integer NOT NULL DEFAULT 0 CHECK(fencing_token BETWEEN 0 AND 1000),
  lease_owner varchar(64),
  lease_started_at timestamptz(3),
  lease_until timestamptz(3),
  file_bytes bytea CHECK(file_bytes IS NULL OR octet_length(file_bytes) BETWEEN 1 AND 16777216),
  file_sha256 varchar(64) GENERATED ALWAYS AS (encode(pg_catalog.sha256(file_bytes),'hex')) STORED,
  file_size integer GENERATED ALWAYS AS (octet_length(file_bytes)) STORED,
  selection_sha256 varchar(64),
  row_count integer,
  generated_at timestamptz(3),
  object_id uuid,
  stored_at timestamptz(3),
  error_code varchar(40),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,object_id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((fencing_token=0 AND lease_owner IS NULL AND lease_started_at IS NULL AND lease_until IS NULL)
    OR (fencing_token>0 AND lease_owner IS NOT NULL AND lease_owner ~ '^[A-Za-z0-9._:-]{1,64}$'
      AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL AND lease_started_at>=created_at
      AND lease_started_at<=updated_at AND lease_until>lease_started_at
      AND lease_until<=lease_started_at+interval '10 minutes' AND lease_until<=TIMESTAMPTZ '9999-12-31 23:59:59.999+00')),
  CHECK((file_bytes IS NULL AND selection_sha256 IS NULL AND row_count IS NULL AND generated_at IS NULL)
    OR (file_bytes IS NOT NULL AND selection_sha256 IS NOT NULL AND selection_sha256 ~ '^[0-9a-f]{64}$'
      AND row_count IS NOT NULL AND row_count BETWEEN 0 AND 1000 AND generated_at IS NOT NULL
      AND generated_at BETWEEN created_at AND updated_at)),
  CHECK((object_id IS NULL AND stored_at IS NULL)
    OR (object_id IS NOT NULL AND stored_at IS NOT NULL AND generated_at IS NOT NULL AND stored_at BETWEEN generated_at AND updated_at)),
  CHECK((state='QUEUED' AND fencing_token=0 AND version=0 AND file_bytes IS NULL AND object_id IS NULL AND error_code IS NULL)
    OR (state='RUNNING' AND fencing_token>0 AND object_id IS NULL AND error_code IS NULL)
    OR (state='RECONCILING_STORAGE' AND fencing_token>0 AND file_bytes IS NOT NULL AND object_id IS NULL AND error_code IS NULL)
    OR (state='SUCCEEDED' AND fencing_token>0 AND file_bytes IS NOT NULL AND object_id IS NOT NULL AND error_code IS NULL)
    OR (state='FAILED' AND object_id IS NULL AND error_code IS NOT NULL
      AND error_code IN ('AUTHORIZATION_CHANGED','SOURCE_UNAVAILABLE','INPUT_LIMIT','ENCODING_FAILED','CONFIRMED_STORAGE_REJECTION')
      AND (fencing_token>0 OR error_code='AUTHORIZATION_CHANGED')))
);
CREATE INDEX mop_asset_export_owner ON public.mop_asset_export_job(tenant_id,created_by,created_at,id);
CREATE INDEX mop_asset_export_queue ON public.mop_asset_export_job(created_at,id) WHERE state='QUEUED';
CREATE INDEX mop_asset_export_expired ON public.mop_asset_export_job(lease_until,created_at,id) WHERE state IN ('RUNNING','RECONCILING_STORAGE');
CREATE TABLE public.mop_asset_export_attempt (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  fencing_token integer NOT NULL CHECK(fencing_token BETWEEN 1 AND 1000),
  lease_owner varchar(64) NOT NULL CHECK(lease_owner ~ '^[A-Za-z0-9._:-]{1,64}$'),
  started_at timestamptz(3) NOT NULL,
  lease_until timestamptz(3) NOT NULL,
  PRIMARY KEY(tenant_id,job_id,fencing_token),
  FOREIGN KEY(tenant_id,job_id) REFERENCES public.mop_asset_export_job(tenant_id,id),
  CHECK(started_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'
    AND lease_until>started_at AND lease_until<=started_at+interval '10 minutes'
    AND lease_until<=TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE FUNCTION public.mop_asset_export_transition_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE observed timestamptz(3):=clock_timestamp();
BEGIN
  IF NEW.updated_at>observed THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export observation is in the future'; END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'QUEUED' OR NEW.version<>0 OR NEW.fencing_token<>0 OR NEW.file_bytes IS NOT NULL OR NEW.object_id IS NOT NULL THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export acceptance must begin queued';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED') OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export terminal or version transition rejected';
  END IF;
  -- Generated columns are deliberately not read in a BEFORE trigger; V19 established this boundary.
  IF ROW(NEW.id,NEW.tenant_id,NEW.created_by,NEW.permission_version,NEW.accepted_role,NEW.command_bytes,NEW.trace_id,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.created_by,OLD.permission_version,OLD.accepted_role,OLD.command_bytes,OLD.trace_id,OLD.created_at) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export immutable acceptance changed';
  END IF;
  IF OLD.file_bytes IS NOT NULL AND ROW(NEW.file_bytes,NEW.selection_sha256,NEW.row_count,NEW.generated_at)
    IS DISTINCT FROM ROW(OLD.file_bytes,OLD.selection_sha256,OLD.row_count,OLD.generated_at) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export prepared bytes cannot be changed';
  END IF;
  IF NEW.fencing_token=OLD.fencing_token+1 THEN
    IF OLD.state NOT IN ('QUEUED','RUNNING','RECONCILING_STORAGE')
      OR NEW.state<>(CASE WHEN OLD.file_bytes IS NULL THEN 'RUNNING' ELSE 'RECONCILING_STORAGE' END)
      OR (OLD.fencing_token>0 AND (OLD.lease_until>observed OR NEW.lease_started_at<OLD.lease_until))
      OR NEW.lease_started_at>observed
      OR ROW(NEW.file_bytes,NEW.selection_sha256,NEW.row_count,NEW.generated_at) IS DISTINCT FROM ROW(OLD.file_bytes,OLD.selection_sha256,OLD.row_count,OLD.generated_at) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export reclaim must preserve bytes and reconcile prepared storage';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.fencing_token<>OLD.fencing_token
    OR ROW(NEW.lease_owner,NEW.lease_started_at,NEW.lease_until) IS DISTINCT FROM ROW(OLD.lease_owner,OLD.lease_started_at,OLD.lease_until) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export lease changed without a new fence';
  END IF;
  IF NEW.state='FAILED' AND NEW.error_code='AUTHORIZATION_CHANGED'
    AND (OLD.state='QUEUED' OR OLD.lease_until<=observed) THEN
    IF ROW(NEW.file_bytes,NEW.selection_sha256,NEW.row_count,NEW.generated_at) IS DISTINCT FROM ROW(OLD.file_bytes,OLD.selection_sha256,OLD.row_count,OLD.generated_at) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Authorization failure cannot prepare bytes';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state NOT IN ('RUNNING','RECONCILING_STORAGE') OR observed<OLD.lease_started_at OR observed>=OLD.lease_until THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export completion lease expired';
  END IF;
  IF OLD.file_bytes IS NULL AND NEW.file_bytes IS NOT NULL THEN
    IF OLD.state<>'RUNNING' OR NEW.state<>'RUNNING' THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export preparation must be a separate active transition';
    END IF;
    RETURN NEW;
  END IF;
  IF NEW.state='SUCCEEDED' AND OLD.file_bytes IS NOT NULL THEN RETURN NEW; END IF;
  IF NEW.state='FAILED' AND (OLD.state='RUNNING' OR NEW.error_code IN ('AUTHORIZATION_CHANGED','CONFIRMED_STORAGE_REJECTION')) THEN RETURN NEW; END IF;
  IF NEW.state='RECONCILING_STORAGE' AND OLD.file_bytes IS NOT NULL THEN RETURN NEW; END IF;
  IF OLD.state='RECONCILING_STORAGE' AND NEW.state='RUNNING' THEN RETURN NEW; END IF;
  RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export transition is not supported';
END;
$$;
CREATE TRIGGER mop_asset_export_transition BEFORE INSERT OR UPDATE ON public.mop_asset_export_job
FOR EACH ROW EXECUTE FUNCTION public.mop_asset_export_transition_guard();
CREATE FUNCTION public.mop_asset_export_attempt_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE head public.mop_asset_export_job%ROWTYPE; previous_until timestamptz(3);
BEGIN
  SELECT * INTO head FROM public.mop_asset_export_job WHERE tenant_id=NEW.tenant_id AND id=NEW.job_id;
  IF NOT FOUND OR head.state NOT IN ('RUNNING','RECONCILING_STORAGE')
    OR ROW(NEW.fencing_token,NEW.lease_owner,NEW.started_at,NEW.lease_until)
      IS DISTINCT FROM ROW(head.fencing_token,head.lease_owner,head.lease_started_at,head.lease_until) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export attempt must match current claimed lease';
  END IF;
  IF NEW.fencing_token>1 THEN
    SELECT lease_until INTO previous_until FROM public.mop_asset_export_attempt WHERE tenant_id=NEW.tenant_id AND job_id=NEW.job_id AND fencing_token=NEW.fencing_token-1;
    IF NOT FOUND OR previous_until>NEW.started_at THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Export attempt history is not contiguous'; END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_asset_export_attempt_guard BEFORE INSERT ON public.mop_asset_export_attempt
FOR EACH ROW EXECUTE FUNCTION public.mop_asset_export_attempt_guard();
CREATE FUNCTION public.mop_asset_export_capture_attempt() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
BEGIN
  IF NEW.fencing_token=OLD.fencing_token+1 THEN
    INSERT INTO public.mop_asset_export_attempt(tenant_id,job_id,fencing_token,lease_owner,started_at,lease_until)
      VALUES (NEW.tenant_id,NEW.id,NEW.fencing_token,NEW.lease_owner,NEW.lease_started_at,NEW.lease_until);
  END IF;
  RETURN NULL;
END;
$$;
CREATE TRIGGER mop_asset_export_capture_attempt AFTER UPDATE ON public.mop_asset_export_job
FOR EACH ROW EXECUTE FUNCTION public.mop_asset_export_capture_attempt();
REVOKE ALL ON public.mop_asset_export_job,public.mop_asset_export_attempt FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_asset_export_job,public.mop_asset_export_attempt TO mop_test_runtime;
GRANT UPDATE(state,version,fencing_token,lease_owner,lease_started_at,lease_until,file_bytes,selection_sha256,row_count,generated_at,object_id,stored_at,error_code,updated_at) ON public.mop_asset_export_job TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_asset_export_transition_guard(),public.mop_asset_export_attempt_guard(),public.mop_asset_export_capture_attempt() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_asset_export_transition_guard(),public.mop_asset_export_attempt_guard(),public.mop_asset_export_capture_attempt() TO mop_test_runtime;
