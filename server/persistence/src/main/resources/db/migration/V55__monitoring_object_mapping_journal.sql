-- Reserved, NOT_APPLIED candidate. Mapping publication is not collection, provisioning or dispatch authorization.
DO $$ BEGIN
 IF current_user<>'mop_test_migrator' OR NOT(current_database()='mop_test' OR current_database() ~ '^mop_monitoring_test_[a-f0-9]{12}$') THEN RAISE EXCEPTION 'Dedicated monitoring mapping migrator required'; END IF;
END $$;
CREATE TABLE public.mop_monitoring_object_binding (
 tenant_id uuid NOT NULL,id uuid NOT NULL,pool_id uuid NOT NULL,object_type varchar(8) NOT NULL CHECK(object_type IN ('HOST','ITEM')),
 external_id varchar(256) NOT NULL CHECK(length(btrim(external_id))>0),provider varchar(64) NOT NULL,
 document jsonb NOT NULL CHECK(jsonb_typeof(document)='object' AND octet_length(document::text)<=8192),
 created_by uuid NOT NULL,permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),created_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,id),UNIQUE(tenant_id,pool_id,object_type,external_id),
 FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_monitoring_object_binding_key ON public.mop_monitoring_object_binding(pool_id,object_type,external_id);
CREATE INDEX mop_monitoring_object_binding_recent ON public.mop_monitoring_object_binding(tenant_id,created_at DESC,id DESC);
CREATE TABLE public.mop_monitoring_object_binding_revision (
 tenant_id uuid NOT NULL,id uuid NOT NULL,version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
 action varchar(24) NOT NULL CHECK(action IN ('CREATE','SUBMIT_DRAFT','CANCEL_DRAFT','ACTIVATE','REVOKE')),
 state jsonb NOT NULL CHECK(jsonb_typeof(state)='object' AND octet_length(state::text)<=32768),
 draft_asset_id uuid GENERATED ALWAYS AS ((state#>>'{draft,asset_id}')::uuid) STORED,
 active_asset_id uuid GENERATED ALWAYS AS ((state#>>'{active,approved_draft,asset_id}')::uuid) STORED,
 actor_id uuid NOT NULL,permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),recorded_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,id,version),FOREIGN KEY(tenant_id,id) REFERENCES public.mop_monitoring_object_binding(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,draft_asset_id) REFERENCES public.mop_asset(tenant_id,id),
 FOREIGN KEY(tenant_id,active_asset_id) REFERENCES public.mop_asset(tenant_id,id),
 CHECK((state->>'version')::bigint=version AND (state->>'updated_at')::timestamptz=recorded_at)
);
CREATE INDEX mop_monitoring_mapping_draft_asset ON public.mop_monitoring_object_binding_revision(tenant_id,draft_asset_id,id) WHERE draft_asset_id IS NOT NULL;
CREATE INDEX mop_monitoring_mapping_active_asset ON public.mop_monitoring_object_binding_revision(tenant_id,active_asset_id,id) WHERE active_asset_id IS NOT NULL;
CREATE FUNCTION public.mop_monitoring_object_binding_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor uuid; role text; root public.mop_monitoring_object_binding%ROWTYPE; prior public.mop_monitoring_object_binding_revision%ROWTYPE;
 draft jsonb; active jsonb; source jsonb; asset public.mop_asset%ROWTYPE; ref text;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Monitoring mapping history is immutable'; END IF;
 IF TG_TABLE_NAME='mop_monitoring_object_binding' THEN actor:=NEW.created_by;ELSE actor:=NEW.actor_id;END IF;
 SELECT m.role_code INTO role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
  WHERE m.tenant_id=NEW.tenant_id AND m.user_id=actor AND m.active AND u.active AND m.permission_version=NEW.permission_version FOR SHARE OF m,u;
 IF NOT FOUND OR role<>'padmin' OR (ARRAY['assets:read','configurations:read','configurations:write']<@public.mop_effective_capabilities(actor,NEW.tenant_id)) IS NOT TRUE THEN RAISE EXCEPTION 'Current mapping administrator required'; END IF;
 IF TG_TABLE_NAME='mop_monitoring_object_binding' THEN
  IF (NEW.document->>'id'=NEW.id::text AND NEW.document->>'tenant_id'=NEW.tenant_id::text
    AND NEW.document#>>'{key,pool_id}'=NEW.pool_id::text AND NEW.document#>>'{key,object_type}'=NEW.object_type
    AND NEW.document#>>'{key,external_id}'=NEW.external_id AND NEW.document->>'provider'=NEW.provider
    AND NEW.document->>'created_by'=actor::text AND (NEW.document->>'created_at')::timestamptz=NEW.created_at
    AND NEW.created_at<=clock_timestamp()) IS NOT TRUE THEN RAISE EXCEPTION 'Exact immutable mapping document required'; END IF;
  RETURN NEW;
 END IF;
 SELECT * INTO root FROM public.mop_monitoring_object_binding WHERE tenant_id=NEW.tenant_id AND id=NEW.id FOR UPDATE;
 IF NOT FOUND OR NEW.state->'document' IS DISTINCT FROM root.document THEN RAISE EXCEPTION 'Same-tenant immutable mapping required'; END IF;
 SELECT * INTO prior FROM public.mop_monitoring_object_binding_revision WHERE tenant_id=NEW.tenant_id AND id=NEW.id ORDER BY version DESC LIMIT 1;
 IF NEW.version<>COALESCE(prior.version,0)+1 OR NEW.recorded_at<COALESCE(prior.recorded_at,root.created_at) OR NEW.recorded_at>clock_timestamp() THEN RAISE EXCEPTION 'Mapping optimistic version conflict'; END IF;
 IF ((NEW.state->>'version')::bigint=NEW.version AND (NEW.state->>'updated_at')::timestamptz=NEW.recorded_at
  AND (NEW.state->'reason'='null'::jsonb OR (jsonb_typeof(NEW.state->'reason')='string' AND length(btrim(NEW.state->>'reason')) BETWEEN 1 AND 500))) IS NOT TRUE THEN RAISE EXCEPTION 'Exact revision state and bounded reason required'; END IF;
 draft:=NEW.state->'draft';active:=NEW.state->'active';
 IF draft IS NULL OR active IS NULL OR NEW.state->'reason' IS NULL THEN RAISE EXCEPTION 'Explicit null/current mapping state required'; END IF;
 IF ((NEW.action='CREATE' AND NEW.version=1 AND actor=root.created_by AND draft<>'null'::jsonb AND active='null'::jsonb)
   OR(NEW.action='SUBMIT_DRAFT' AND prior.version IS NOT NULL AND draft<>'null'::jsonb AND active IS NOT DISTINCT FROM prior.state->'active')
   OR(NEW.action='CANCEL_DRAFT' AND prior.state->'draft'<>'null'::jsonb AND draft='null'::jsonb AND active IS NOT DISTINCT FROM prior.state->'active')
   OR(NEW.action='ACTIVATE' AND prior.state->'draft'<>'null'::jsonb AND draft='null'::jsonb AND active<>'null'::jsonb)
   OR(NEW.action='REVOKE' AND prior.state->'active'<>'null'::jsonb AND active='null'::jsonb AND draft IS NOT DISTINCT FROM prior.state->'draft' AND length(btrim(NEW.state->>'reason'))>0)) IS NOT TRUE THEN RAISE EXCEPTION 'Invalid mapping state transition'; END IF;
 IF NEW.action IN ('CREATE','SUBMIT_DRAFT') THEN
  IF (draft->>'submitted_by'=actor::text AND (draft->>'submitted_at')::timestamptz=NEW.recorded_at AND (draft->>'asset_version')::bigint BETWEEN 1 AND 9007199254740991) IS NOT TRUE THEN RAISE EXCEPTION 'Current mapping proposal identity required'; END IF;
  SELECT * INTO asset FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=(draft->>'asset_id')::uuid FOR SHARE;
  IF NOT FOUND OR asset.version<>(draft->>'asset_version')::bigint THEN RAISE EXCEPTION 'Mapping proposal asset changed'; END IF;
 END IF;
 IF NEW.action='ACTIVATE' THEN
  source:=active->'source';ref:=active->>'policy_reference';
  IF (active->'approved_draft'=prior.state->'draft' AND active->>'activated_by'=actor::text AND (active->>'activated_at')::timestamptz=NEW.recorded_at
    AND (active->>'expires_at')::timestamptz>NEW.recorded_at AND (active->>'expires_at')::timestamptz<=NEW.recorded_at+interval '1 day'
    AND ref ~ '^[A-Za-z0-9._:-]+:[a-f0-9]{64}$' AND source->'key'=root.document->'key' AND source->>'provider'=root.provider
    AND source->>'claimed_pool_id'=root.pool_id::text AND source->>'claimed_tenant_id'=NEW.tenant_id::text
    AND source->>'claimed_asset_id'=active#>>'{approved_draft,asset_id}' AND source->>'definition_sha256' ~ '^[a-f0-9]{64}$'
    AND source->>'source_host_id' ~ '^[1-9][0-9]{0,19}$' AND source->>'metadata_sha256'=encode(sha256(convert_to(concat_ws(E'\n','MOP_MAPPING_SOURCE_METADATA_V1',root.pool_id::text,root.object_type,root.external_id,root.provider,source->>'source_host_id',source->>'claimed_pool_id',source->>'claimed_tenant_id',source->>'claimed_asset_id'),'UTF8')),'hex')) IS NOT TRUE THEN RAISE EXCEPTION 'Exact mapping source/approval facts required'; END IF;
  SELECT * INTO asset FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=(active#>>'{approved_draft,asset_id}')::uuid FOR SHARE;
  IF NOT FOUND OR asset.version<>(active#>>'{approved_draft,asset_version}')::bigint THEN RAISE EXCEPTION 'Activation asset changed'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.mapping.key|'||root.pool_id||'|'||root.object_type||'|'||root.external_id,0));
  -- An expired/invalid foreign publication still reserves ownership until explicitly revoked. No implicit tenant transfer.
  IF EXISTS(SELECT 1 FROM public.mop_monitoring_object_binding b JOIN LATERAL
    (SELECT r.state FROM public.mop_monitoring_object_binding_revision r WHERE r.tenant_id=b.tenant_id AND r.id=b.id ORDER BY r.version DESC LIMIT 1) latest ON true
    WHERE b.pool_id=root.pool_id AND b.object_type=root.object_type AND b.external_id=root.external_id AND (b.tenant_id,b.id)<>(root.tenant_id,root.id)
      AND latest.state->'active'<>'null'::jsonb) THEN RAISE EXCEPTION 'Object already has a published mapping'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_monitoring_object_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_monitoring_object_binding FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_object_binding_guard();
CREATE TRIGGER mop_monitoring_object_binding_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_monitoring_object_binding_revision FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_object_binding_guard();
REVOKE ALL ON public.mop_monitoring_object_binding,public.mop_monitoring_object_binding_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_monitoring_object_binding,public.mop_monitoring_object_binding_revision TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_monitoring_object_binding_guard() FROM PUBLIC;
