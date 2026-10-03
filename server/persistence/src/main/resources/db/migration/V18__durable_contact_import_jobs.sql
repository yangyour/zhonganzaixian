-- Explicit test-database migration only. No API/worker automatic migration or contact insertion.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Contact imports require the dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_contact_import_job (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),
  row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 100),
  payload_bytes bytea NOT NULL CHECK(octet_length(payload_bytes) BETWEEN 2 AND 524288),
  input_sha256 char(64) GENERATED ALWAYS AS (pg_catalog.encode(pg_catalog.sha256(payload_bytes),'hex')) STORED NOT NULL,
  source_format varchar(40) NOT NULL DEFAULT 'contact-create-only-v1' CHECK(source_format='contact-create-only-v1'),
  trace_id varchar(128) NOT NULL CHECK(trace_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
  state varchar(16) NOT NULL DEFAULT 'PENDING' CHECK(state IN ('PENDING','LEASED','SUCCEEDED','FAILED')),
  attempts bigint NOT NULL DEFAULT 0 CHECK(attempts BETWEEN 0 AND 9007199254740991),
  fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token=attempts),
  lease_owner varchar(64),
  lease_started_at timestamptz(3),
  lease_until timestamptz(3),
  next_at timestamptz(3) DEFAULT statement_timestamp(),
  completed_rows integer,
  error_code varchar(40),
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,id,row_count),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(next_at IS NULL OR next_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(lease_started_at IS NULL OR lease_started_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(lease_until IS NULL OR lease_until BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(
    (state='PENDING' AND lease_owner IS NULL AND lease_started_at IS NULL AND lease_until IS NULL AND next_at IS NOT NULL AND completed_rows IS NULL
      AND ((attempts=0 AND error_code IS NULL) OR (attempts>0 AND error_code IS NOT NULL AND error_code='UPSTREAM_UNAVAILABLE')))
    OR (state='LEASED' AND attempts>0 AND lease_owner IS NOT NULL AND lease_owner ~ '^[A-Za-z0-9._:-]{1,64}$'
      AND lease_started_at IS NOT NULL AND lease_until IS NOT NULL AND lease_until>lease_started_at
      AND lease_until<=lease_started_at+interval '1 hour' AND next_at IS NULL AND completed_rows IS NULL AND error_code IS NULL)
    OR (state='SUCCEEDED' AND attempts>0 AND lease_owner IS NULL AND lease_started_at IS NULL AND lease_until IS NULL AND next_at IS NULL
      AND completed_rows IS NOT NULL AND completed_rows=row_count AND error_code IS NULL)
    OR (state='FAILED' AND attempts>0 AND lease_owner IS NULL AND lease_started_at IS NULL AND lease_until IS NULL AND next_at IS NULL
      AND completed_rows IS NULL AND error_code IS NOT NULL AND error_code IN ('AUTHORIZATION_REVOKED','PAYLOAD_INVALID','LAST_ENABLED_DECISION_MAKER'))
  )
);
CREATE INDEX mop_contact_import_owner ON public.mop_contact_import_job(tenant_id,created_by,created_at,id);
CREATE INDEX mop_contact_import_pending ON public.mop_contact_import_job(next_at,created_at,id) WHERE state='PENDING';
CREATE INDEX mop_contact_import_expired ON public.mop_contact_import_job(lease_until,created_at,id) WHERE state='LEASED';
CREATE TABLE public.mop_contact_import_result (
  tenant_id uuid NOT NULL,
  job_id uuid NOT NULL,
  row_number integer NOT NULL,
  row_count integer NOT NULL,
  contact_id uuid NOT NULL,
  contact_version bigint NOT NULL DEFAULT 1 CHECK(contact_version=1),
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  PRIMARY KEY(tenant_id,job_id,row_number),
  UNIQUE(tenant_id,contact_id),
  FOREIGN KEY(tenant_id,job_id,row_count) REFERENCES public.mop_contact_import_job(tenant_id,id,row_count),
  FOREIGN KEY(tenant_id,contact_id,contact_version) REFERENCES public.mop_customer_contact_revision(tenant_id,contact_id,version),
  CHECK(row_number BETWEEN 1 AND row_count)
);
CREATE FUNCTION public.mop_contact_import_transition_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE observed timestamptz(3):=clock_timestamp();
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'PENDING' OR NEW.attempts<>0 OR NEW.fencing_token<>0 OR NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import acceptance must begin queued';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED') OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import terminal/version transition rejected';
  END IF;
  IF (to_jsonb(NEW)-ARRAY['state','attempts','fencing_token','lease_owner','lease_started_at','lease_until','next_at','completed_rows','error_code','version','updated_at'])
    IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['state','attempts','fencing_token','lease_owner','lease_started_at','lease_until','next_at','completed_rows','error_code','version','updated_at']) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import immutable acceptance changed';
  END IF;
  IF NEW.state='LEASED' THEN
    IF NEW.attempts<>OLD.attempts+1 OR NEW.fencing_token<>OLD.fencing_token+1
      OR NEW.lease_started_at>observed
      OR (OLD.state='PENDING' AND (OLD.next_at>observed OR NEW.lease_started_at<OLD.next_at))
      OR (OLD.state='LEASED' AND (OLD.lease_until>observed OR NEW.lease_started_at<OLD.lease_until)) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import claim is early or unfenced';
    END IF;
  ELSE
    IF OLD.state<>'LEASED' OR observed<OLD.lease_started_at OR observed>=OLD.lease_until
      OR NEW.attempts<>OLD.attempts OR NEW.fencing_token<>OLD.fencing_token
      OR (NEW.state='PENDING' AND NEW.next_at<=observed) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import completion or retry lease invalid';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_contact_import_transition BEFORE INSERT OR UPDATE ON public.mop_contact_import_job
FOR EACH ROW EXECUTE FUNCTION public.mop_contact_import_transition_guard();
CREATE FUNCTION public.mop_contact_import_completion_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE job_ref uuid; current_state varchar(16); expected integer; actual bigint;
BEGIN
  IF TG_TABLE_NAME='mop_contact_import_job' THEN job_ref:=NEW.id; ELSE job_ref:=NEW.job_id; END IF;
  SELECT state,row_count INTO current_state,expected FROM public.mop_contact_import_job WHERE tenant_id=NEW.tenant_id AND id=job_ref;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import job missing'; END IF;
  SELECT count(*) INTO actual FROM public.mop_contact_import_result WHERE tenant_id=NEW.tenant_id AND job_id=job_ref;
  IF (current_state='SUCCEEDED' AND actual<>expected) OR (current_state<>'SUCCEEDED' AND actual<>0) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import completion is not an atomic whole batch';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_contact_import_job_complete AFTER INSERT OR UPDATE ON public.mop_contact_import_job
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_contact_import_completion_guard();
CREATE CONSTRAINT TRIGGER mop_contact_import_result_complete AFTER INSERT ON public.mop_contact_import_result
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_contact_import_completion_guard();
REVOKE ALL ON public.mop_contact_import_job,public.mop_contact_import_result FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_contact_import_job,public.mop_contact_import_result TO mop_test_runtime;
GRANT UPDATE(state,attempts,fencing_token,lease_owner,lease_started_at,lease_until,next_at,completed_rows,error_code,version,updated_at) ON public.mop_contact_import_job TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_contact_import_transition_guard(),public.mop_contact_import_completion_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_contact_import_transition_guard(),public.mop_contact_import_completion_guard() TO mop_test_runtime;
