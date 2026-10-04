-- Unpublished daily plan proposals only. No credentials, scheduler, runs, devices or storage are configured.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Backup plan drafts require the dedicated test migration identity';
  END IF;
END $$;
CREATE TABLE public.mop_backup_plan_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_backup_plan_revision (
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  previous_version bigint,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE','ARCHIVE','RESTORE')),
  archived boolean NOT NULL DEFAULT false,
  name varchar(100) NOT NULL CHECK(char_length(name) BETWEEN 1 AND 100
    AND name !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND name !~ U&'[\0001-\001F\007F-\009F]'),
  daily_local_time varchar(5) NOT NULL CHECK(daily_local_time ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'),
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' CHECK(timezone='Asia/Shanghai'),
  credential_ref uuid,
  requested_enabled boolean NOT NULL,
  target_count integer NOT NULL CHECK(target_count BETWEEN 1 AND 1000),
  note varchar(4000) CHECK(note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  lifecycle_status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(lifecycle_status='DRAFT'),
  approval_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK(approval_status='UNVERIFIED'),
  enabled boolean NOT NULL DEFAULT false CHECK(NOT enabled),
  effective boolean NOT NULL DEFAULT false CHECK(NOT effective),
  PRIMARY KEY(tenant_id,plan_id,version),
  FOREIGN KEY(tenant_id,plan_id) REFERENCES public.mop_backup_plan_draft(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,plan_id,previous_version) REFERENCES public.mop_backup_plan_revision(tenant_id,plan_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE') OR
    (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action<>'CREATE')),
  CHECK((action='ARCHIVE' AND archived) OR (action IN ('CREATE','UPDATE','RESTORE') AND NOT archived)),
  CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_backup_plan_target (
  tenant_id uuid NOT NULL,
  plan_id uuid NOT NULL,
  version bigint NOT NULL,
  position integer NOT NULL CHECK(position BETWEEN 1 AND 1000),
  asset_id uuid NOT NULL,
  PRIMARY KEY(tenant_id,plan_id,version,position),
  UNIQUE(tenant_id,plan_id,version,asset_id),
  FOREIGN KEY(tenant_id,plan_id,version) REFERENCES public.mop_backup_plan_revision(tenant_id,plan_id,version),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id)
);
ALTER TABLE public.mop_backup_plan_draft ADD CONSTRAINT mop_backup_plan_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_backup_plan_revision(tenant_id,plan_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_backup_plan_draft_order ON public.mop_backup_plan_draft(tenant_id,created_at,id);
CREATE FUNCTION public.mop_backup_plan_head_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft starts at version one';
    END IF;
  ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft identity and version transition are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_backup_plan_head_guard BEFORE INSERT OR UPDATE ON public.mop_backup_plan_draft
  FOR EACH ROW EXECUTE FUNCTION public.mop_backup_plan_head_guard();
CREATE FUNCTION public.mop_backup_plan_revision_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  IF NEW.creation_xid<>pg_current_xact_id() THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft revision must belong to the current transaction';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_backup_plan_revision_insert_guard BEFORE INSERT ON public.mop_backup_plan_revision
  FOR EACH ROW EXECUTE FUNCTION public.mop_backup_plan_revision_insert_guard();
CREATE FUNCTION public.mop_backup_plan_target_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE r public.mop_backup_plan_revision%ROWTYPE;
BEGIN
  SELECT * INTO STRICT r FROM public.mop_backup_plan_revision
    WHERE tenant_id=NEW.tenant_id AND plan_id=NEW.plan_id AND version=NEW.version;
  IF r.creation_xid<>pg_current_xact_id() OR NEW.position>r.target_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup targets are fixed in their creation transaction';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_backup_plan_target_insert_guard BEFORE INSERT ON public.mop_backup_plan_target
  FOR EACH ROW EXECUTE FUNCTION public.mop_backup_plan_target_insert_guard();
CREATE FUNCTION public.mop_backup_plan_revision_complete() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  r public.mop_backup_plan_revision%ROWTYPE;
  p public.mop_backup_plan_revision%ROWTYPE;
  h public.mop_backup_plan_draft%ROWTYPE;
  target_total integer;
  maximum_position integer;
  current_targets uuid[];
  previous_targets uuid[];
BEGIN
  SELECT * INTO STRICT r FROM public.mop_backup_plan_revision
    WHERE tenant_id=NEW.tenant_id AND plan_id=NEW.plan_id AND version=NEW.version;
  SELECT * INTO STRICT h FROM public.mop_backup_plan_draft WHERE tenant_id=r.tenant_id AND id=r.plan_id;
  IF r.version>h.version OR r.recorded_at<h.created_at OR (r.version=1 AND r.recorded_by<>h.created_by) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft revision identity is incomplete';
  END IF;
  SELECT count(*),max(position),array_agg(asset_id ORDER BY position)
    INTO target_total,maximum_position,current_targets FROM public.mop_backup_plan_target
    WHERE tenant_id=r.tenant_id AND plan_id=r.plan_id AND version=r.version;
  IF target_total<>r.target_count OR maximum_position<>r.target_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft requires all ordered targets';
  END IF;
  IF r.version>1 THEN
    SELECT * INTO STRICT p FROM public.mop_backup_plan_revision
      WHERE tenant_id=r.tenant_id AND plan_id=r.plan_id AND version=r.previous_version;
    IF r.recorded_at<p.recorded_at OR (r.action='RESTORE')<>p.archived THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Backup draft archive transition is invalid';
    END IF;
    IF r.action IN ('ARCHIVE','RESTORE') THEN
      SELECT array_agg(asset_id ORDER BY position) INTO previous_targets FROM public.mop_backup_plan_target
        WHERE tenant_id=p.tenant_id AND plan_id=p.plan_id AND version=p.version;
      IF ROW(r.name,r.daily_local_time,r.credential_ref,r.requested_enabled,r.note)
        IS DISTINCT FROM ROW(p.name,p.daily_local_time,p.credential_ref,p.requested_enabled,p.note)
        OR current_targets IS DISTINCT FROM previous_targets THEN
        RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Archive and restore preserve the complete backup proposal';
      END IF;
    END IF;
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_backup_plan_revision_complete AFTER INSERT ON public.mop_backup_plan_revision
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_backup_plan_revision_complete();
REVOKE ALL ON FUNCTION public.mop_backup_plan_head_guard(),public.mop_backup_plan_revision_insert_guard(),public.mop_backup_plan_target_insert_guard(),public.mop_backup_plan_revision_complete() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_backup_plan_head_guard(),public.mop_backup_plan_revision_insert_guard(),public.mop_backup_plan_target_insert_guard(),public.mop_backup_plan_revision_complete() TO mop_test_runtime;
REVOKE ALL ON public.mop_backup_plan_draft,public.mop_backup_plan_revision,public.mop_backup_plan_target FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_backup_plan_draft,public.mop_backup_plan_revision,public.mop_backup_plan_target TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_backup_plan_draft TO mop_test_runtime;
-- No runtime revision/target updates, deletes, automatic activation, credential configuration or SSH execution.
