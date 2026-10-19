-- CANDIDATE: explicit independent migration only. V1--V39 remain frozen.
-- Request configuration is unapproved draft content, never a runtime/customer approval.
CREATE TABLE public.mop_inspection_quarterly_report (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  label varchar(200) NOT NULL CHECK(char_length(btrim(label))>0 AND label !~ U&'[\0001-\001F\007F-\009F]'),
  recorded_at timestamp(3) with time zone NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version=1),
  source_collection_id uuid NOT NULL,
  source_document_sha256 varchar(64) NOT NULL CHECK(source_document_sha256 ~ '^[0-9a-f]{64}$'),
  source_manifest_sha256 varchar(64) NOT NULL CHECK(source_manifest_sha256 ~ '^[0-9a-f]{64}$'),
  year integer NOT NULL CHECK(year BETWEEN 1 AND 9998),
  quarter integer NOT NULL CHECK(quarter BETWEEN 1 AND 4),
  report_sha256 varchar(64) NOT NULL CHECK(report_sha256 ~ '^[0-9a-f]{64}$'),
  document bytea NOT NULL CHECK(octet_length(document) BETWEEN 1 AND 41943040),
  document_sha256 varchar(64) GENERATED ALWAYS AS (encode(sha256(document),'hex')) STORED NOT NULL,
  document_format varchar(48) NOT NULL DEFAULT 'MOP_INSPECTION_QUARTERLY_DOCUMENT_V1' CHECK(document_format='MOP_INSPECTION_QUARTERLY_DOCUMENT_V1'),
  configuration_source varchar(40) NOT NULL DEFAULT 'REQUEST_DRAFT_NOT_APPROVED' CHECK(configuration_source='REQUEST_DRAFT_NOT_APPROVED'),
  generation_status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(generation_status='DRAFT'),
  report_published boolean NOT NULL DEFAULT false CHECK(NOT report_published),
  report_publishable boolean NOT NULL DEFAULT false CHECK(NOT report_publishable),
  evidence_access_granted boolean NOT NULL DEFAULT false CHECK(NOT evidence_access_granted),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,source_collection_id) REFERENCES public.mop_inspection_source_collection(tenant_id,id),
  CHECK(recorded_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE INDEX mop_inspection_quarterly_report_order ON public.mop_inspection_quarterly_report(tenant_id,recorded_at,id);
CREATE FUNCTION public.mop_inspection_quarterly_report_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  source public.mop_inspection_source_collection%ROWTYPE;
  doc jsonb;
  config jsonb;
  value text;
  canonical bytea=''::bytea;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Quarterly report snapshots are append only';END IF;
  IF NEW.document IS NULL OR octet_length(NEW.document) NOT BETWEEN 1 AND 41943040 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report document exceeds technical bound';
  END IF;
  SELECT * INTO source FROM public.mop_inspection_source_collection WHERE tenant_id=NEW.tenant_id AND id=NEW.source_collection_id;
  IF NOT FOUND THEN RAISE EXCEPTION USING ERRCODE='23503',MESSAGE='Report source collection is not in scope';END IF;
  IF source.document_sha256 IS DISTINCT FROM NEW.source_document_sha256 OR source.source_manifest_sha256 IS DISTINCT FROM NEW.source_manifest_sha256 OR source.recorded_at>NEW.recorded_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report source hashes or time do not match immutable collection';
  END IF;
  doc=convert_from(NEW.document,'UTF8')::jsonb;config=doc->'configuration';
  IF jsonb_typeof(doc) IS DISTINCT FROM 'object' OR doc-ARRAY['configuration','source_document','output'] IS DISTINCT FROM jsonb_build_object(
    'format',NEW.document_format,'tenant_id',NEW.tenant_id::text,'source_collection_id',NEW.source_collection_id::text,
    'source_document_sha256',NEW.source_document_sha256,'source_manifest_sha256',NEW.source_manifest_sha256,'report_sha256',NEW.report_sha256,
    'configuration_source',NEW.configuration_source,'year',NEW.year,'quarter',NEW.quarter,'completeness','COMPLETE_EXPLICIT_SELECTION')
    OR doc->'source_document' IS DISTINCT FROM convert_from(source.document,'UTF8')::jsonb
    OR jsonb_typeof(config) IS DISTINCT FROM 'object'
    OR NOT(config ?& ARRAY['revision','timeZone','duplicateRule','archivedRule','metricVersion','reportTemplateVersion'])
    OR config-ARRAY['revision','timeZone','duplicateRule','archivedRule','metricVersion','reportTemplateVersion']<>'{}'::jsonb
    OR jsonb_typeof(doc->'output') IS DISTINCT FROM 'object'
    OR doc->'output'->'generation_status' IS DISTINCT FROM '"DRAFT"'::jsonb
    OR doc->'output'->'report_published' IS DISTINCT FROM 'false'::jsonb
    OR doc->'output'->'report_publishable' IS DISTINCT FROM 'false'::jsonb
    OR doc->'output'->'evidence_access_granted' IS DISTINCT FROM 'false'::jsonb
    OR doc->'output'->'tenant_asset_population' IS DISTINCT FROM 'null'::jsonb THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report document scope, provenance or draft boundary is invalid';
  END IF;
  FOR value IN SELECT key FROM jsonb_each(config) LOOP
    IF jsonb_typeof(config->value) NOT IN ('null','string') OR char_length(config->>value)>200 OR (config->>value IS NOT NULL AND (char_length(btrim(config->>value))=0 OR config->>value ~ U&'[\0001-\001F\007F-\009F]')) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report configuration value is invalid';
    END IF;
  END LOOP;
  IF (config->>'duplicateRule' IS NOT NULL AND config->>'duplicateRule' NOT IN ('REQUIRE_SINGLE_SOURCE','LATEST_REPORTED_PERFORMED_AT'))
    OR (config->>'archivedRule' IS NOT NULL AND config->>'archivedRule' NOT IN ('INCLUDE','EXCLUDE')) OR char_length(config->>'timeZone')>100 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report selection configuration is invalid';
  END IF;
  FOREACH value IN ARRAY ARRAY['MOP_INSPECTION_QUARTERLY_DRAFT_V1',NEW.source_manifest_sha256] LOOP
    canonical=canonical||int4send(octet_length(convert_to(value,'UTF8')))||convert_to(value,'UTF8');
  END LOOP;
  canonical=canonical||int4send(NEW.year)||int4send(NEW.quarter);
  FOREACH value IN ARRAY ARRAY[config->>'revision',config->>'timeZone',config->>'duplicateRule',config->>'archivedRule',config->>'metricVersion',config->>'reportTemplateVersion','COMPLETE_EXPLICIT_SELECTION'] LOOP
    canonical=canonical||CASE WHEN value IS NULL THEN int4send(-1) ELSE int4send(octet_length(convert_to(value,'UTF8')))||convert_to(value,'UTF8') END;
  END LOOP;
  IF encode(sha256(canonical),'hex') IS DISTINCT FROM NEW.report_sha256 THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Report input fingerprint is invalid';END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_inspection_quarterly_report_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_inspection_quarterly_report
FOR EACH ROW EXECUTE FUNCTION public.mop_inspection_quarterly_report_guard();
REVOKE ALL ON FUNCTION public.mop_inspection_quarterly_report_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_inspection_quarterly_report_guard() TO mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_inspection_quarterly_report TO mop_test_runtime;

-- Explicit scope: only the built-in platform administrator receives the new draft capabilities.
-- Existing custom roles remain unchanged. Future tenants use the expanded padmin limit.
INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES
 ('padmin','inspection_quarterly_reports:read'),('padmin','inspection_quarterly_reports:write');
UPDATE public.mop_project_role SET permissions=(SELECT array_agg(p ORDER BY p) FROM unnest(permissions||ARRAY['inspection_quarterly_reports:read','inspection_quarterly_reports:write']) p),version=version+1,updated_at=statement_timestamp()
WHERE builtin AND base_role='padmin';
