-- Explicit independent manual worksheet drafts. No report publication, scans, automatic health or evidence grants.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Inspection worksheet drafts require the dedicated test migration identity';
  END IF;
END $$;
CREATE TABLE public.mop_inspection_worksheet (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  asset_id uuid NOT NULL,
  asset_name varchar(200) NOT NULL CHECK(char_length(asset_name) BETWEEN 1 AND 200
    AND asset_name !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND asset_name !~ U&'[\0001-\001F\007F-\009F]'),
  template_id uuid NOT NULL,
  template_version bigint NOT NULL CHECK(template_version BETWEEN 1 AND 9007199254740990),
  template_content_sha256 varchar(64) NOT NULL CHECK(template_content_sha256 ~ '^[0-9a-f]{64}$'),
  record_source varchar(40) NOT NULL DEFAULT 'MANUAL_INSPECTION_DRAFT' CHECK(record_source='MANUAL_INSPECTION_DRAFT'),
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,template_id,template_version) REFERENCES public.mop_inspection_template_revision(tenant_id,template_id,version),
  CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_inspection_worksheet_revision (
  tenant_id uuid NOT NULL,
  worksheet_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  previous_version bigint,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE','ARCHIVE','RESTORE')),
  archived boolean NOT NULL DEFAULT false,
  reported_performed_at timestamptz(3),
  observations jsonb NOT NULL CHECK(jsonb_typeof(observations)='array' AND octet_length(observations::text)<=2097152),
  answered_count integer GENERATED ALWAYS AS (CASE WHEN jsonb_typeof(observations)='array' THEN jsonb_array_length(observations) ELSE -1 END) STORED NOT NULL CHECK(answered_count BETWEEN 0 AND 100),
  content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  verification_status varchar(32) NOT NULL DEFAULT 'SELF_REPORTED_UNVERIFIED' CHECK(verification_status='SELF_REPORTED_UNVERIFIED'),
  PRIMARY KEY(tenant_id,worksheet_id,version),
  FOREIGN KEY(tenant_id,worksheet_id) REFERENCES public.mop_inspection_worksheet(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,worksheet_id,previous_version) REFERENCES public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE') OR
    (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action<>'CREATE')),
  CHECK((action='ARCHIVE' AND archived) OR (action IN ('CREATE','UPDATE','RESTORE') AND NOT archived)),
  CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(reported_performed_at IS NULL OR (reported_performed_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00' AND reported_performed_at<=recorded_at))
);
ALTER TABLE public.mop_inspection_worksheet ADD CONSTRAINT mop_inspection_worksheet_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_inspection_worksheet_order ON public.mop_inspection_worksheet(tenant_id,created_at,id);
CREATE INDEX mop_inspection_worksheet_asset ON public.mop_inspection_worksheet(tenant_id,asset_id,created_at,id);

CREATE FUNCTION public.mop_inspection_worksheet_head_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  source_version bigint;
  source_sha text;
  source_archived boolean;
  source_name text;
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet starts at version one';
    END IF;
    SELECT h.version,r.content_sha256,r.archived INTO source_version,source_sha,source_archived
      FROM public.mop_inspection_template h JOIN public.mop_inspection_template_revision r
        ON r.tenant_id=h.tenant_id AND r.template_id=h.id AND r.version=h.version
      WHERE h.tenant_id=NEW.tenant_id AND h.id=NEW.template_id FOR SHARE OF h;
    IF NOT FOUND THEN
      RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Worksheet template is outside the recorded scope';
    END IF;
    IF source_archived OR NEW.template_version IS DISTINCT FROM source_version OR NEW.template_content_sha256 IS DISTINCT FROM source_sha THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet must capture the current unarchived template candidate';
    END IF;
    SELECT name INTO source_name FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=NEW.asset_id;
    IF NOT FOUND THEN
      RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Worksheet asset is outside the recorded scope';
    END IF;
    IF NEW.asset_name IS DISTINCT FROM source_name THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet must capture the registered asset name';
    END IF;
  ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.created_by IS DISTINCT FROM OLD.created_by OR NEW.asset_id IS DISTINCT FROM OLD.asset_id
    OR NEW.asset_name IS DISTINCT FROM OLD.asset_name OR NEW.template_id IS DISTINCT FROM OLD.template_id
    OR NEW.template_version IS DISTINCT FROM OLD.template_version OR NEW.template_content_sha256 IS DISTINCT FROM OLD.template_content_sha256
    OR NEW.record_source IS DISTINCT FROM OLD.record_source OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet captured identity and version transition are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_worksheet_head_guard BEFORE INSERT OR UPDATE ON public.mop_inspection_worksheet
  FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_worksheet_head_guard();

-- Validate one complete JSON snapshot once, not once per item. Runtime cannot append to or update an old snapshot.
-- Evidence references deliberately have no input field until a scoped evidence resolver is implemented.
CREATE FUNCTION public.mop_inspection_worksheet_content_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  h public.mop_inspection_worksheet%ROWTYPE;
  p public.mop_inspection_worksheet_revision%ROWTYPE;
  value jsonb;
  seen text[]='{}'::text[];
  field_value text;
  bytes bytea=''::bytea;
  item_total integer;
BEGIN
  SELECT * INTO h FROM public.mop_inspection_worksheet WHERE tenant_id=NEW.tenant_id AND id=NEW.worksheet_id;
  IF NOT FOUND THEN
    RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Worksheet revision has no scoped head';
  END IF;
  IF NEW.version<>h.version OR NEW.recorded_at<h.created_at OR (NEW.version=1 AND NEW.recorded_by<>h.created_by) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet revision must complete its current head version';
  END IF;
  IF NEW.version>1 THEN
    SELECT * INTO p FROM public.mop_inspection_worksheet_revision
      WHERE tenant_id=NEW.tenant_id AND worksheet_id=NEW.worksheet_id AND version=NEW.previous_version;
    IF NOT FOUND THEN
      RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Worksheet previous revision is missing';
    END IF;
    IF NEW.recorded_at<p.recorded_at OR (NEW.action='RESTORE')<>p.archived
      OR (NEW.action='ARCHIVE' AND NEW.content_sha256<>p.content_sha256) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet archive transition is invalid';
    END IF;
  END IF;
  IF jsonb_typeof(NEW.observations) IS DISTINCT FROM 'array' OR octet_length(NEW.observations::text)>2097152 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet observations must be a bounded array';
  END IF;
  -- Generated columns are unavailable in BEFORE triggers: derive count only from the raw JSON input here.
  item_total=jsonb_array_length(NEW.observations);
  IF item_total>100 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet observation technical bound exceeded';
  END IF;
  FOR value IN SELECT * FROM jsonb_array_elements(NEW.observations) LOOP
    IF jsonb_typeof(value)<>'object' OR NOT(value ?& ARRAY['key','result','note'])
      OR value-ARRAY['key','result','note']<>'{}'::jsonb OR jsonb_typeof(value->'key')<>'string'
      OR (value->>'key') !~ '^[a-z][a-z0-9_-]{0,63}$' OR jsonb_typeof(value->'result')<>'string'
      OR (value->>'result') NOT IN ('NORMAL','ATTENTION','REQUIRES_ACTION') OR jsonb_typeof(value->'note') NOT IN ('string','null') THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet observation fields are invalid';
    END IF;
    IF (value->>'key')=ANY(seen) OR NOT EXISTS(SELECT 1 FROM public.mop_inspection_template_item i
      WHERE i.tenant_id=h.tenant_id AND i.template_id=h.template_id AND i.version=h.template_version AND i.item_key=value->>'key') THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet observation key is duplicate or outside its frozen template';
    END IF;
    seen=array_append(seen,value->>'key');
    IF jsonb_typeof(value->'note')='string' AND (char_length(value->>'note')>3000 OR (value->>'note') ~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]') THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet note is outside the explicit text bound';
    END IF;
  END LOOP;
  FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_WORKSHEET_V1',h.tenant_id::text,h.asset_id::text,h.asset_name,h.template_id::text] LOOP
    bytes=bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
  END LOOP;
  bytes=bytes||int8send(h.template_version)||int4send(octet_length(convert_to(h.template_content_sha256,'UTF8')))||convert_to(h.template_content_sha256,'UTF8');
  bytes=bytes||CASE WHEN NEW.reported_performed_at IS NULL THEN int4send(-1)
    ELSE int4send(8)||int8send((extract(epoch FROM NEW.reported_performed_at)*1000)::bigint) END;
  bytes=bytes||int4send(item_total);
  FOR value IN SELECT e.value FROM jsonb_array_elements(NEW.observations) e(value)
    JOIN public.mop_inspection_template_item i ON i.tenant_id=h.tenant_id AND i.template_id=h.template_id
      AND i.version=h.template_version AND i.item_key=e.value->>'key' ORDER BY i.position LOOP
    FOREACH field_value IN ARRAY ARRAY[value->>'key',value->>'result',value->>'note'] LOOP
      bytes=bytes||CASE WHEN field_value IS NULL THEN int4send(-1)
        ELSE int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8') END;
    END LOOP;
    bytes=bytes||int4send(0); -- No evidence grants or unverified foreign file bindings in this schema slice.
  END LOOP;
  IF octet_length(bytes)>2097152 OR NEW.content_sha256 IS DISTINCT FROM encode(sha256(bytes),'hex') THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Worksheet canonical content fingerprint mismatch';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_worksheet_content_guard BEFORE INSERT ON public.mop_inspection_worksheet_revision
  FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_worksheet_content_guard();
REVOKE ALL ON FUNCTION public.mop_inspection_worksheet_head_guard(),public.mop_inspection_worksheet_content_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_inspection_worksheet_head_guard(),public.mop_inspection_worksheet_content_guard() TO mop_test_runtime;
REVOKE ALL ON public.mop_inspection_worksheet,public.mop_inspection_worksheet_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_inspection_worksheet,public.mop_inspection_worksheet_revision TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_inspection_worksheet TO mop_test_runtime;
-- No runtime history rewrite, deletion, source relinking, automatic report or monitor-state modification.
