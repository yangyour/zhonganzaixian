-- Reserved candidate, NOT APPLIED. Explicit coordinated migrator only; no API/Worker migration.
DO $$ BEGIN
 IF current_user<>'mop_test_migrator' OR NOT(current_database()='mop_test' OR current_database() ~ '^mop_report_test_[a-f0-9]{12}$') THEN
  RAISE EXCEPTION 'Dedicated report test migrator required';
 END IF;
END $$;
CREATE TABLE public.mop_report_publication_document (
 tenant_id uuid NOT NULL, id uuid NOT NULL, source_report_id uuid NOT NULL,
 source_document_sha256 varchar(64) NOT NULL CHECK(source_document_sha256 ~ '^[a-f0-9]{64}$'),
 label varchar(200) NOT NULL CHECK(length(trim(label))>0),
 payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 2 AND 2097152),
 payload_sha256 varchar(64) GENERATED ALWAYS AS (encode(sha256(payload),'hex')) STORED,
 created_by uuid NOT NULL, permission_version bigint NOT NULL CHECK(permission_version>0),
 captured_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,source_report_id) REFERENCES public.mop_inspection_quarterly_report(tenant_id,id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE TABLE public.mop_report_publication_artifact (
 tenant_id uuid NOT NULL, document_id uuid NOT NULL, format varchar(4) NOT NULL CHECK(format IN ('HTML','PDF')),
 render_version bigint NOT NULL CHECK(render_version>1),
 content bytea NOT NULL CHECK(octet_length(content) BETWEEN 1 AND 8388608),
 content_sha256 varchar(64) GENERATED ALWAYS AS (encode(sha256(content),'hex')) STORED,
 metadata jsonb NOT NULL CHECK(jsonb_typeof(metadata)='object' AND octet_length(metadata::text)<4096),
 actor_id uuid NOT NULL, permission_version bigint NOT NULL CHECK(permission_version>0),
 PRIMARY KEY(tenant_id,document_id,format,render_version),
 FOREIGN KEY(tenant_id,document_id) REFERENCES public.mop_report_publication_document(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE TABLE public.mop_report_publication_revision (
 tenant_id uuid NOT NULL, document_id uuid NOT NULL, version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
 action varchar(16) NOT NULL CHECK(action IN ('CREATE','QUEUE','RENDER','RENDER_FAILED','APPROVE','RETURN','PUBLISH')),
 phase varchar(20) NOT NULL CHECK(phase IN ('DRAFT','GENERATING','REVIEW_REQUIRED','APPROVED','PUBLISHED','RETURNED','FAILED')),
 state jsonb NOT NULL CHECK(jsonb_typeof(state)='object' AND octet_length(state::text)<=131072),
 actor_id uuid NOT NULL, permission_version bigint NOT NULL CHECK(permission_version>0), recorded_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,document_id,version),
 FOREIGN KEY(tenant_id,document_id) REFERENCES public.mop_report_publication_document(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
 CHECK((state->>'version')::bigint=version AND state->>'phase'=phase)
);
CREATE TABLE public.mop_report_publication_read (
 tenant_id uuid NOT NULL, document_id uuid NOT NULL, customer_id uuid NOT NULL,
 document_sha256 varchar(64) NOT NULL CHECK(document_sha256 ~ '^[a-f0-9]{64}$'),
 permission_version bigint NOT NULL CHECK(permission_version>0), first_read_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,document_id,customer_id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES public.mop_report_publication_document(tenant_id,id),
 FOREIGN KEY(tenant_id,customer_id) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE TABLE public.mop_report_publication_delivery_intent (
 tenant_id uuid NOT NULL, id uuid NOT NULL, document_id uuid NOT NULL, recipient_id uuid NOT NULL,
 document_sha256 varchar(64) NOT NULL, html_sha256 varchar(64) NOT NULL, pdf_sha256 varchar(64) NOT NULL,
 cause varchar(24) NOT NULL CHECK(cause IN ('INITIAL_PUBLICATION','RESEND')),
 actor_id uuid NOT NULL, permission_version bigint NOT NULL CHECK(permission_version>0), requested_at timestamptz(3) NOT NULL,
 external_dispatch_enabled boolean NOT NULL DEFAULT false CHECK(NOT external_dispatch_enabled),
 PRIMARY KEY(tenant_id,id),
 FOREIGN KEY(tenant_id,document_id) REFERENCES public.mop_report_publication_document(tenant_id,id),
 FOREIGN KEY(tenant_id,recipient_id) REFERENCES public.mop_membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_report_publication_recent ON public.mop_report_publication_document(tenant_id,captured_at DESC,id DESC);
CREATE FUNCTION public.mop_report_publication_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor uuid; epoch bigint; report public.mop_report_publication_document%ROWTYPE;
 previous public.mop_report_publication_revision%ROWTYPE; source public.mop_inspection_quarterly_report%ROWTYPE;
 payload_json jsonb; artifact jsonb; fmt text; capability text;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Report publication evidence is immutable'; END IF;
 IF TG_TABLE_NAME='mop_report_publication_document' THEN actor:=NEW.created_by;
 ELSIF TG_TABLE_NAME='mop_report_publication_read' THEN actor:=NEW.customer_id; ELSE actor:=NEW.actor_id; END IF;
 epoch := NEW.permission_version;
 capability := CASE WHEN TG_TABLE_NAME='mop_report_publication_read' THEN 'dashboard:read' ELSE 'inspection_quarterly_reports:write' END;
 PERFORM 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=actor AND m.active AND u.active AND m.permission_version=epoch FOR SHARE OF m,u;
 IF NOT FOUND OR (capability=ANY(public.mop_effective_capabilities(actor,NEW.tenant_id))) IS NOT TRUE THEN RAISE EXCEPTION 'Current report authority required'; END IF;
 IF TG_TABLE_NAME='mop_report_publication_document' THEN
  SELECT * INTO source FROM public.mop_inspection_quarterly_report WHERE tenant_id=NEW.tenant_id AND id=NEW.source_report_id;
  IF NOT FOUND OR source.document_sha256<>NEW.source_document_sha256 THEN RAISE EXCEPTION 'Exact frozen report source required'; END IF;
  payload_json := convert_from(NEW.payload,'UTF8')::jsonb;
  IF (payload_json->>'format'='MOP_REPORT_PUBLICATION_V1' AND payload_json->>'tenant_id'=NEW.tenant_id::text
    AND payload_json->>'source_id'=NEW.source_report_id::text AND payload_json->>'source_document_sha256'=NEW.source_document_sha256
    AND payload_json->'output'=(convert_from(source.document,'UTF8')::jsonb)->'output') IS NOT TRUE THEN RAISE EXCEPTION 'Publication payload must use frozen output'; END IF;
  IF NEW.captured_at>clock_timestamp() OR NEW.captured_at<source.recorded_at THEN RAISE EXCEPTION 'Invalid capture time'; END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO report FROM public.mop_report_publication_document WHERE tenant_id=NEW.tenant_id AND id=NEW.document_id FOR UPDATE;
 IF NOT FOUND THEN RAISE EXCEPTION 'Same tenant report required'; END IF;
 SELECT * INTO previous FROM public.mop_report_publication_revision WHERE tenant_id=NEW.tenant_id AND document_id=NEW.document_id ORDER BY version DESC LIMIT 1;
 IF TG_TABLE_NAME='mop_report_publication_artifact' THEN
  IF previous.phase IS DISTINCT FROM 'GENERATING' OR NEW.render_version<>previous.version OR
    (NEW.metadata->>'format'=NEW.format AND NEW.metadata->>'document_sha256'=report.payload_sha256
     AND NEW.metadata->>'content_sha256'=encode(sha256(NEW.content),'hex') AND (NEW.metadata->>'bytes')::bigint=octet_length(NEW.content)) IS NOT TRUE THEN
   RAISE EXCEPTION 'Artifact must match current generation and exact bytes'; END IF;
 ELSIF TG_TABLE_NAME='mop_report_publication_revision' THEN
  IF NEW.version<>COALESCE(previous.version,0)+1 OR NEW.recorded_at>clock_timestamp() OR NEW.recorded_at<COALESCE(previous.recorded_at,report.captured_at) THEN RAISE EXCEPTION 'Report revision conflict'; END IF;
  IF (NEW.state#>>'{document,tenant_id}'=NEW.tenant_id::text AND NEW.state#>>'{document,id}'=NEW.document_id::text AND NEW.state#>>'{document,document_sha256}'=report.payload_sha256) IS NOT TRUE THEN RAISE EXCEPTION 'Report state binding invalid'; END IF;
  IF previous.version IS NOT NULL AND NEW.state->'document' IS DISTINCT FROM previous.state->'document' THEN RAISE EXCEPTION 'Frozen report document cannot change'; END IF;
  IF ((NEW.action='CREATE' AND NEW.version=1 AND NEW.phase='DRAFT')
   OR (NEW.action='QUEUE' AND previous.phase IN ('DRAFT','FAILED') AND NEW.phase='GENERATING')
   OR (NEW.action IN ('RENDER','RENDER_FAILED') AND previous.phase='GENERATING' AND NEW.phase IN ('REVIEW_REQUIRED','FAILED'))
   OR (NEW.action='APPROVE' AND previous.phase='REVIEW_REQUIRED' AND NEW.phase='APPROVED')
   OR (NEW.action='RETURN' AND previous.phase IN ('REVIEW_REQUIRED','APPROVED') AND NEW.phase='RETURNED')
   OR (NEW.action='PUBLISH' AND previous.phase='APPROVED' AND NEW.phase='PUBLISHED')) IS NOT TRUE THEN RAISE EXCEPTION 'Invalid report phase transition'; END IF;
  IF NEW.action IN ('APPROVE','RETURN','PUBLISH') AND ('configurations:write'=ANY(public.mop_effective_capabilities(actor,NEW.tenant_id))) IS NOT TRUE THEN RAISE EXCEPTION 'Report management authority required'; END IF;
  IF NEW.phase IN ('REVIEW_REQUIRED','APPROVED','PUBLISHED') AND
    (jsonb_typeof(NEW.state->'html')='object' AND jsonb_typeof(NEW.state->'pdf')='object'
     AND NEW.state#>>'{html,content_sha256}'=NEW.state#>>'{pdf,html_sha256}'
     AND NEW.state#>>'{html,font_sha256}'=NEW.state#>>'{pdf,font_sha256}') IS NOT TRUE THEN RAISE EXCEPTION 'Exact dual output required'; END IF;
  IF NEW.phase NOT IN ('APPROVED','PUBLISHED') AND
    (NEW.state->'reviewer'='null'::jsonb AND NEW.state->'approved_at'='null'::jsonb AND NEW.state->'approved_policy_reference'='null'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Unapproved state cannot contain approval'; END IF;
  IF NEW.phase<>'PUBLISHED' AND
    (NEW.state->'publisher'='null'::jsonb AND NEW.state->'published_at'='null'::jsonb AND NEW.state->'recipients'='[]'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Unpublished state cannot contain recipients'; END IF;
  IF NEW.action='APPROVE' THEN
   IF (NEW.state#>'{document,incomplete_reasons}'='[]'::jsonb AND NEW.state->>'reviewer'=actor::text
     AND (NEW.state->>'approved_at')::timestamptz=NEW.recorded_at AND length(NEW.state->>'approved_policy_reference')>0
     AND NEW.state->'html'=previous.state->'html' AND NEW.state->'pdf'=previous.state->'pdf') IS NOT TRUE THEN RAISE EXCEPTION 'Approval requires complete unchanged output'; END IF;
  ELSIF NEW.action='PUBLISH' THEN
   IF (NEW.state->>'publisher'=actor::text AND (NEW.state->>'published_at')::timestamptz=NEW.recorded_at
     AND NEW.state->'reviewer'=previous.state->'reviewer' AND NEW.state->'approved_at'=previous.state->'approved_at'
     AND NEW.state->'approved_policy_reference'=previous.state->'approved_policy_reference'
     AND NEW.state->'html'=previous.state->'html' AND NEW.state->'pdf'=previous.state->'pdf'
     AND jsonb_array_length(NEW.state->'recipients') BETWEEN 1 AND 1000) IS NOT TRUE THEN RAISE EXCEPTION 'Publication must retain exact approval'; END IF;
   IF EXISTS(SELECT 1 FROM jsonb_array_elements_text(NEW.state->'recipients') r(id)
     WHERE NOT EXISTS(SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
       WHERE m.tenant_id=NEW.tenant_id AND m.user_id=r.id::uuid AND m.active AND u.active AND m.role_code IN ('viewer','tadmin'))) THEN RAISE EXCEPTION 'Active same tenant customer recipients required'; END IF;
  END IF;
  FOREACH fmt IN ARRAY ARRAY['HTML','PDF'] LOOP
   artifact:=NEW.state->lower(fmt);
   IF artifact IS NOT NULL AND artifact<>'null'::jsonb AND NOT EXISTS(SELECT 1 FROM public.mop_report_publication_artifact a WHERE a.tenant_id=NEW.tenant_id AND a.document_id=NEW.document_id AND a.format=fmt AND a.content_sha256=artifact->>'content_sha256' AND a.metadata=artifact) THEN RAISE EXCEPTION 'Report artifact evidence missing'; END IF;
  END LOOP;
 ELSIF TG_TABLE_NAME='mop_report_publication_read' THEN
  IF previous.phase IS DISTINCT FROM 'PUBLISHED' OR NOT (previous.state->'recipients' ? NEW.customer_id::text)
   OR NEW.document_sha256<>report.payload_sha256 OR NEW.first_read_at<(previous.state->>'published_at')::timestamptz OR NEW.first_read_at>clock_timestamp() THEN RAISE EXCEPTION 'Published recipient read required'; END IF;
 ELSIF TG_TABLE_NAME='mop_report_publication_delivery_intent' THEN
  IF ('configurations:write'=ANY(public.mop_effective_capabilities(actor,NEW.tenant_id))) IS NOT TRUE THEN RAISE EXCEPTION 'Delivery intent management authority required'; END IF;
  IF previous.phase IS DISTINCT FROM 'PUBLISHED' OR NOT(previous.state->'recipients' ? NEW.recipient_id::text)
   OR NEW.document_sha256<>report.payload_sha256 OR NEW.html_sha256 IS DISTINCT FROM previous.state#>>'{html,content_sha256}'
   OR NEW.pdf_sha256 IS DISTINCT FROM previous.state#>>'{pdf,content_sha256}' OR NEW.requested_at>clock_timestamp()
   OR NEW.requested_at<(previous.state->>'published_at')::timestamptz THEN RAISE EXCEPTION 'Delivery intent must reuse published output'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_report_publication_document_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_report_publication_document FOR EACH ROW EXECUTE FUNCTION public.mop_report_publication_guard();
CREATE TRIGGER mop_report_publication_artifact_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_report_publication_artifact FOR EACH ROW EXECUTE FUNCTION public.mop_report_publication_guard();
CREATE TRIGGER mop_report_publication_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_report_publication_revision FOR EACH ROW EXECUTE FUNCTION public.mop_report_publication_guard();
CREATE TRIGGER mop_report_publication_read_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_report_publication_read FOR EACH ROW EXECUTE FUNCTION public.mop_report_publication_guard();
CREATE TRIGGER mop_report_publication_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_report_publication_delivery_intent FOR EACH ROW EXECUTE FUNCTION public.mop_report_publication_guard();
REVOKE ALL ON public.mop_report_publication_document,public.mop_report_publication_artifact,public.mop_report_publication_revision,public.mop_report_publication_read,public.mop_report_publication_delivery_intent FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_report_publication_document,public.mop_report_publication_artifact,public.mop_report_publication_revision,public.mop_report_publication_read,public.mop_report_publication_delivery_intent TO mop_test_runtime;
-- Row locking requires one column's UPDATE privilege; the immutable trigger rejects every actual UPDATE.
GRANT UPDATE(id) ON public.mop_report_publication_document TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_report_publication_guard() FROM PUBLIC;
