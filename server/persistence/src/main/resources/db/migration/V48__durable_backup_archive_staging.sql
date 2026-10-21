-- Candidate: encrypted source staging and append-only dispatch facts; never a provider execution grant.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_backup_archive_submission (
 tenant_id uuid NOT NULL,
 version_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 run_id uuid NOT NULL,
 attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 3),
 run_version bigint NOT NULL CHECK(run_version BETWEEN 1 AND 9007199254740991),
 submission_id uuid NOT NULL,
 submission_sha256 varchar(64) NOT NULL CHECK(submission_sha256 ~ '^[0-9a-f]{64}$'),
 encoded_envelope bytea NOT NULL,
 envelope_sha256 varchar(64) NOT NULL CHECK(envelope_sha256 ~ '^[0-9a-f]{64}$' AND encode(sha256(encoded_envelope),'hex')=envelope_sha256),
 envelope_bytes integer NOT NULL CHECK(envelope_bytes BETWEEN 247 AND 16787456 AND envelope_bytes=octet_length(encoded_envelope)),
 key_reference varchar(140) NOT NULL CHECK(key_reference ~ '^kms-key-ref:[A-Za-z0-9._-]{1,128}$'),
 created_by uuid NOT NULL,
 recorded_at timestamptz(3) NOT NULL,
 envelope_format varchar(32) NOT NULL DEFAULT 'MOP_BACKUP_AES256_GCM_V1' CHECK(envelope_format='MOP_BACKUP_AES256_GCM_V1'),
 encryption_algorithm varchar(16) NOT NULL DEFAULT 'AES-256-GCM' CHECK(encryption_algorithm='AES-256-GCM'),
 record_source varchar(40) NOT NULL DEFAULT 'INTERNAL_BACKUP_ARCHIVE_STAGING' CHECK(record_source='INTERNAL_BACKUP_ARCHIVE_STAGING'),
 storage_verified boolean NOT NULL DEFAULT false CHECK(NOT storage_verified),
 version_registered boolean NOT NULL DEFAULT false CHECK(NOT version_registered),
 device_execution_authorized boolean NOT NULL DEFAULT false CHECK(NOT device_execution_authorized),
 PRIMARY KEY(tenant_id,version_id),
 UNIQUE(tenant_id,submission_id),
 UNIQUE(tenant_id,run_id,attempt_number),
 FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,run_id,run_version) REFERENCES public.mop_backup_run_event(tenant_id,run_id,version),
 CHECK(recorded_at>=timestamptz '0001-01-01 00:00:00+00' AND recorded_at<=timestamptz '9999-12-31 23:59:59.999+00'),
 -- JCE/independent domain codec canonical prefix: format, algorithm, tenant/asset/run/attempt/version.
 CHECK(substring(encoded_envelope from 1 for 195)=int2send(24::smallint)||convert_to('MOP_BACKUP_AES256_GCM_V1','UTF8')||int2send(11::smallint)||convert_to('AES-256-GCM','UTF8')
   ||int2send(36::smallint)||convert_to(tenant_id::text,'UTF8')||int2send(36::smallint)||convert_to(asset_id::text,'UTF8')||int2send(36::smallint)||convert_to(run_id::text,'UTF8')||int4send(attempt_number)||int2send(36::smallint)||convert_to(version_id::text,'UTF8')),
 CHECK(substring(encoded_envelope from 196 for 2+octet_length(key_reference))=int2send(octet_length(key_reference)::smallint)||convert_to(key_reference,'UTF8'))
);
CREATE INDEX mop_backup_archive_submission_asset ON public.mop_backup_archive_submission(tenant_id,asset_id,recorded_at DESC,version_id DESC);
CREATE TABLE public.mop_backup_archive_event (
 tenant_id uuid NOT NULL,
 version_id uuid NOT NULL,
 event_version bigint NOT NULL CHECK(event_version BETWEEN 0 AND 9007199254740991),
 command_id uuid NOT NULL,
 command_sha256 varchar(64) NOT NULL CHECK(command_sha256 ~ '^[0-9a-f]{64}$'),
 action varchar(24) NOT NULL CHECK(action IN ('STAGE','START_STORE','MARK_UNCONFIRMED','CONFIRM_NO_DISPATCH','VERIFY_OBJECT')),
 phase varchar(24) NOT NULL CHECK(phase IN ('STAGED','STORE_STARTED','VERIFY_REQUIRED','VERIFIED')),
 dispatch_id uuid,
 dispatch_owner varchar(128),
 dispatch_count bigint NOT NULL CHECK(dispatch_count BETWEEN 0 AND 9007199254740991),
 evidence_reference varchar(71),
 actor_id uuid NOT NULL,
 observed_at timestamptz(3) NOT NULL,
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[^A-Za-z0-9._:-]'),
 record_source varchar(40) NOT NULL DEFAULT 'INTERNAL_BACKUP_ARCHIVE_JOURNAL' CHECK(record_source='INTERNAL_BACKUP_ARCHIVE_JOURNAL'),
 version_registered boolean NOT NULL DEFAULT false CHECK(NOT version_registered),
 device_execution_authorized boolean NOT NULL DEFAULT false CHECK(NOT device_execution_authorized),
 PRIMARY KEY(tenant_id,version_id,event_version),
 UNIQUE(tenant_id,version_id,command_id),
 FOREIGN KEY(tenant_id,version_id) REFERENCES public.mop_backup_archive_submission(tenant_id,version_id),
 CHECK(observed_at>=timestamptz '0001-01-01 00:00:00+00' AND observed_at<=timestamptz '9999-12-31 23:59:59.999+00'),
 CHECK((dispatch_count=0 AND dispatch_id IS NULL AND dispatch_owner IS NULL) OR (dispatch_count>0 AND dispatch_id IS NOT NULL AND dispatch_owner IS NOT NULL AND length(dispatch_owner)>0 AND dispatch_owner !~ '[^A-Za-z0-9._:-]')),
 CHECK((action='STAGE' AND phase='STAGED' AND event_version=0 AND dispatch_count=0)
   OR (action='START_STORE' AND phase='STORE_STARTED' AND event_version>0 AND dispatch_count>0)
   OR (action='MARK_UNCONFIRMED' AND phase='VERIFY_REQUIRED' AND event_version>0 AND dispatch_count>0)
   OR (action='CONFIRM_NO_DISPATCH' AND phase='STAGED' AND event_version>0 AND dispatch_count>0)
   OR (action='VERIFY_OBJECT' AND phase='VERIFIED' AND event_version>0)),
 CHECK((action IN ('CONFIRM_NO_DISPATCH','VERIFY_OBJECT') AND evidence_reference IS NOT NULL AND evidence_reference ~ '^sha256:[0-9a-f]{64}$') OR (action NOT IN ('CONFIRM_NO_DISPATCH','VERIFY_OBJECT') AND evidence_reference IS NULL))
);
CREATE UNIQUE INDEX mop_backup_archive_dispatch_once ON public.mop_backup_archive_event(tenant_id,dispatch_id) WHERE action='START_STORE';
CREATE FUNCTION public.mop_backup_archive_submission_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE run_row public.mop_backup_run_event%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Archive source immutable' USING ERRCODE='23514'; END IF;
 IF NEW.recorded_at IS NOT NULL THEN RAISE EXCEPTION 'Caller time forbidden' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Scoped asset missing' USING ERRCODE='23503'; END IF;
 SELECT * INTO run_row FROM public.mop_backup_run_event WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id ORDER BY version DESC LIMIT 1;
 NEW.recorded_at=date_trunc('milliseconds',clock_timestamp());
 IF run_row.version IS NULL OR run_row.asset_id IS DISTINCT FROM NEW.asset_id OR run_row.version IS DISTINCT FROM NEW.run_version OR run_row.attempt_number IS DISTINCT FROM NEW.attempt_number OR run_row.state IS DISTINCT FROM 'RUNNING' OR NEW.recorded_at<run_row.observed_at THEN RAISE EXCEPTION 'Current running attempt required for staging' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE FUNCTION public.mop_backup_archive_event_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE source_row public.mop_backup_archive_submission%ROWTYPE; prior_row public.mop_backup_archive_event%ROWTYPE; run_row public.mop_backup_run_event%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Archive journal immutable' USING ERRCODE='23514'; END IF;
 IF NEW.observed_at IS NOT NULL THEN RAISE EXCEPTION 'Caller time forbidden' USING ERRCODE='23514'; END IF;
 SELECT * INTO source_row FROM public.mop_backup_archive_submission WHERE tenant_id=NEW.tenant_id AND version_id=NEW.version_id;
 IF source_row.version_id IS NULL THEN RAISE EXCEPTION 'Staged source required' USING ERRCODE='23503'; END IF;
 PERFORM 1 FROM public.mop_asset WHERE tenant_id=source_row.tenant_id AND id=source_row.asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Scoped asset missing' USING ERRCODE='23503'; END IF;
 SELECT * INTO prior_row FROM public.mop_backup_archive_event WHERE tenant_id=NEW.tenant_id AND version_id=NEW.version_id ORDER BY event_version DESC LIMIT 1;
 NEW.observed_at=date_trunc('milliseconds',clock_timestamp());
 IF NEW.observed_at<source_row.recorded_at THEN RAISE EXCEPTION 'Archive clock conflict' USING ERRCODE='23514'; END IF;
 IF prior_row.event_version IS NULL THEN
  IF NEW.action IS DISTINCT FROM 'STAGE' OR NEW.event_version IS DISTINCT FROM 0 OR NEW.command_id IS DISTINCT FROM source_row.submission_id OR NEW.command_sha256 IS DISTINCT FROM source_row.submission_sha256 OR NEW.actor_id IS DISTINCT FROM source_row.created_by THEN RAISE EXCEPTION 'Initial stage receipt required' USING ERRCODE='23514'; END IF;
 ELSE
  IF prior_row.event_version>=9007199254740991 OR NEW.event_version IS DISTINCT FROM prior_row.event_version+1 OR NEW.observed_at<prior_row.observed_at OR prior_row.phase='VERIFIED' THEN RAISE EXCEPTION 'Archive version clock or terminal conflict' USING ERRCODE='23514'; END IF;
  IF NEW.action='START_STORE' THEN
   IF prior_row.phase<>'STAGED' OR prior_row.dispatch_count>=9007199254740991 OR NEW.dispatch_count IS DISTINCT FROM prior_row.dispatch_count+1 OR NEW.dispatch_id IS NOT DISTINCT FROM prior_row.dispatch_id THEN RAISE EXCEPTION 'Fresh dispatch required' USING ERRCODE='23514'; END IF;
   SELECT * INTO run_row FROM public.mop_backup_run_event WHERE tenant_id=source_row.tenant_id AND run_id=source_row.run_id ORDER BY version DESC LIMIT 1;
   IF run_row.version IS NULL OR run_row.asset_id IS DISTINCT FROM source_row.asset_id OR run_row.attempt_number IS DISTINCT FROM source_row.attempt_number OR run_row.state IS DISTINCT FROM 'RUNNING' OR NEW.observed_at<run_row.observed_at THEN RAISE EXCEPTION 'Original attempt must still be running before new upload' USING ERRCODE='23514'; END IF;
  ELSE
   IF ROW(NEW.dispatch_id,NEW.dispatch_owner,NEW.dispatch_count) IS DISTINCT FROM ROW(prior_row.dispatch_id,prior_row.dispatch_owner,prior_row.dispatch_count) THEN RAISE EXCEPTION 'Original dispatch binding immutable' USING ERRCODE='23514'; END IF;
   IF NEW.action IN ('MARK_UNCONFIRMED','CONFIRM_NO_DISPATCH') THEN
    IF prior_row.phase NOT IN ('STORE_STARTED','VERIFY_REQUIRED') THEN RAISE EXCEPTION 'No dispatched attempt to reconcile' USING ERRCODE='23514'; END IF;
   ELSIF NEW.action='VERIFY_OBJECT' THEN
    IF prior_row.phase NOT IN ('STAGED','STORE_STARTED','VERIFY_REQUIRED') THEN RAISE EXCEPTION 'Cannot verify archive phase' USING ERRCODE='23514'; END IF;
   ELSE RAISE EXCEPTION 'Invalid archive transition' USING ERRCODE='23514'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_backup_archive_submission_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_backup_archive_submission FOR EACH ROW EXECUTE FUNCTION public.mop_backup_archive_submission_guard();
