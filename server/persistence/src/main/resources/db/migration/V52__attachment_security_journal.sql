-- Candidate only. Explicit application after coordination; API/Worker never run migrations.
DO $$ BEGIN
 IF current_user <> 'mop_test_migrator' OR NOT
   (current_database()='mop_test' OR current_database() ~ '^mop_attachment_test_[a-f0-9]{12}$') THEN
   RAISE EXCEPTION 'Dedicated attachment migration identity and namespace required';
 END IF;
END $$;
CREATE TABLE public.mop_attachment_security_revision (
 tenant_id uuid NOT NULL,
 file_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
 action varchar(20) NOT NULL CHECK(action IN ('REQUEST_SCAN','CANCEL_SCAN','SCAN_RESULT','RELEASE','QUARANTINE')),
 state varchar(20) NOT NULL CHECK(state IN ('QUARANTINED','QUEUED','CANCELLED','CLEAN','REJECTED','UNKNOWN','RELEASED')),
 actor_id uuid NOT NULL,
 permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740990),
 scan_request_id uuid,
 content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[a-f0-9]{64}$'),
 scanned_at timestamptz(3), valid_until timestamptz(3),
 engine_version varchar(96), signature_version varchar(96), policy_sha256 varchar(64),
 trace_id varchar(128) NOT NULL CHECK(trace_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
 recorded_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',transaction_timestamp()),
 PRIMARY KEY(tenant_id,file_id,version),
 FOREIGN KEY(tenant_id,file_id) REFERENCES public.mop_file(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
 CHECK((state IN ('CLEAN','RELEASED') AND scanned_at IS NOT NULL AND valid_until IS NOT NULL
   AND scanned_at<=recorded_at AND valid_until>recorded_at
   AND engine_version IS NOT NULL AND engine_version ~ '^[A-Za-z0-9._-]{1,96}$'
   AND signature_version IS NOT NULL AND signature_version ~ '^[A-Za-z0-9._-]{1,96}$'
   AND policy_sha256 IS NOT NULL AND policy_sha256 ~ '^[a-f0-9]{64}$')
  OR (state NOT IN ('CLEAN','RELEASED') AND scanned_at IS NULL AND valid_until IS NULL
   AND engine_version IS NULL AND signature_version IS NULL AND policy_sha256 IS NULL)),
 CHECK(state='QUARANTINED' OR scan_request_id IS NOT NULL)
);
CREATE UNIQUE INDEX mop_attachment_scan_identity ON public.mop_attachment_security_revision(tenant_id,file_id,scan_request_id) WHERE action='REQUEST_SCAN';
CREATE INDEX mop_attachment_security_queue ON public.mop_attachment_security_revision(tenant_id,recorded_at,file_id) WHERE state='QUEUED';
CREATE FUNCTION public.mop_attachment_security_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE previous public.mop_attachment_security_revision%ROWTYPE;
file_row public.mop_file%ROWTYPE;
manager boolean;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Attachment decisions are immutable' USING ERRCODE='23514'; END IF;
 PERFORM 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
   WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.actor_id AND m.active AND u.active
   AND m.permission_version=NEW.permission_version FOR SHARE OF m,u;
 IF NOT FOUND OR NOT ('tickets:read'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) THEN
   RAISE EXCEPTION 'Current attachment authority required' USING ERRCODE='23514';
 END IF;
 SELECT * INTO file_row FROM public.mop_file WHERE tenant_id=NEW.tenant_id AND id=NEW.file_id FOR UPDATE;
 IF NOT FOUND OR file_row.sha256<>NEW.content_sha256 THEN
   RAISE EXCEPTION 'Attachment content identity mismatch' USING ERRCODE='23514';
 END IF;
 manager := 'configurations:write'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id));
 IF NOT manager AND (NEW.action IN ('RELEASE','QUARANTINE') OR NEW.actor_id<>file_row.created_by) THEN
   RAISE EXCEPTION 'Attachment action authority required' USING ERRCODE='23514';
 END IF;
 SELECT * INTO previous FROM public.mop_attachment_security_revision
   WHERE tenant_id=NEW.tenant_id AND file_id=NEW.file_id ORDER BY version DESC LIMIT 1;
 IF NEW.version<>COALESCE(previous.version,0)+1 THEN
   RAISE EXCEPTION 'Attachment version conflict' USING ERRCODE='23514';
 END IF;
 NEW.recorded_at := greatest(date_trunc('milliseconds',clock_timestamp()),previous.recorded_at);
 IF NEW.action='REQUEST_SCAN' THEN
   IF NEW.state<>'QUEUED' OR file_row.upload_status<>'READY' OR previous.state='QUEUED'
      OR NEW.scan_request_id IS NULL OR NEW.scan_request_id IS NOT DISTINCT FROM previous.scan_request_id THEN
     RAISE EXCEPTION 'Invalid scan request' USING ERRCODE='23514'; END IF;
 ELSIF NEW.action='CANCEL_SCAN' THEN
   IF NEW.state<>'CANCELLED' OR previous.state IS DISTINCT FROM 'QUEUED'
      OR NEW.scan_request_id IS DISTINCT FROM previous.scan_request_id THEN
     RAISE EXCEPTION 'Invalid scan cancellation' USING ERRCODE='23514'; END IF;
 ELSIF NEW.action='SCAN_RESULT' THEN
   IF NEW.state NOT IN ('CLEAN','REJECTED','UNKNOWN') OR previous.state IS DISTINCT FROM 'QUEUED'
      OR NEW.scan_request_id IS DISTINCT FROM previous.scan_request_id OR file_row.upload_status<>'READY'
      OR (NEW.state='CLEAN' AND NEW.scanned_at<previous.recorded_at) THEN
     RAISE EXCEPTION 'Invalid scan observation' USING ERRCODE='23514'; END IF;
 ELSIF NEW.action='RELEASE' THEN
   IF NEW.state<>'RELEASED' OR previous.state IS DISTINCT FROM 'CLEAN' OR file_row.upload_status<>'READY'
      OR NEW.scan_request_id IS DISTINCT FROM previous.scan_request_id
      OR (NEW.scanned_at,NEW.valid_until,NEW.engine_version,NEW.signature_version,NEW.policy_sha256)
        IS DISTINCT FROM (previous.scanned_at,previous.valid_until,previous.engine_version,previous.signature_version,previous.policy_sha256) THEN
     RAISE EXCEPTION 'Only the exact clean observation can be released' USING ERRCODE='23514'; END IF;
 ELSIF NEW.action='QUARANTINE' AND NEW.state<>'QUARANTINED' THEN
   RAISE EXCEPTION 'Invalid quarantine action' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_attachment_security_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_attachment_security_revision
 FOR EACH ROW EXECUTE FUNCTION public.mop_attachment_security_guard();
REVOKE ALL ON public.mop_attachment_security_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_attachment_security_revision TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_attachment_security_guard() FROM PUBLIC;
