-- Unreviewed notice proposals only. No approval, valid matching, alert suppression, events or device commands.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Change notice drafts require the dedicated test migration identity';
  END IF;
END $$;
CREATE TABLE public.mop_change_notice_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  applicant_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,applicant_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_change_notice_revision (
  tenant_id uuid NOT NULL,
  notice_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  previous_version bigint,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE','ARCHIVE','RESTORE')),
  archived boolean NOT NULL DEFAULT false,
  ticket_id uuid,
  change_summary varchar(2000) NOT NULL CHECK(char_length(change_summary) BETWEEN 1 AND 2000
    AND change_summary !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND change_summary !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  planned_start_at timestamptz(3) NOT NULL CHECK(planned_start_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'
    AND extract(year FROM planned_start_at AT TIME ZONE 'Asia/Shanghai') BETWEEN 1 AND 9999),
  planned_end_at timestamptz(3) CHECK(planned_end_at IS NULL OR (planned_end_at>planned_start_at
    AND planned_end_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'
    AND extract(year FROM planned_end_at AT TIME ZONE 'Asia/Shanghai') BETWEEN 1 AND 9999)),
  asset_count integer NOT NULL CHECK(asset_count BETWEEN 1 AND 1000),
  content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' CHECK(timezone='Asia/Shanghai'),
  lifecycle_status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(lifecycle_status='DRAFT'),
  approval_workflow_status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING' CHECK(approval_workflow_status='CONFIG_PENDING'),
  approval_status text CHECK(approval_status IS NULL),
  matching_policy_status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING' CHECK(matching_policy_status='CONFIG_PENDING'),
  automatic_p3_decision text CHECK(automatic_p3_decision IS NULL),
  effective boolean NOT NULL DEFAULT false CHECK(NOT effective),
  approval_evidence_verified boolean NOT NULL DEFAULT false CHECK(NOT approval_evidence_verified),
  device_execution_allowed boolean NOT NULL DEFAULT false CHECK(NOT device_execution_allowed),
  alert_suppression_allowed boolean NOT NULL DEFAULT false CHECK(NOT alert_suppression_allowed),
  record_source varchar(40) NOT NULL DEFAULT 'UNREVIEWED_CHANGE_NOTICE_DRAFT' CHECK(record_source='UNREVIEWED_CHANGE_NOTICE_DRAFT'),
  PRIMARY KEY(tenant_id,notice_id,version),
  FOREIGN KEY(tenant_id,notice_id) REFERENCES public.mop_change_notice_draft(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,notice_id,previous_version) REFERENCES public.mop_change_notice_revision(tenant_id,notice_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE') OR
    (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action<>'CREATE')),
  CHECK((action='ARCHIVE' AND archived) OR (action IN ('CREATE','UPDATE','RESTORE') AND NOT archived)),
  CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_change_notice_asset (
  tenant_id uuid NOT NULL,
  notice_id uuid NOT NULL,
  version bigint NOT NULL,
  position integer NOT NULL CHECK(position BETWEEN 1 AND 1000),
  asset_id uuid NOT NULL,
  PRIMARY KEY(tenant_id,notice_id,version,position),
  UNIQUE(tenant_id,notice_id,version,asset_id),
  FOREIGN KEY(tenant_id,notice_id,version) REFERENCES public.mop_change_notice_revision(tenant_id,notice_id,version),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id)
);
ALTER TABLE public.mop_change_notice_draft ADD CONSTRAINT mop_change_notice_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_change_notice_revision(tenant_id,notice_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_change_notice_draft_order ON public.mop_change_notice_draft(tenant_id,created_at,id);
CREATE FUNCTION public.mop_change_notice_head_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Change notice draft starts at version one';
    END IF;
  ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.applicant_id IS DISTINCT FROM OLD.applicant_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Change notice applicant identity and version are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_change_notice_head_guard BEFORE INSERT OR UPDATE ON public.mop_change_notice_draft
  FOR EACH ROW EXECUTE FUNCTION public.mop_change_notice_head_guard();
CREATE FUNCTION public.mop_change_notice_revision_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  IF NEW.creation_xid IS DISTINCT FROM pg_current_xact_id() THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice revision must belong to the current transaction';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_change_notice_revision_insert_guard BEFORE INSERT ON public.mop_change_notice_revision
  FOR EACH ROW EXECUTE FUNCTION public.mop_change_notice_revision_insert_guard();
CREATE FUNCTION public.mop_change_notice_asset_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE r public.mop_change_notice_revision%ROWTYPE;
BEGIN
  SELECT * INTO STRICT r FROM public.mop_change_notice_revision
    WHERE tenant_id=NEW.tenant_id AND notice_id=NEW.notice_id AND version=NEW.version;
  IF r.creation_xid<>pg_current_xact_id() OR NEW.position>r.asset_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice asset references are fixed in their creation transaction';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_change_notice_asset_insert_guard BEFORE INSERT ON public.mop_change_notice_asset
  FOR EACH ROW EXECUTE FUNCTION public.mop_change_notice_asset_insert_guard();
CREATE FUNCTION public.mop_change_notice_revision_complete() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  r public.mop_change_notice_revision%ROWTYPE;
  p public.mop_change_notice_revision%ROWTYPE;
  h public.mop_change_notice_draft%ROWTYPE;
  asset_total integer;
  maximum_position integer;
  definition_bytes bytea;
  asset_bytes bytea;
  field_value text;
BEGIN
  SELECT * INTO STRICT r FROM public.mop_change_notice_revision
    WHERE tenant_id=NEW.tenant_id AND notice_id=NEW.notice_id AND version=NEW.version;
  SELECT * INTO STRICT h FROM public.mop_change_notice_draft WHERE tenant_id=r.tenant_id AND id=r.notice_id;
  IF r.version>h.version OR r.recorded_at<h.created_at OR (r.version=1 AND r.recorded_by<>h.applicant_id) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice revision applicant or head is incomplete';
  END IF;
  SELECT count(*),max(position) INTO asset_total,maximum_position FROM public.mop_change_notice_asset
    WHERE tenant_id=r.tenant_id AND notice_id=r.notice_id AND version=r.version;
  IF asset_total<>r.asset_count OR maximum_position<>r.asset_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice draft requires all ordered asset references';
  END IF;
  definition_bytes=''::bytea;
  FOREACH field_value IN ARRAY ARRAY['MOP_CHANGE_NOTICE_DRAFT_V1',r.tenant_id::text,h.applicant_id::text,r.ticket_id::text,r.change_summary] LOOP
    IF field_value IS NULL THEN definition_bytes=definition_bytes||int4send(-1);
    ELSE definition_bytes=definition_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');END IF;
  END LOOP;
  definition_bytes=definition_bytes||int8send((extract(epoch FROM r.planned_start_at)*1000)::bigint);
  IF r.planned_end_at IS NULL THEN definition_bytes=definition_bytes||int4send(-1);
  ELSE definition_bytes=definition_bytes||int4send(8)||int8send((extract(epoch FROM r.planned_end_at)*1000)::bigint);END IF;
  definition_bytes=definition_bytes||int4send(r.asset_count);
  SELECT string_agg(int4send(octet_length(convert_to(asset_id::text,'UTF8')))||convert_to(asset_id::text,'UTF8'),''::bytea ORDER BY position)
    INTO asset_bytes FROM public.mop_change_notice_asset WHERE tenant_id=r.tenant_id AND notice_id=r.notice_id AND version=r.version;
  IF encode(sha256(definition_bytes||asset_bytes),'hex')<>r.content_sha256 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice draft content fingerprint mismatch';
  END IF;
  IF r.version>1 THEN
    SELECT * INTO STRICT p FROM public.mop_change_notice_revision
      WHERE tenant_id=r.tenant_id AND notice_id=r.notice_id AND version=r.previous_version;
    IF r.recorded_at<p.recorded_at OR (r.action='RESTORE')<>p.archived
      OR (r.action IN ('ARCHIVE','RESTORE') AND r.content_sha256<>p.content_sha256) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Notice archive transition must preserve the original proposal';
    END IF;
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_change_notice_revision_complete AFTER INSERT ON public.mop_change_notice_revision
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_change_notice_revision_complete();
REVOKE ALL ON FUNCTION public.mop_change_notice_head_guard(),public.mop_change_notice_revision_insert_guard(),public.mop_change_notice_asset_insert_guard(),public.mop_change_notice_revision_complete() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_change_notice_head_guard(),public.mop_change_notice_revision_insert_guard(),public.mop_change_notice_asset_insert_guard(),public.mop_change_notice_revision_complete() TO mop_test_runtime;
REVOKE ALL ON public.mop_change_notice_draft,public.mop_change_notice_revision,public.mop_change_notice_asset FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_change_notice_draft,public.mop_change_notice_revision,public.mop_change_notice_asset TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_change_notice_draft TO mop_test_runtime;
-- No runtime historical rewrites/deletes, approval grant, valid-window suppression or automatic P3 creation.