CREATE TRIGGER mop_backup_archive_event_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_backup_archive_event FOR EACH ROW EXECUTE FUNCTION public.mop_backup_archive_event_guard();
CREATE FUNCTION public.mop_backup_archive_submission_complete_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF NOT EXISTS(SELECT 1 FROM public.mop_backup_archive_event WHERE tenant_id=NEW.tenant_id AND version_id=NEW.version_id AND event_version=0 AND action='STAGE') THEN RAISE EXCEPTION 'Source and initial stage receipt must commit together' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER mop_backup_archive_submission_complete_guard AFTER INSERT ON public.mop_backup_archive_submission DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_backup_archive_submission_complete_guard();
REVOKE ALL ON public.mop_backup_archive_submission,public.mop_backup_archive_event FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_backup_archive_submission,public.mop_backup_archive_event TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_backup_archive_submission_guard(),public.mop_backup_archive_event_guard(),public.mop_backup_archive_submission_complete_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_backup_archive_submission_guard(),public.mop_backup_archive_event_guard(),public.mop_backup_archive_submission_complete_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_backup_archive_submission IS 'Immutable original encrypted envelope before any object-store dispatch. Structural checks are not AEAD proof; application verification and authorization are mandatory.';
COMMENT ON TABLE public.mop_backup_archive_event IS 'Durable dispatch/reconciliation facts. Expiry is not a retry permission. No-dispatch proof must independently establish no PUT in flight or committed. VERIFIED is not version registration or backup-run completion.';
