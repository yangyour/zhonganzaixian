-- Explicit candidate only. No provider, recipient, paid send, migration startup or delivery claim.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_ticket_sla_scan_job (
 tenant_id uuid NOT NULL, ticket_id uuid NOT NULL, lifecycle_version bigint NOT NULL,
 status varchar(24) NOT NULL CHECK(status IN ('PENDING','LEASED','IDLE','BLOCKED_ATTEMPTS')),
 next_at timestamptz(3) NOT NULL, lease_owner varchar(128), lease_until timestamptz(3),
 fencing_token bigint NOT NULL DEFAULT 0 CHECK(fencing_token BETWEEN 0 AND 9007199254740990),
 attempts integer NOT NULL DEFAULT 0 CHECK(attempts>=0), last_outcome varchar(64), observed_at timestamptz(3),
 PRIMARY KEY(tenant_id,ticket_id),
 FOREIGN KEY(tenant_id,ticket_id,lifecycle_version) REFERENCES public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version),
 CHECK((status='LEASED' AND lease_owner IS NOT NULL AND lease_until IS NOT NULL) OR (status<>'LEASED' AND lease_owner IS NULL AND lease_until IS NULL))
);
CREATE INDEX mop_ticket_sla_scan_due ON public.mop_ticket_sla_scan_job(status,next_at,lease_until);
CREATE TABLE public.mop_sla_notification_intent (
 id uuid PRIMARY KEY, tenant_id uuid NOT NULL, ticket_id uuid NOT NULL,
 kind varchar(32) NOT NULL CHECK(kind='SLA_PROGRESS'), event_key varchar(160) NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object' AND octet_length(payload::text)<=8192),
 created_at timestamptz(3) NOT NULL, production_approved boolean NOT NULL DEFAULT false CHECK(NOT production_approved),
 UNIQUE(tenant_id,event_key), UNIQUE(tenant_id,id), FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)
);
CREATE TABLE public.mop_ticket_sla_reminder (
 tenant_id uuid NOT NULL, ticket_id uuid NOT NULL, clock varchar(16) NOT NULL CHECK(clock IN ('RESPONSE','RECOVERY')),
 progress numeric(3,2) NOT NULL CHECK(progress IN (0.5,0.8,1.0)), lifecycle_version bigint NOT NULL,
 observed_at timestamptz(3) NOT NULL, elapsed_ms bigint NOT NULL CHECK(elapsed_ms>=0), budget_ms bigint NOT NULL CHECK(budget_ms>0),
 configuration_reference varchar(80) NOT NULL CHECK(configuration_reference ~ '^sha256:[0-9a-f]{64}$'),
 policy_reference varchar(256), intent_id uuid NOT NULL UNIQUE, worker_lease_owner varchar(128) NOT NULL,
 worker_fencing_token bigint NOT NULL CHECK(worker_fencing_token BETWEEN 1 AND 9007199254740990),
 PRIMARY KEY(tenant_id,ticket_id,clock,progress),
 FOREIGN KEY(tenant_id,ticket_id,lifecycle_version) REFERENCES public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version),
 FOREIGN KEY(tenant_id,intent_id) REFERENCES public.mop_sla_notification_intent(tenant_id,id) DEFERRABLE INITIALLY DEFERRED,
 CHECK(elapsed_ms::numeric>=budget_ms::numeric*progress)
);
CREATE FUNCTION public.mop_ticket_sla_scan_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE latest bigint; latest_at timestamptz; latest_state text;
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'SLA scan evidence cannot be deleted'; END IF;
 SELECT version,occurred_at,state INTO latest,latest_at,latest_state FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id ORDER BY version DESC LIMIT 1;
 IF latest IS NULL OR NEW.lifecycle_version<>latest THEN RAISE EXCEPTION 'SLA scan must bind latest lifecycle'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'PENDING' OR NEW.fencing_token<>0 OR NEW.attempts<>0 OR NEW.next_at IS DISTINCT FROM latest_at THEN RAISE EXCEPTION 'Invalid initial SLA scan'; END IF;
   RETURN NEW;
 END IF;
 IF NEW.tenant_id<>OLD.tenant_id OR NEW.ticket_id<>OLD.ticket_id THEN RAISE EXCEPTION 'SLA scan identity immutable'; END IF;
 IF NEW.lifecycle_version>OLD.lifecycle_version THEN
   IF NEW.status<>'PENDING' OR NEW.fencing_token<>OLD.fencing_token+1 OR NEW.attempts<>0 OR NEW.next_at IS DISTINCT FROM latest_at OR NEW.next_at>clock_timestamp() THEN RAISE EXCEPTION 'Lifecycle event must invalidate old SLA lease'; END IF;
 ELSIF NEW.lifecycle_version<>OLD.lifecycle_version THEN RAISE EXCEPTION 'SLA lifecycle cannot go backwards';
 ELSIF NEW.status='LEASED' THEN
   IF NOT(OLD.status='PENDING' OR (OLD.status='LEASED' AND OLD.lease_until<=clock_timestamp())) OR NEW.next_at>clock_timestamp() OR NEW.lease_until<=clock_timestamp() OR NEW.lease_until>clock_timestamp()+interval '300 seconds' OR NEW.fencing_token<>OLD.fencing_token+1 OR NEW.attempts<>OLD.attempts+1 THEN RAISE EXCEPTION 'Invalid SLA lease claim'; END IF;
 ELSE
   IF OLD.status<>'LEASED' OR OLD.lease_until<=clock_timestamp() OR NEW.fencing_token<>OLD.fencing_token OR NEW.status NOT IN ('PENDING','IDLE','BLOCKED_ATTEMPTS') OR (NEW.status='BLOCKED_ATTEMPTS' AND NEW.attempts<>OLD.attempts) OR (NEW.status<>'BLOCKED_ATTEMPTS' AND NEW.attempts<>0) OR (NEW.status='IDLE' AND latest_state NOT IN ('PENDING_CUSTOMER','CLOSED','RESOLVED')) OR NEW.observed_at IS NULL OR NEW.observed_at>clock_timestamp() OR NEW.next_at<NEW.observed_at THEN RAISE EXCEPTION 'Stale or invalid SLA scan finish'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_sla_scan_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_sla_scan_job FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_sla_scan_guard();
