-- Candidate: internal run facts only. No SSH, scheduler, object access or execution authority.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_backup_run_event (
 tenant_id uuid NOT NULL,
 run_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version BETWEEN 0 AND 9007199254740991),
 command_id uuid NOT NULL,
 command_sha256 varchar(64) NOT NULL CHECK(command_sha256 ~ '^[0-9a-f]{64}$'),
 action varchar(16) NOT NULL CHECK(action IN ('QUEUE','START','MARK_UNKNOWN','SUCCEED','FAIL')),
 state varchar(16) NOT NULL CHECK(state IN ('QUEUED','RUNNING','RETRY_WAIT','SUCCEEDED','FAILED')),
 queued_at timestamptz(3) NOT NULL,
 observed_at timestamptz(3) NOT NULL,
 attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 0 AND 3),
 lease_request_id uuid,
 lease_event_version bigint,
 lease_owner varchar(128),
 fencing_token bigint,
 verification_reference varchar(71),
 snapshot bytea NOT NULL,
 snapshot_sha256 varchar(64) NOT NULL CHECK(snapshot_sha256 ~ '^[0-9a-f]{64}$' AND encode(sha256(snapshot),'hex')=snapshot_sha256),
 snapshot_format varchar(32) NOT NULL DEFAULT 'MOP_BACKUP_RUN_SNAPSHOT_V1' CHECK(snapshot_format='MOP_BACKUP_RUN_SNAPSHOT_V1'),
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[^A-Za-z0-9._:-]'),
 record_source varchar(40) NOT NULL DEFAULT 'INTERNAL_BACKUP_RUN_JOURNAL' CHECK(record_source='INTERNAL_BACKUP_RUN_JOURNAL'),
 device_execution_authorized boolean NOT NULL DEFAULT false CHECK(NOT device_execution_authorized),
 PRIMARY KEY(tenant_id,run_id,version),
 UNIQUE(tenant_id,run_id,command_id),
 FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,asset_id,lease_event_version) REFERENCES public.mop_backup_device_lease_event(tenant_id,asset_id,version),
 CHECK(queued_at>=timestamptz '0001-01-01 00:00:00+00' AND queued_at<=observed_at AND observed_at<=timestamptz '9999-12-31 23:59:59.999+00'),
 CHECK((action='QUEUE' AND state='QUEUED' AND version=0 AND attempt_number=0 AND queued_at=observed_at)
    OR (action IN ('START','MARK_UNKNOWN') AND state='RUNNING' AND version>=1 AND attempt_number BETWEEN 1 AND 3)
    OR (action='SUCCEED' AND state='SUCCEEDED' AND version>=2 AND attempt_number BETWEEN 1 AND 3)
    OR (action='FAIL' AND version>=2 AND ((state='RETRY_WAIT' AND attempt_number BETWEEN 1 AND 2) OR (state='FAILED' AND attempt_number=3)))),
 CHECK((action='QUEUE' AND lease_request_id IS NULL AND lease_event_version IS NULL AND lease_owner IS NULL AND fencing_token IS NULL)
    OR (action<>'QUEUE' AND lease_request_id IS NOT NULL AND lease_event_version IS NOT NULL AND lease_event_version BETWEEN 1 AND 9007199254740991 AND lease_owner IS NOT NULL AND length(lease_owner)>0 AND lease_owner !~ '[^A-Za-z0-9._:-]' AND fencing_token IS NOT NULL AND fencing_token BETWEEN 1 AND 9007199254740991)),
 CHECK((action IN ('SUCCEED','FAIL') AND verification_reference IS NOT NULL AND verification_reference ~ '^sha256:[0-9a-f]{64}$') OR (action NOT IN ('SUCCEED','FAIL') AND verification_reference IS NULL)),
 CHECK(octet_length(snapshot) BETWEEN 162 AND 4096 AND ((attempt_number=0)=(octet_length(snapshot)=162))),
 -- Independent immutable header binding; the Java codec verifies the complete attempt payload.
 CHECK(substring(snapshot from 1 for 28)=decode('001a4d4f505f4241434b55505f52554e5f534e415053484f545f5631','hex')
   AND substring(snapshot from 29 for 38)=decode('0024','hex')||convert_to(run_id::text,'UTF8')
   AND substring(snapshot from 67 for 38)=decode('0024','hex')||convert_to(tenant_id::text,'UTF8')
   AND substring(snapshot from 105 for 38)=decode('0024','hex')||convert_to(asset_id::text,'UTF8')
   AND substring(snapshot from 143 for 8)=int8send((extract(epoch from queued_at)*1000)::bigint)
   AND substring(snapshot from 151 for 8)=int8send(version)
   AND substring(snapshot from 159 for 4)=int4send(attempt_number))
);
CREATE UNIQUE INDEX mop_backup_attempt_lease_once ON public.mop_backup_run_event(tenant_id,asset_id,lease_request_id) WHERE action='START';
CREATE INDEX mop_backup_run_asset_history ON public.mop_backup_run_event(tenant_id,asset_id,run_id,version DESC);
CREATE FUNCTION public.mop_backup_run_event_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE prior_row public.mop_backup_run_event%ROWTYPE; lease_row public.mop_backup_device_lease_event%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Backup run journal immutable' USING ERRCODE='23514'; END IF;
 -- One operation per transaction; all snapshot timestamps originate from this exact database observation.
 IF NEW.observed_at IS DISTINCT FROM date_trunc('milliseconds',transaction_timestamp()) THEN RAISE EXCEPTION 'Trusted transaction observation required' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Scoped asset not found' USING ERRCODE='23503'; END IF;
 SELECT * INTO prior_row FROM public.mop_backup_run_event WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id ORDER BY version DESC LIMIT 1;
 IF prior_row.version IS NULL THEN
  IF NEW.action IS DISTINCT FROM 'QUEUE' OR NEW.version IS DISTINCT FROM 0 OR NEW.attempt_number IS DISTINCT FROM 0 OR NEW.queued_at IS DISTINCT FROM NEW.observed_at THEN RAISE EXCEPTION 'Run must begin with queue version zero' USING ERRCODE='23514'; END IF;
 ELSE
  IF prior_row.asset_id IS DISTINCT FROM NEW.asset_id OR prior_row.queued_at IS DISTINCT FROM NEW.queued_at OR prior_row.version>=9007199254740991 OR NEW.version IS DISTINCT FROM prior_row.version+1 OR NEW.observed_at<prior_row.observed_at THEN RAISE EXCEPTION 'Run identity version or clock conflict' USING ERRCODE='23514'; END IF;
  IF NEW.action='START' THEN
   IF prior_row.state NOT IN ('QUEUED','RETRY_WAIT') OR NEW.attempt_number IS DISTINCT FROM prior_row.attempt_number+1 OR prior_row.attempt_number>=3 OR (prior_row.state='RETRY_WAIT' AND NEW.observed_at<prior_row.observed_at+interval '10 minutes') THEN RAISE EXCEPTION 'Attempt cannot start or retry early' USING ERRCODE='23514'; END IF;
   SELECT * INTO lease_row FROM public.mop_backup_device_lease_event WHERE tenant_id=NEW.tenant_id AND asset_id=NEW.asset_id ORDER BY version DESC LIMIT 1;
   IF lease_row.version IS NULL OR lease_row.released_at IS NOT NULL OR clock_timestamp()>=lease_row.lease_until OR NEW.observed_at<lease_row.observed_at
     OR ROW(NEW.lease_request_id,NEW.lease_event_version,NEW.lease_owner,NEW.fencing_token) IS DISTINCT FROM ROW(lease_row.request_id,lease_row.version,lease_row.worker_owner,lease_row.fencing_token) THEN RAISE EXCEPTION 'Current owned unexpired device lease required' USING ERRCODE='23514'; END IF;
  ELSIF NEW.action IN ('MARK_UNKNOWN','SUCCEED','FAIL') THEN
   IF prior_row.state<>'RUNNING' OR NEW.attempt_number IS DISTINCT FROM prior_row.attempt_number OR ROW(NEW.lease_request_id,NEW.lease_event_version,NEW.lease_owner,NEW.fencing_token) IS DISTINCT FROM ROW(prior_row.lease_request_id,prior_row.lease_event_version,prior_row.lease_owner,prior_row.fencing_token) THEN RAISE EXCEPTION 'Current attempt and original lease binding required' USING ERRCODE='23514'; END IF;
  ELSE RAISE EXCEPTION 'Invalid run transition' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_backup_run_event_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_backup_run_event FOR EACH ROW EXECUTE FUNCTION public.mop_backup_run_event_guard();
REVOKE ALL ON public.mop_backup_run_event FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_backup_run_event TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_backup_run_event_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_backup_run_event_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_backup_run_event IS 'Internal run/attempt journal only. Not proof of SSH execution or encrypted object existence. A new attempt requires its own current device lease; unknown outcomes never authorize blind retry.';
