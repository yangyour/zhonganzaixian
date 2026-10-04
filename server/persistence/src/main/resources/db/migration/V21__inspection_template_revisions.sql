-- Explicit unpublished inspection-template revisions. No seeded templates, scans, worksheet data or reports.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Inspection templates require the dedicated test migration identity';
  END IF;
END $$;
CREATE TABLE public.mop_inspection_template (
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
CREATE TABLE public.mop_inspection_template_revision (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  previous_version bigint,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE','ARCHIVE','RESTORE')),
  archived boolean NOT NULL DEFAULT false,
  device_type varchar(64) NOT NULL CHECK(device_type ~ '^[a-z][a-z0-9_-]{0,63}$'),
  name varchar(200) NOT NULL CHECK(char_length(name) BETWEEN 1 AND 200
    AND name !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND name !~ U&'[\0001-\001F\007F-\009F]'),
  item_count integer NOT NULL CHECK(item_count BETWEEN 1 AND 100),
  content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  approval_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK(approval_status='UNVERIFIED'),
  PRIMARY KEY(tenant_id,template_id,version),
  FOREIGN KEY(tenant_id,template_id) REFERENCES public.mop_inspection_template(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,template_id,previous_version) REFERENCES public.mop_inspection_template_revision(tenant_id,template_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE') OR
    (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action<>'CREATE')),
  CHECK((action='ARCHIVE' AND archived) OR (action IN ('CREATE','UPDATE','RESTORE') AND NOT archived)),
  CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_inspection_template_item (
  tenant_id uuid NOT NULL,
  template_id uuid NOT NULL,
  version bigint NOT NULL,
  position integer NOT NULL CHECK(position BETWEEN 1 AND 100),
  item_key varchar(64) NOT NULL CHECK(item_key ~ '^[a-z][a-z0-9_-]{0,63}$'),
  section varchar(100) NOT NULL CHECK(char_length(section) BETWEEN 1 AND 100
    AND section !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND section !~ U&'[\0001-\001F\007F-\009F]'),
  label varchar(200) NOT NULL CHECK(char_length(label) BETWEEN 1 AND 200
    AND label !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND label !~ U&'[\0001-\001F\007F-\009F]'),
  instruction varchar(2000) CHECK(instruction !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  PRIMARY KEY(tenant_id,template_id,version,position),
  UNIQUE(tenant_id,template_id,version,item_key),
  FOREIGN KEY(tenant_id,template_id,version) REFERENCES public.mop_inspection_template_revision(tenant_id,template_id,version)
);
ALTER TABLE public.mop_inspection_template ADD CONSTRAINT mop_inspection_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_inspection_template_revision(tenant_id,template_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_inspection_template_order ON public.mop_inspection_template(tenant_id,created_at,id);

CREATE FUNCTION public.mop_inspection_template_head_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection template starts at version one';
    END IF;
  ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection template identity and version transition are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_template_head_guard BEFORE INSERT OR UPDATE ON public.mop_inspection_template
  FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_template_head_guard();

-- Deferred complete revision validation prevents partial/extra items and binds the exact Java V1 fingerprint.
CREATE FUNCTION public.mop_inspection_template_revision_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  r public.mop_inspection_template_revision%ROWTYPE;
  p public.mop_inspection_template_revision%ROWTYPE;
  h public.mop_inspection_template%ROWTYPE;
  item_total integer;
  maximum_position integer;
  definition_bytes bytea;
  item_bytes bytea;
  field_value text;
BEGIN
  SELECT * INTO STRICT r FROM public.mop_inspection_template_revision
    WHERE tenant_id=NEW.tenant_id AND template_id=NEW.template_id AND version=NEW.version;
  SELECT * INTO STRICT h FROM public.mop_inspection_template WHERE tenant_id=r.tenant_id AND id=r.template_id;
  IF r.version>h.version OR r.recorded_at<h.created_at OR (r.version=1 AND r.recorded_by<>h.created_by) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection revision identity is incomplete';
  END IF;
  IF r.version>1 THEN
    SELECT * INTO STRICT p FROM public.mop_inspection_template_revision
      WHERE tenant_id=r.tenant_id AND template_id=r.template_id AND version=r.previous_version;
    IF r.recorded_at<p.recorded_at OR (r.action='RESTORE')<>p.archived
      OR (r.action='ARCHIVE' AND r.content_sha256<>p.content_sha256) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection revision archive transition is invalid';
    END IF;
  END IF;
  SELECT count(*),max(position) INTO item_total,maximum_position FROM public.mop_inspection_template_item
    WHERE tenant_id=r.tenant_id AND template_id=r.template_id AND version=r.version;
  IF item_total<>r.item_count OR maximum_position<>r.item_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection revision requires all ordered items';
  END IF;
  definition_bytes=''::bytea;
  FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_TEMPLATE_V1',r.device_type,r.name] LOOP
    definition_bytes=definition_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
  END LOOP;
  definition_bytes=definition_bytes||int4send(r.item_count);
  SELECT string_agg(
    int4send(octet_length(convert_to(item_key,'UTF8')))||convert_to(item_key,'UTF8')||
    int4send(octet_length(convert_to(section,'UTF8')))||convert_to(section,'UTF8')||
    int4send(octet_length(convert_to(label,'UTF8')))||convert_to(label,'UTF8')||
    CASE WHEN instruction IS NULL THEN int4send(-1)
      ELSE int4send(octet_length(convert_to(instruction,'UTF8')))||convert_to(instruction,'UTF8') END,
    ''::bytea ORDER BY position) INTO item_bytes FROM public.mop_inspection_template_item
    WHERE tenant_id=r.tenant_id AND template_id=r.template_id AND version=r.version;
  IF encode(sha256(definition_bytes||item_bytes),'hex')<>r.content_sha256 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Inspection revision fingerprint mismatch';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_inspection_template_revision_complete AFTER INSERT ON public.mop_inspection_template_revision
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_template_revision_guard();
CREATE CONSTRAINT TRIGGER mop_inspection_template_items_complete AFTER INSERT ON public.mop_inspection_template_item
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_template_revision_guard();
REVOKE ALL ON FUNCTION public.mop_inspection_template_head_guard(),public.mop_inspection_template_revision_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_inspection_template_head_guard(),public.mop_inspection_template_revision_guard() TO mop_test_runtime;
REVOKE ALL ON public.mop_inspection_template,public.mop_inspection_template_revision,public.mop_inspection_template_item FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_inspection_template,public.mop_inspection_template_revision,public.mop_inspection_template_item TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_inspection_template TO mop_test_runtime;
-- Runtime cannot rewrite revision/items, delete/truncate, grant privileges or bypass tenant/actor FK scope.