CREATE FUNCTION public.mop_ticket_sla_scan_enqueue() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 INSERT INTO public.mop_ticket_sla_scan_job(tenant_id,ticket_id,lifecycle_version,status,next_at)
 VALUES(NEW.tenant_id,NEW.ticket_id,NEW.version,'PENDING',NEW.occurred_at)
 ON CONFLICT(tenant_id,ticket_id) DO UPDATE SET lifecycle_version=EXCLUDED.lifecycle_version,status='PENDING',next_at=EXCLUDED.next_at,lease_owner=NULL,lease_until=NULL,fencing_token=mop_ticket_sla_scan_job.fencing_token+1,attempts=0,last_outcome=NULL,observed_at=NULL;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_sla_scan_enqueue AFTER INSERT ON public.mop_ticket_lifecycle_revision FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_sla_scan_enqueue();
CREATE FUNCTION public.mop_ticket_sla_reminder_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE job public.mop_ticket_sla_scan_job%ROWTYPE; latest public.mop_ticket_lifecycle_revision%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'SLA reminder evidence immutable'; END IF;
 PERFORM pg_advisory_xact_lock(hashtextextended('mop.ticket.lifecycle|'||NEW.tenant_id||'|'||NEW.ticket_id,0));
 SELECT * INTO job FROM public.mop_ticket_sla_scan_job WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id FOR UPDATE;
 IF NOT FOUND OR job.status<>'LEASED' OR job.lease_until<=clock_timestamp() OR job.fencing_token<>NEW.worker_fencing_token OR job.lease_owner<>NEW.worker_lease_owner OR job.lifecycle_version<>NEW.lifecycle_version THEN RAISE EXCEPTION 'SLA reminder requires current fenced lease'; END IF;
 SELECT * INTO latest FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id ORDER BY version DESC LIMIT 1;
 IF NOT FOUND OR latest.version<>NEW.lifecycle_version OR latest.state NOT IN ('NEW','IN_PROGRESS','WAITING_VENDOR','REOPENED') OR (NEW.clock='RESPONSE' AND latest.state<>'NEW') OR NEW.configuration_reference IS DISTINCT FROM latest.opening_snapshot->>'configuration_release_ref' OR NEW.observed_at<latest.occurred_at OR NEW.observed_at>clock_timestamp() THEN RAISE EXCEPTION 'SLA reminder scope, state or clock evidence invalid'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_sla_reminder_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_sla_reminder FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_sla_reminder_guard();
