-- Explicit independent migration only. Frozen V1--V22 are not edited.
-- These immutable source collections are not quarterly reports, approved metrics, file grants or publication.
CREATE TABLE public.mop_inspection_source_collection (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  label varchar(200) NOT NULL CHECK(char_length(btrim(label))>0 AND label !~ U&'[\0001-\001F\007F-\009F]'),
  recorded_at timestamp(3) with time zone NOT NULL,
  insert_transaction xid8 NOT NULL DEFAULT pg_current_xact_id(),
  version bigint NOT NULL DEFAULT 1 CHECK(version=1),
  document_format varchar(40) NOT NULL DEFAULT 'MOP_INSPECTION_SOURCE_DOCUMENT_V1' CHECK(document_format='MOP_INSPECTION_SOURCE_DOCUMENT_V1'),
  source_count integer NOT NULL CHECK(source_count BETWEEN 0 AND 1000),
  document bytea NOT NULL CHECK(octet_length(document) BETWEEN 1 AND 33554432),
  document_sha256 varchar(64) GENERATED ALWAYS AS (encode(sha256(document),'hex')) STORED NOT NULL,
  source_manifest_sha256 varchar(64) NOT NULL CHECK(source_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  record_source varchar(48) NOT NULL DEFAULT 'EXPLICIT_IMMUTABLE_SOURCE_COLLECTION' CHECK(record_source='EXPLICIT_IMMUTABLE_SOURCE_COLLECTION'),
  verification_status varchar(32) NOT NULL DEFAULT 'SELF_REPORTED_UNVERIFIED' CHECK(verification_status='SELF_REPORTED_UNVERIFIED'),
  report_published boolean NOT NULL DEFAULT false CHECK(NOT report_published),
  formal_report_generatable boolean NOT NULL DEFAULT false CHECK(NOT formal_report_generatable),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(recorded_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_inspection_source_collection_item (
  tenant_id uuid NOT NULL,
  collection_id uuid NOT NULL,
  position integer NOT NULL CHECK(position BETWEEN 1 AND 1000),
  worksheet_id uuid NOT NULL,
  worksheet_version bigint NOT NULL CHECK(worksheet_version BETWEEN 1 AND 9007199254740990),
  source_snapshot_sha256 varchar(64) NOT NULL CHECK(source_snapshot_sha256 ~ '^[0-9a-f]{64}$'),
  PRIMARY KEY(tenant_id,collection_id,position),
  UNIQUE(tenant_id,collection_id,worksheet_id,worksheet_version),
  FOREIGN KEY(tenant_id,collection_id) REFERENCES public.mop_inspection_source_collection(tenant_id,id),
  FOREIGN KEY(tenant_id,worksheet_id,worksheet_version) REFERENCES public.mop_inspection_worksheet_revision(tenant_id,worksheet_id,version)
);
CREATE INDEX mop_inspection_source_collection_order ON public.mop_inspection_source_collection(tenant_id,recorded_at,id);

CREATE FUNCTION public.mop_inspection_source_document_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  doc jsonb;
  s jsonb;
  c jsonb;
  value jsonb;
  expected_items jsonb;
  expected_observations jsonb;
  h public.mop_inspection_worksheet%ROWTYPE;
  r public.mop_inspection_worksheet_revision%ROWTYPE;
  t public.mop_inspection_template_revision%ROWTYPE;
  field_value text;
  template_bytes bytea;
  content_bytes bytea;
  source_bytes bytea;
  manifest_bytes bytea=''::bytea;
  source_hash text;
  previous_id text;
  previous_version bigint;
  canonical_total bigint=0;
BEGIN
  IF NEW.insert_transaction IS DISTINCT FROM pg_current_xact_id() THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Collection insertion transaction is not current';
  END IF;
  IF NEW.source_count IS NULL OR NEW.source_count NOT BETWEEN 0 AND 1000 OR NEW.document IS NULL OR octet_length(NEW.document) NOT BETWEEN 1 AND 33554432 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source collection technical bound exceeded';
  END IF;
  doc=convert_from(NEW.document,'UTF8')::jsonb;
  IF jsonb_typeof(doc) IS DISTINCT FROM 'object' OR jsonb_typeof(doc->'sources') IS DISTINCT FROM 'array'
    OR doc-'sources' IS DISTINCT FROM jsonb_build_object('format',NEW.document_format,'tenant_id',NEW.tenant_id::text,'manifest_sha256',NEW.source_manifest_sha256)
    OR jsonb_array_length(doc->'sources')<>NEW.source_count THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source document root is not the scoped collection';
  END IF;
  FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_SOURCE_MANIFEST_V1',NEW.tenant_id::text] LOOP
    manifest_bytes=manifest_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
  END LOOP;
  manifest_bytes=manifest_bytes||int4send(NEW.source_count);
  FOR s IN SELECT * FROM jsonb_array_elements(doc->'sources') LOOP
    IF jsonb_typeof(s) IS DISTINCT FROM 'object' OR jsonb_typeof(s->'version') IS DISTINCT FROM 'number'
      OR (s->>'version') !~ '^[1-9][0-9]{0,15}$' OR jsonb_typeof(s->'worksheet_id') IS DISTINCT FROM 'string'
      OR (s->>'worksheet_id') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$' THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source identity is invalid';
    END IF;
    IF previous_id IS NOT NULL AND (previous_id>s->>'worksheet_id' OR (previous_id=s->>'worksheet_id' AND previous_version>=(s->>'version')::bigint)) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source order or duplicate revision is invalid';
    END IF;
    previous_id=s->>'worksheet_id';previous_version=(s->>'version')::bigint;
    SELECT * INTO h FROM public.mop_inspection_worksheet WHERE tenant_id=NEW.tenant_id AND id=(s->>'worksheet_id')::uuid;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Collection source has no scoped worksheet';END IF;
    SELECT * INTO r FROM public.mop_inspection_worksheet_revision WHERE tenant_id=NEW.tenant_id AND worksheet_id=h.id AND version=(s->>'version')::bigint AND version<=h.version;
    IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Collection source has no scoped immutable revision';END IF;
    IF r.recorded_at>NEW.recorded_at OR s-ARRAY['content','source_sha256','created_at','recorded_at'] IS DISTINCT FROM jsonb_build_object('worksheet_id',h.id::text,'version',r.version,'created_by',h.created_by::text,'recorded_by',r.recorded_by::text,'action',r.action,'archived',r.archived)
      OR jsonb_typeof(s->'created_at') IS DISTINCT FROM 'string' OR jsonb_typeof(s->'recorded_at') IS DISTINCT FROM 'string'
      OR (s->>'created_at')::timestamptz IS DISTINCT FROM h.created_at OR (s->>'recorded_at')::timestamptz IS DISTINCT FROM r.recorded_at THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source recording metadata does not match its immutable revision';
    END IF;
    SELECT * INTO t FROM public.mop_inspection_template_revision WHERE tenant_id=h.tenant_id AND template_id=h.template_id AND version=h.template_version;
    IF NOT FOUND OR t.content_sha256<>h.template_content_sha256 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Captured template revision is inconsistent';END IF;
    SELECT COALESCE(jsonb_agg(jsonb_build_object('key',i.item_key,'section',i.section,'label',i.label,'instruction',i.instruction) ORDER BY i.position),'[]'::jsonb) INTO expected_items
      FROM public.mop_inspection_template_item i WHERE i.tenant_id=h.tenant_id AND i.template_id=h.template_id AND i.version=h.template_version;
    SELECT COALESCE(jsonb_agg(e.value||jsonb_build_object('evidence_ids','[]'::jsonb) ORDER BY i.position),'[]'::jsonb) INTO expected_observations
      FROM jsonb_array_elements(r.observations) e(value) JOIN public.mop_inspection_template_item i ON i.tenant_id=h.tenant_id AND i.template_id=h.template_id AND i.version=h.template_version AND i.item_key=e.value->>'key';
    c=s->'content';
    IF jsonb_typeof(c) IS DISTINCT FROM 'object' OR NOT(c ?& ARRAY['reported_performed_at','template','observations'])
      OR c-ARRAY['reported_performed_at','template','observations'] IS DISTINCT FROM jsonb_build_object('asset_id',h.asset_id::text,'asset_name',h.asset_name,'content_sha256',r.content_sha256)
      OR c->'template' IS DISTINCT FROM jsonb_build_object('id',h.template_id::text,'version',h.template_version,'device_type',t.device_type,'name',t.name,'content_sha256',t.content_sha256,'items',expected_items)
      OR c->'observations' IS DISTINCT FROM expected_observations
      OR (r.reported_performed_at IS NULL AND c->'reported_performed_at' IS DISTINCT FROM 'null'::jsonb)
      OR (r.reported_performed_at IS NOT NULL AND (jsonb_typeof(c->'reported_performed_at') IS DISTINCT FROM 'string' OR (c->>'reported_performed_at')::timestamptz IS DISTINCT FROM r.reported_performed_at)) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source body does not match its frozen template and manual revision';
    END IF;
    template_bytes=''::bytea;
    FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_TEMPLATE_V1',t.device_type,t.name] LOOP
      template_bytes=template_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
    END LOOP;
    template_bytes=template_bytes||int4send(t.item_count);
    FOR value IN SELECT * FROM jsonb_array_elements(expected_items) LOOP
      FOREACH field_value IN ARRAY ARRAY[value->>'key',value->>'section',value->>'label',value->>'instruction'] LOOP
        template_bytes=template_bytes||CASE WHEN field_value IS NULL THEN int4send(-1) ELSE int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8') END;
      END LOOP;
    END LOOP;
    content_bytes=''::bytea;
    FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_WORKSHEET_V1',h.tenant_id::text,h.asset_id::text,h.asset_name,h.template_id::text] LOOP
      content_bytes=content_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
    END LOOP;
    content_bytes=content_bytes||int8send(h.template_version)||int4send(64)||convert_to(h.template_content_sha256,'UTF8')
      ||CASE WHEN r.reported_performed_at IS NULL THEN int4send(-1) ELSE int4send(8)||int8send((extract(epoch FROM r.reported_performed_at)*1000)::bigint) END||int4send(jsonb_array_length(expected_observations));
    FOR value IN SELECT * FROM jsonb_array_elements(expected_observations) LOOP
      FOREACH field_value IN ARRAY ARRAY[value->>'key',value->>'result',value->>'note'] LOOP
        content_bytes=content_bytes||CASE WHEN field_value IS NULL THEN int4send(-1) ELSE int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8') END;
      END LOOP;
      content_bytes=content_bytes||int4send(0);
    END LOOP;
    IF encode(sha256(template_bytes),'hex')<>t.content_sha256 OR encode(sha256(content_bytes),'hex')<>r.content_sha256 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Existing captured source hashes are inconsistent';
    END IF;
    source_bytes=''::bytea;
    FOREACH field_value IN ARRAY ARRAY['MOP_INSPECTION_SOURCE_SNAPSHOT_V1',h.tenant_id::text,h.id::text] LOOP
      source_bytes=source_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
    END LOOP;
    source_bytes=source_bytes||int8send(r.version)||int4send(36)||convert_to(h.created_by::text,'UTF8')||int8send((extract(epoch FROM h.created_at)*1000)::bigint)
      ||int4send(36)||convert_to(r.recorded_by::text,'UTF8')||int8send((extract(epoch FROM r.recorded_at)*1000)::bigint)||int4send(octet_length(convert_to(r.action,'UTF8')))||convert_to(r.action,'UTF8')
      ||CASE WHEN r.archived THEN decode('01','hex') ELSE decode('00','hex') END||int4send(octet_length(template_bytes))||template_bytes||int4send(octet_length(content_bytes))||content_bytes;
    source_hash=encode(sha256(source_bytes),'hex');canonical_total=canonical_total+octet_length(source_bytes);
    IF canonical_total>16777216 OR s->>'source_sha256' IS DISTINCT FROM source_hash THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source metadata hash or canonical total is invalid';END IF;
    manifest_bytes=manifest_bytes||int4send(36)||convert_to(h.id::text,'UTF8')||int8send(r.version)||int4send(64)||convert_to(source_hash,'UTF8');
  END LOOP;
  IF encode(sha256(manifest_bytes),'hex') IS DISTINCT FROM NEW.source_manifest_sha256 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source manifest hash is invalid';END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_source_document_guard BEFORE INSERT ON public.mop_inspection_source_collection
  FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_source_document_guard();

-- The item gate prevents new members being appended after the collection's creating transaction.
-- Exactly one deferred collection check runs, rather than reparsing a large document once per item.
CREATE FUNCTION public.mop_inspection_source_item_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE creation xid8;declared_count integer;
BEGIN
  SELECT insert_transaction,source_count INTO creation,declared_count FROM public.mop_inspection_source_collection WHERE tenant_id=NEW.tenant_id AND id=NEW.collection_id;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Source item has no scoped collection';END IF;
  IF creation IS DISTINCT FROM pg_current_xact_id() THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Immutable collection members may only be inserted in the creating transaction';END IF;
  IF NEW.position NOT BETWEEN 1 AND declared_count THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Source position exceeds immutable collection cardinality';END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_source_item_insert_guard BEFORE INSERT ON public.mop_inspection_source_collection_item
  FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_source_item_insert_guard();
CREATE FUNCTION public.mop_inspection_source_collection_complete() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE count_items integer;value jsonb;ordinal bigint;
BEGIN
  SELECT count(*) INTO count_items FROM public.mop_inspection_source_collection_item WHERE tenant_id=NEW.tenant_id AND collection_id=NEW.id;
  IF count_items<>NEW.source_count THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Immutable collection item set is incomplete';END IF;
  FOR value,ordinal IN SELECT e.value,e.ordinality FROM jsonb_array_elements(convert_from(NEW.document,'UTF8')::jsonb->'sources') WITH ORDINALITY e(value,ordinality) LOOP
    IF NOT EXISTS(SELECT 1 FROM public.mop_inspection_source_collection_item i WHERE i.tenant_id=NEW.tenant_id AND i.collection_id=NEW.id AND i.position=ordinal AND i.worksheet_id=(value->>'worksheet_id')::uuid AND i.worksheet_version=(value->>'version')::bigint AND i.source_snapshot_sha256=value->>'source_sha256') THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Immutable collection item does not match its encoded source';
    END IF;
  END LOOP;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_inspection_source_collection_complete AFTER INSERT ON public.mop_inspection_source_collection
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_source_collection_complete();
REVOKE ALL ON FUNCTION public.mop_inspection_source_document_guard(),public.mop_inspection_source_item_insert_guard(),public.mop_inspection_source_collection_complete() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_inspection_source_document_guard(),public.mop_inspection_source_item_insert_guard(),public.mop_inspection_source_collection_complete() TO mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_inspection_source_collection,public.mop_inspection_source_collection_item TO mop_test_runtime;
