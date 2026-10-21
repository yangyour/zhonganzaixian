-- Explicit test-schema candidate. Coordination only: no schedule, SSH, credential access or device authority.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_backup_device_lease_event (
 tenant_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
 fencing_token bigint NOT NULL CHECK(fencing_token BETWEEN 1 AND 9007199254740991),
 request_id uuid NOT NULL,
 worker_owner varchar(128) NOT NULL CHECK(length(worker_owner)>0 AND worker_owner !~ '[^A-Za-z0-9._:-]'),
 lease_millis integer NOT NULL CHECK(lease_millis BETWEEN 1 AND 300000),
 action varchar(8) NOT NULL CHECK(action IN ('ACQUIRE','RENEW','RELEASE')),
 acquired_at timestamptz(3) NOT NULL,
 observed_at timestamptz(3) NOT NULL,
 lease_until timestamptz(3) NOT NULL,
 released_at timestamptz(3),
 release_confirmation_reference varchar(71),
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[^A-Za-z0-9._:-]'),
 record_source varchar(40) NOT NULL DEFAULT 'INTERNAL_COORDINATION_JOURNAL' CHECK(record_source='INTERNAL_COORDINATION_JOURNAL'),
 device_execution_authorized boolean NOT NULL DEFAULT false CHECK(NOT device_execution_authorized),
 PRIMARY KEY(tenant_id,asset_id,version),
 FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
 CHECK(acquired_at>=timestamptz '0001-01-01 00:00:00+00' AND lease_until<=timestamptz '9999-12-31 23:59:59.999+00'),
 CHECK(observed_at>=acquired_at AND lease_until>observed_at),
 CHECK((action='RELEASE')=(released_at IS NOT NULL)),
 CHECK((action='RELEASE' AND released_at=observed_at AND release_confirmation_reference IS NOT NULL AND release_confirmation_reference ~ '^sha256:[0-9a-f]{64}$')
    OR (action<>'RELEASE' AND released_at IS NULL AND release_confirmation_reference IS NULL)),
 CHECK((released_at IS NULL AND fencing_token<=(version+1)/2 AND lease_until=observed_at+lease_millis*interval '1 millisecond')
    OR (released_at IS NOT NULL AND fencing_token<=version/2 AND lease_until-lease_millis*interval '1 millisecond' BETWEEN acquired_at AND released_at))
);
CREATE UNIQUE INDEX mop_backup_device_request_once ON public.mop_backup_device_lease_event(tenant_id,asset_id,request_id) WHERE action='ACQUIRE';
CREATE UNIQUE INDEX mop_backup_device_fence_once ON public.mop_backup_device_lease_event(tenant_id,asset_id,fencing_token) WHERE action='ACQUIRE';
CREATE INDEX mop_backup_device_request_history ON public.mop_backup_device_lease_event(tenant_id,asset_id,request_id,version DESC);
CREATE FUNCTION public.mop_backup_device_lease_insert_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE prior_row public.mop_backup_device_lease_event%ROWTYPE; observed timestamptz;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Device coordination journal is immutable' USING ERRCODE='23514'; END IF;
 IF NEW.acquired_at IS NOT NULL OR NEW.observed_at IS NOT NULL OR NEW.lease_until IS NOT NULL OR NEW.released_at IS NOT NULL THEN
   RAISE EXCEPTION 'Device coordination timestamps must come from database clock' USING ERRCODE='23514';
 END IF;
 IF NEW.lease_millis IS NULL OR NEW.lease_millis<1 OR NEW.lease_millis>300000 THEN RAISE EXCEPTION 'Explicit bounded lease required' USING ERRCODE='23514'; END IF;
 -- The existing tenant/asset row serializes all cooperating journal writers; no remote-device lock is asserted.
 PERFORM 1 FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Scoped asset not found' USING ERRCODE='23503'; END IF;
 SELECT * INTO prior_row FROM public.mop_backup_device_lease_event WHERE tenant_id=NEW.tenant_id AND asset_id=NEW.asset_id ORDER BY version DESC LIMIT 1;
 observed:=date_trunc('milliseconds',clock_timestamp());
 IF prior_row.version IS NULL THEN
   IF NEW.action IS DISTINCT FROM 'ACQUIRE' OR NEW.version IS DISTINCT FROM 1 OR NEW.fencing_token IS DISTINCT FROM 1 THEN RAISE EXCEPTION 'First coordination event must acquire version and fence one' USING ERRCODE='23514'; END IF;
   NEW.acquired_at:=observed;NEW.lease_until:=observed+NEW.lease_millis*interval '1 millisecond';
 ELSE
   IF prior_row.version>=9007199254740991 OR NEW.version IS DISTINCT FROM prior_row.version+1 OR observed<prior_row.observed_at THEN RAISE EXCEPTION 'Coordination version or clock conflict' USING ERRCODE='23514'; END IF;
   IF NEW.action='ACQUIRE' THEN
     IF prior_row.released_at IS NULL OR NEW.request_id IS NOT DISTINCT FROM prior_row.request_id OR NEW.fencing_token IS DISTINCT FROM prior_row.fencing_token+1 THEN RAISE EXCEPTION 'Prior request must be explicitly released; expiry is not release' USING ERRCODE='23514'; END IF;
     NEW.acquired_at:=observed;NEW.lease_until:=observed+NEW.lease_millis*interval '1 millisecond';
   ELSIF NEW.action IN ('RENEW','RELEASE') THEN
     IF prior_row.released_at IS NOT NULL OR observed>=prior_row.lease_until
       OR ROW(NEW.request_id,NEW.worker_owner,NEW.fencing_token,NEW.lease_millis) IS DISTINCT FROM ROW(prior_row.request_id,prior_row.worker_owner,prior_row.fencing_token,prior_row.lease_millis) THEN
       RAISE EXCEPTION 'Stale owner, fence or expired coordination; reconciliation required' USING ERRCODE='23514';
     END IF;
     NEW.acquired_at:=prior_row.acquired_at;
     IF NEW.action='RENEW' THEN
       IF observed<=prior_row.observed_at THEN RAISE EXCEPTION 'Renewal must advance the observed clock' USING ERRCODE='23514'; END IF;
       NEW.lease_until:=observed+NEW.lease_millis*interval '1 millisecond';
     ELSE
       NEW.lease_until:=prior_row.lease_until;NEW.released_at:=observed;
     END IF;
   ELSE RAISE EXCEPTION 'Invalid coordination action' USING ERRCODE='23514';
   END IF;
 END IF;
 NEW.observed_at:=observed;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_backup_device_lease_insert_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_backup_device_lease_event FOR EACH ROW EXECUTE FUNCTION public.mop_backup_device_lease_insert_guard();
REVOKE ALL ON public.mop_backup_device_lease_event FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_backup_device_lease_event TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_backup_device_lease_insert_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_backup_device_lease_insert_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_backup_device_lease_event IS 'Internal coordination journal only. Never grants SSH or proves a remote process stopped. Expired unreleased requests block reacquisition pending a separately verified reconciliation path.';