CREATE FUNCTION public.mop_sla_notification_intent_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE reminder public.mop_ticket_sla_reminder%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Notification intent immutable'; END IF;
 SELECT * INTO reminder FROM public.mop_ticket_sla_reminder WHERE intent_id=NEW.id AND tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id;
 IF NOT FOUND OR NEW.event_key IS DISTINCT FROM 'sla:'||NEW.ticket_id||':'||reminder.clock||':'||(CASE reminder.progress WHEN 0.5 THEN '0.5' WHEN 0.8 THEN '0.8' ELSE '1' END) THEN RAISE EXCEPTION 'Notification intent requires deduplicated reminder'; END IF;
 IF NOT (NEW.payload ?& ARRAY['clock','progress','lifecycle_version','configuration_reference','policy_reference','observed_at','escalation_required']) OR jsonb_typeof(NEW.payload->'progress')<>'number' OR jsonb_typeof(NEW.payload->'lifecycle_version')<>'number' OR jsonb_typeof(NEW.payload->'escalation_required')<>'boolean' OR (SELECT count(*) FROM jsonb_object_keys(NEW.payload))<>7 OR (NEW.payload->>'clock'=reminder.clock) IS NOT TRUE OR (NEW.payload->>'progress')::numeric IS DISTINCT FROM reminder.progress OR (NEW.payload->>'lifecycle_version')::bigint IS DISTINCT FROM reminder.lifecycle_version OR NEW.payload->>'configuration_reference' IS DISTINCT FROM reminder.configuration_reference OR NEW.payload->>'policy_reference' IS DISTINCT FROM reminder.policy_reference OR (NEW.payload->>'observed_at')::timestamptz IS DISTINCT FROM reminder.observed_at OR (NEW.payload->>'escalation_required')::boolean IS DISTINCT FROM (reminder.progress=1.0) OR NEW.created_at IS DISTINCT FROM reminder.observed_at THEN RAISE EXCEPTION 'Notification intent payload must be scoped reminder evidence only'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_sla_notification_intent_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_sla_notification_intent FOR EACH ROW EXECUTE FUNCTION public.mop_sla_notification_intent_guard();
REVOKE ALL ON public.mop_ticket_sla_scan_job,public.mop_ticket_sla_reminder,public.mop_sla_notification_intent FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_sla_scan_job,public.mop_ticket_sla_reminder,public.mop_sla_notification_intent TO mop_test_runtime;
GRANT UPDATE(lifecycle_version,status,next_at,lease_owner,lease_until,fencing_token,attempts,last_outcome,observed_at) ON public.mop_ticket_sla_scan_job TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_sla_scan_guard(),public.mop_ticket_sla_scan_enqueue(),public.mop_ticket_sla_reminder_guard(),public.mop_sla_notification_intent_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_sla_scan_guard(),public.mop_ticket_sla_scan_enqueue(),public.mop_ticket_sla_reminder_guard(),public.mop_sla_notification_intent_guard() TO mop_test_runtime;
INSERT INTO public.mop_ticket_sla_scan_job(tenant_id,ticket_id,lifecycle_version,status,next_at)
SELECT DISTINCT ON(tenant_id,ticket_id) tenant_id,ticket_id,version,'PENDING',occurred_at FROM public.mop_ticket_lifecycle_revision ORDER BY tenant_id,ticket_id,version DESC;
