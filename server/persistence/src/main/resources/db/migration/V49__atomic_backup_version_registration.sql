-- Candidate: immutable encrypted-version registration tied to one successful run event.
-- No scheduler, provider permission, retention approval or device-stop assertion.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_backup_version_registration (
 tenant_id uuid NOT NULL,
 version_id uuid NOT NULL,
 asset_id uuid NOT NULL,
 run_id uuid NOT NULL,
 attempt_number integer NOT NULL CHECK(attempt_number BETWEEN 1 AND 3),
 command_id uuid NOT NULL,
 command_sha256 varchar(64) NOT NULL CHECK(command_sha256 ~ '^[0-9a-f]{64}$'),
 expected_run_version bigint NOT NULL CHECK(expected_run_version BETWEEN 1 AND 9007199254740990),
 completed_run_version bigint NOT NULL CHECK(completed_run_version=expected_run_version+1),
 archive_event_version bigint NOT NULL CHECK(archive_event_version BETWEEN 1 AND 9007199254740991),
 envelope_sha256 varchar(64) NOT NULL CHECK(envelope_sha256 ~ '^[0-9a-f]{64}$'),
 envelope_bytes integer NOT NULL CHECK(envelope_bytes BETWEEN 247 AND 16787456),
 key_reference varchar(140) NOT NULL CHECK(key_reference ~ '^kms-key-ref:[A-Za-z0-9._-]{1,128}$'),
 archive_verification_reference varchar(71) NOT NULL CHECK(archive_verification_reference ~ '^sha256:[0-9a-f]{64}$'),
 object_recheck_reference varchar(71) NOT NULL CHECK(object_recheck_reference ~ '^sha256:[0-9a-f]{64}$'),
 execution_reference varchar(71) NOT NULL CHECK(execution_reference ~ '^sha256:[0-9a-f]{64}$'),
 lease_request_id uuid NOT NULL,
 lease_owner varchar(128) NOT NULL CHECK(length(lease_owner)>0 AND lease_owner !~ '[^A-Za-z0-9._:-]'),
 fencing_token bigint NOT NULL CHECK(fencing_token BETWEEN 1 AND 9007199254740991),
 object_observed_at timestamptz(3) NOT NULL,
 registered_by uuid NOT NULL,
 registered_at timestamptz(3) NOT NULL,
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[^A-Za-z0-9._:-]'),
 record_source varchar(48) NOT NULL DEFAULT 'INTERNAL_BACKUP_VERSION_REGISTRATION' CHECK(record_source='INTERNAL_BACKUP_VERSION_REGISTRATION'),
 device_execution_authorized boolean NOT NULL DEFAULT false CHECK(NOT device_execution_authorized),
 retention_verified boolean NOT NULL DEFAULT false CHECK(NOT retention_verified),
 recovery_verified boolean NOT NULL DEFAULT false CHECK(NOT recovery_verified),
 PRIMARY KEY(tenant_id,version_id),
 UNIQUE(tenant_id,command_id),
 UNIQUE(tenant_id,run_id,attempt_number),
 FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,version_id) REFERENCES public.mop_backup_archive_submission(tenant_id,version_id),
 FOREIGN KEY(tenant_id,version_id,archive_event_version) REFERENCES public.mop_backup_archive_event(tenant_id,version_id,event_version),
 FOREIGN KEY(tenant_id,run_id,expected_run_version) REFERENCES public.mop_backup_run_event(tenant_id,run_id,version),
 FOREIGN KEY(tenant_id,run_id,completed_run_version) REFERENCES public.mop_backup_run_event(tenant_id,run_id,version) DEFERRABLE INITIALLY DEFERRED,
 CHECK(object_observed_at>=timestamptz '0001-01-01 00:00:00+00' AND object_observed_at<=timestamptz '9999-12-31 23:59:59.999+00'),
 CHECK(registered_at>=object_observed_at AND registered_at<=timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE INDEX mop_backup_version_asset_history ON public.mop_backup_version_registration(tenant_id,asset_id,registered_at DESC,version_id DESC);
CREATE FUNCTION public.mop_backup_version_registration_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE source_row public.mop_backup_archive_submission%ROWTYPE; archive_row public.mop_backup_archive_event%ROWTYPE; run_row public.mop_backup_run_event%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Registered version immutable' USING ERRCODE='23514'; END IF;
 IF NEW.registered_at IS NOT NULL THEN RAISE EXCEPTION 'Caller registration time forbidden' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Scoped asset missing' USING ERRCODE='23503'; END IF;
 SELECT * INTO source_row FROM public.mop_backup_archive_submission WHERE tenant_id=NEW.tenant_id AND version_id=NEW.version_id;
 IF source_row.version_id IS NULL OR ROW(source_row.asset_id,source_row.run_id,source_row.attempt_number,source_row.envelope_sha256,source_row.envelope_bytes,source_row.key_reference) IS DISTINCT FROM ROW(NEW.asset_id,NEW.run_id,NEW.attempt_number,NEW.envelope_sha256,NEW.envelope_bytes,NEW.key_reference) THEN RAISE EXCEPTION 'Original encrypted source binding required' USING ERRCODE='23514'; END IF;
 SELECT * INTO archive_row FROM public.mop_backup_archive_event WHERE tenant_id=NEW.tenant_id AND version_id=NEW.version_id ORDER BY event_version DESC LIMIT 1;
 IF archive_row.event_version IS NULL OR archive_row.event_version IS DISTINCT FROM NEW.archive_event_version OR archive_row.phase IS DISTINCT FROM 'VERIFIED' OR archive_row.action IS DISTINCT FROM 'VERIFY_OBJECT' OR archive_row.evidence_reference IS DISTINCT FROM NEW.archive_verification_reference OR archive_row.observed_at IS DISTINCT FROM NEW.object_observed_at OR NEW.object_observed_at<source_row.recorded_at THEN RAISE EXCEPTION 'Original verified archive event required' USING ERRCODE='23514'; END IF;
 SELECT * INTO run_row FROM public.mop_backup_run_event WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id ORDER BY version DESC LIMIT 1;
 NEW.registered_at=date_trunc('milliseconds',transaction_timestamp());
 IF run_row.version IS NULL OR run_row.version IS DISTINCT FROM NEW.expected_run_version OR run_row.asset_id IS DISTINCT FROM NEW.asset_id OR run_row.attempt_number IS DISTINCT FROM NEW.attempt_number OR run_row.state IS DISTINCT FROM 'RUNNING' OR run_row.action NOT IN ('START','MARK_UNKNOWN') OR ROW(run_row.lease_request_id,run_row.lease_owner,run_row.fencing_token) IS DISTINCT FROM ROW(NEW.lease_request_id,NEW.lease_owner,NEW.fencing_token) OR NEW.registered_at<run_row.observed_at OR NEW.registered_at<NEW.object_observed_at THEN RAISE EXCEPTION 'Current original running attempt required' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_backup_version_registration_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_backup_version_registration FOR EACH ROW EXECUTE FUNCTION public.mop_backup_version_registration_guard();
CREATE FUNCTION public.mop_backup_version_complete_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE completed public.mop_backup_run_event%ROWTYPE;
BEGIN
 SELECT * INTO completed FROM public.mop_backup_run_event WHERE tenant_id=NEW.tenant_id AND run_id=NEW.run_id AND version=NEW.completed_run_version;
 IF completed.version IS NULL OR completed.action IS DISTINCT FROM 'SUCCEED' OR completed.state IS DISTINCT FROM 'SUCCEEDED' OR ROW(completed.asset_id,completed.attempt_number,completed.command_id,completed.verification_reference,completed.observed_at,completed.lease_request_id,completed.lease_owner,completed.fencing_token) IS DISTINCT FROM ROW(NEW.asset_id,NEW.attempt_number,NEW.command_id,NEW.execution_reference,NEW.registered_at,NEW.lease_request_id,NEW.lease_owner,NEW.fencing_token) THEN RAISE EXCEPTION 'Version registration and matching successful run must commit together' USING ERRCODE='23514'; END IF;
 RETURN NEW;
END $$;
CREATE CONSTRAINT TRIGGER mop_backup_version_complete_guard AFTER INSERT ON public.mop_backup_version_registration DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_backup_version_complete_guard();
REVOKE ALL ON public.mop_backup_version_registration FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_backup_version_registration TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_backup_version_registration_guard(),public.mop_backup_version_complete_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_backup_version_registration_guard(),public.mop_backup_version_complete_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_backup_version_registration IS 'Immutable registration plus causally matched run completion. References are not independent proof. Application must authenticate the actual original object and verify completion prerequisites; no device, retention or recovery authority.';
COMMENT ON COLUMN public.mop_backup_version_registration.object_observed_at IS 'When the V48 journal recorded that the original object was verified present. Not an invented provider creation timestamp or a guarantee of current durability.';
