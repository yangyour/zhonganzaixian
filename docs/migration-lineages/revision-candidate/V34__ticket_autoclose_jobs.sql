-- Candidate explicit migration only. Does not enable a worker or send notifications.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_ticket_autoclose_job (
  tenant_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  solution_version bigint NOT NULL,
  solution_hash varchar(64) NOT NULL CHECK(solution_hash ~ '^[0-9a-f]{64}$'),
  due_at timestamptz(3),
  status varchar(32) NOT NULL CHECK(status IN ('PENDING','LEASED','SUCCEEDED','SUPERSEDED','BLOCKED_CONFIG','BLOCKED_ATTEMPTS')),
  lease_owner varchar(128),
  lease_until timestamptz(3),
  fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token BETWEEN 0 AND 9007199254740990),
  attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  completed_at timestamptz(3),
  last_error varchar(64),
  PRIMARY KEY(tenant_id,ticket_id,solution_version),
  FOREIGN KEY(tenant_id,ticket_id,solution_version) REFERENCES public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version),
  FOREIGN KEY(tenant_id,ticket_id,solution_hash) REFERENCES public.mop_ticket_resolution_snapshot(tenant_id,ticket_id,document_sha256),
  CHECK((status='LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'LEASED' AND lease_owner IS NULL AND lease_until IS NULL)),
  CHECK((status IN ('SUCCEEDED','SUPERSEDED','BLOCKED_ATTEMPTS') AND completed_at IS NOT NULL) OR (status NOT IN ('SUCCEEDED','SUPERSEDED','BLOCKED_ATTEMPTS') AND completed_at IS NULL)),
  CHECK((status='BLOCKED_CONFIG' AND due_at IS NULL) OR (status<>'BLOCKED_CONFIG' AND due_at IS NOT NULL)),
  CHECK(due_at IS NULL OR due_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE INDEX mop_ticket_autoclose_claim ON public.mop_ticket_autoclose_job(status,due_at,lease_until);
CREATE FUNCTION public.mop_ticket_autoclose_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE source public.mop_ticket_lifecycle_revision%ROWTYPE; hours_text text; expected_due timestamptz;
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Auto-close job evidence cannot be deleted'; END IF;
  IF TG_OP='INSERT' THEN
    SELECT * INTO source FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id AND version=NEW.solution_version;
    IF NOT FOUND OR source.action<>'SUBMIT_RESOLUTION' OR source.state<>'PENDING_CUSTOMER' OR source.solution_snapshot_hash IS DISTINCT FROM NEW.solution_hash THEN RAISE EXCEPTION 'Auto-close solution binding invalid'; END IF;
    hours_text=source.opening_snapshot->>'confirmation_timeout_hours';
    IF jsonb_typeof(source.opening_snapshot->'confirmation_timeout_hours')='number' AND hours_text ~ '^[1-9][0-9]{0,6}$' THEN
      IF hours_text::bigint<=8760000 THEN
      expected_due=source.resolved_at+(hours_text::integer * interval '1 hour');
      IF expected_due>timestamptz '9999-12-31 23:59:59.999+00' THEN expected_due=NULL; END IF;
      END IF;
    END IF;
    IF NEW.due_at IS DISTINCT FROM expected_due OR NEW.status IS DISTINCT FROM (CASE WHEN expected_due IS NULL THEN 'BLOCKED_CONFIG' ELSE 'PENDING' END) OR NEW.fencing_token<>0 OR NEW.attempts<>0 THEN RAISE EXCEPTION 'Auto-close due time or initial state invalid'; END IF;
    RETURN NEW;
  END IF;
  IF NEW.tenant_id<>OLD.tenant_id OR NEW.ticket_id<>OLD.ticket_id OR NEW.solution_version<>OLD.solution_version OR NEW.solution_hash<>OLD.solution_hash OR NEW.due_at IS DISTINCT FROM OLD.due_at OR NEW.created_at<>OLD.created_at THEN RAISE EXCEPTION 'Auto-close identity and due time are immutable'; END IF;
  IF NEW.status='LEASED' THEN
    IF NOT(OLD.status='PENDING' OR (OLD.status='LEASED' AND OLD.lease_until<=clock_timestamp())) OR NEW.fencing_token<>OLD.fencing_token+1 OR NEW.attempts<>OLD.attempts+1 OR NEW.lease_until<=clock_timestamp() OR NEW.due_at>clock_timestamp() THEN RAISE EXCEPTION 'Invalid auto-close lease claim'; END IF;
  ELSIF NEW.status IN ('SUCCEEDED','SUPERSEDED','BLOCKED_ATTEMPTS') THEN
    IF OLD.status<>'LEASED' OR OLD.lease_until<=clock_timestamp() OR NEW.fencing_token<>OLD.fencing_token OR NEW.attempts<>OLD.attempts THEN RAISE EXCEPTION 'Stale auto-close completion'; END IF;
    IF NEW.status='SUCCEEDED' AND NOT EXISTS(SELECT 1 FROM public.mop_ticket_lifecycle_revision r WHERE r.tenant_id=NEW.tenant_id AND r.ticket_id=NEW.ticket_id AND r.version=NEW.solution_version+1 AND r.action='AUTO_CLOSE' AND r.actor_kind='WORKER' AND r.solution_snapshot_hash=NEW.solution_hash) THEN RAISE EXCEPTION 'Auto-close completion missing lifecycle evidence'; END IF;
  ELSE RAISE EXCEPTION 'Unsupported auto-close job update'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_autoclose_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_autoclose_job FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_autoclose_guard();
REVOKE ALL ON public.mop_ticket_autoclose_job FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_autoclose_job TO mop_test_runtime;
GRANT UPDATE(status,lease_owner,lease_until,fencing_token,attempts,completed_at,last_error) ON public.mop_ticket_autoclose_job TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_autoclose_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_autoclose_guard() TO mop_test_runtime;

ALTER TABLE public.mop_ticket_lifecycle_revision ADD COLUMN worker_lease_owner varchar(128), ADD COLUMN worker_fencing_token bigint;
ALTER TABLE public.mop_ticket_lifecycle_revision ADD CONSTRAINT mop_ticket_lifecycle_worker_fence CHECK(
  (actor_kind='USER' AND worker_lease_owner IS NULL AND worker_fencing_token IS NULL)
  OR (actor_kind='WORKER' AND worker_lease_owner IS NOT NULL AND worker_fencing_token BETWEEN 1 AND 9007199254740990));
CREATE FUNCTION public.mop_ticket_autoclose_revision_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE job public.mop_ticket_autoclose_job%ROWTYPE;
BEGIN
  IF NEW.actor_kind='WORKER' THEN
    SELECT * INTO job FROM public.mop_ticket_autoclose_job WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id AND solution_version=NEW.version-1 FOR UPDATE;
    IF NOT FOUND OR job.status<>'LEASED' OR job.lease_owner IS DISTINCT FROM NEW.worker_lease_owner OR job.fencing_token IS DISTINCT FROM NEW.worker_fencing_token OR job.lease_until<=clock_timestamp() OR job.due_at>clock_timestamp() OR NEW.occurred_at<job.due_at OR NEW.occurred_at>clock_timestamp() OR NEW.solution_snapshot_hash IS DISTINCT FROM job.solution_hash THEN RAISE EXCEPTION 'Worker lifecycle requires current fenced due lease'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_autoclose_revision_guard BEFORE INSERT ON public.mop_ticket_lifecycle_revision FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_autoclose_revision_guard();
CREATE FUNCTION public.mop_ticket_autoclose_enqueue() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE hours_text text; due timestamptz;
BEGIN
  IF NEW.action='SUBMIT_RESOLUTION' THEN
    hours_text=NEW.opening_snapshot->>'confirmation_timeout_hours';
    IF jsonb_typeof(NEW.opening_snapshot->'confirmation_timeout_hours')='number' AND hours_text ~ '^[1-9][0-9]{0,6}$' THEN
      IF hours_text::bigint<=8760000 THEN
        due=NEW.resolved_at+(hours_text::integer * interval '1 hour');
        IF due>timestamptz '9999-12-31 23:59:59.999+00' THEN due=NULL; END IF;
      END IF;
    END IF;
    INSERT INTO public.mop_ticket_autoclose_job(tenant_id,ticket_id,solution_version,solution_hash,due_at,status)
      VALUES(NEW.tenant_id,NEW.ticket_id,NEW.version,NEW.solution_snapshot_hash,due,CASE WHEN due IS NULL THEN 'BLOCKED_CONFIG' ELSE 'PENDING' END);
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_autoclose_enqueue AFTER INSERT ON public.mop_ticket_lifecycle_revision FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_autoclose_enqueue();
REVOKE ALL ON FUNCTION public.mop_ticket_autoclose_revision_guard(),public.mop_ticket_autoclose_enqueue() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_autoclose_revision_guard(),public.mop_ticket_autoclose_enqueue() TO mop_test_runtime;
-- Existing unresolved solutions become durable jobs; no lifecycle transition is executed by migration.
INSERT INTO public.mop_ticket_autoclose_job(tenant_id,ticket_id,solution_version,solution_hash,due_at,status)
SELECT tenant_id,ticket_id,version,solution_snapshot_hash,due,CASE WHEN due IS NULL THEN 'BLOCKED_CONFIG' ELSE 'PENDING' END
FROM (
 SELECT r.*,CASE WHEN opening_snapshot->>'confirmation_timeout_hours' ~ '^[1-9][0-9]{0,6}$'
   AND jsonb_typeof(opening_snapshot->'confirmation_timeout_hours')='number'
   THEN CASE WHEN (opening_snapshot->>'confirmation_timeout_hours')::bigint<=8760000
   THEN CASE WHEN resolved_at+((opening_snapshot->>'confirmation_timeout_hours')::integer*interval '1 hour')<=timestamptz '9999-12-31 23:59:59.999+00'
   THEN resolved_at+((opening_snapshot->>'confirmation_timeout_hours')::integer*interval '1 hour') END END END AS due
 FROM public.mop_ticket_lifecycle_revision r WHERE action='SUBMIT_RESOLUTION'
) sources;
