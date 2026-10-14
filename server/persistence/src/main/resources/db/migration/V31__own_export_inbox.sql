-- Explicit project migration. No backfill, account changes, application-startup migration or external delivery.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Export inbox requires dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_export_inbox (
  job_id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  recipient_id uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),
  published_version bigint NOT NULL CHECK(published_version BETWEEN 1 AND 9007199254740991),
  file_sha256 varchar(64) NOT NULL CHECK(file_sha256 ~ '^[0-9a-f]{64}$'),
  issued_at timestamptz(3) NOT NULL CHECK(issued_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:44:59.999+00'),
  expires_at timestamptz(3) NOT NULL CHECK(expires_at=issued_at+interval '15 minutes'),
  version bigint NOT NULL DEFAULT 0 CHECK(version IN (0,1)),
  read_at timestamptz(3),
  FOREIGN KEY(tenant_id,job_id) REFERENCES public.mop_asset_export_job(tenant_id,id),
  FOREIGN KEY(tenant_id,recipient_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((version=0 AND read_at IS NULL) OR (version=1 AND read_at IS NOT NULL AND read_at>=issued_at))
);
CREATE INDEX mop_export_inbox_owner ON public.mop_export_inbox(tenant_id,recipient_id,permission_version,issued_at DESC,job_id DESC);
CREATE INDEX mop_export_inbox_unread ON public.mop_export_inbox(tenant_id,recipient_id,permission_version) WHERE read_at IS NULL;
CREATE FUNCTION public.mop_export_inbox_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE j public.mop_asset_export_job%ROWTYPE;
BEGIN
  IF TG_OP='INSERT' THEN
    SELECT * INTO j FROM public.mop_asset_export_job WHERE id=NEW.job_id AND xmin::text=pg_current_xact_id()::text;
    IF NOT FOUND OR j.state<>'SUCCEEDED' OR j.object_id IS NULL OR
       (NEW.tenant_id,NEW.recipient_id,NEW.permission_version,NEW.published_version,NEW.file_sha256,NEW.issued_at)
       IS DISTINCT FROM (j.tenant_id,j.created_by,j.permission_version,j.version,j.file_sha256,j.stored_at)
       OR NEW.version<>0 OR NEW.read_at IS NOT NULL THEN
      RAISE EXCEPTION 'Export inbox must accompany the same committed publication transaction';
    END IF;
  ELSIF TG_OP='UPDATE' THEN
    IF (NEW.job_id,NEW.tenant_id,NEW.recipient_id,NEW.permission_version,NEW.published_version,NEW.file_sha256,NEW.issued_at,NEW.expires_at)
       IS DISTINCT FROM (OLD.job_id,OLD.tenant_id,OLD.recipient_id,OLD.permission_version,OLD.published_version,OLD.file_sha256,OLD.issued_at,OLD.expires_at)
       OR OLD.version<>0 OR OLD.read_at IS NOT NULL OR NEW.version<>1 OR NEW.read_at IS NULL
       OR NEW.read_at<OLD.issued_at OR NEW.read_at>clock_timestamp() THEN
      RAISE EXCEPTION 'Export inbox content and first-read history are immutable';
    END IF;
  ELSE RAISE EXCEPTION 'Export inbox deletion is forbidden';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_export_inbox_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_export_inbox FOR EACH ROW EXECUTE FUNCTION public.mop_export_inbox_guard();
REVOKE ALL ON public.mop_export_inbox FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_export_inbox TO mop_test_runtime;
GRANT UPDATE(version,read_at) ON public.mop_export_inbox TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_export_inbox_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_export_inbox_guard() TO mop_test_runtime;
