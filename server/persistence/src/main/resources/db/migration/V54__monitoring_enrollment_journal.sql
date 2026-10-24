-- Reserved candidate, never applied automatically. Registration remains disabled, not deployment.
DO $$ BEGIN
 IF current_user<>'mop_test_migrator' OR NOT(current_database()='mop_test' OR current_database() ~ '^mop_monitoring_test_[a-f0-9]{12}$') THEN RAISE EXCEPTION 'Dedicated monitoring migrator required'; END IF;
END $$;
CREATE TABLE public.mop_monitoring_enrollment (
 tenant_id uuid NOT NULL,id uuid NOT NULL,created_by uuid NOT NULL,permission_version bigint NOT NULL CHECK(permission_version>0),
 document jsonb NOT NULL CHECK(jsonb_typeof(document)='object' AND octet_length(document::text)<=524288),
 created_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,id), FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE TABLE public.mop_monitoring_enrollment_revision (
 tenant_id uuid NOT NULL,id uuid NOT NULL,version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
 action varchar(24) NOT NULL CHECK(action IN ('CREATE','APPROVE','REGISTER_DISABLED','CANCEL','PROBE')),
 phase varchar(24) NOT NULL CHECK(phase IN ('REQUESTED','APPROVED','REGISTERED_DISABLED','CANCELLED')),
 state jsonb NOT NULL CHECK(jsonb_typeof(state)='object' AND octet_length(state::text)<=786432),
 actor_id uuid NOT NULL,permission_version bigint NOT NULL CHECK(permission_version>0),recorded_at timestamptz(3) NOT NULL,
 PRIMARY KEY(tenant_id,id,version),FOREIGN KEY(tenant_id,id) REFERENCES public.mop_monitoring_enrollment(tenant_id,id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
 CHECK((state->>'version')::bigint=version AND state->>'phase'=phase)
);
CREATE INDEX mop_monitoring_enrollment_recent ON public.mop_monitoring_enrollment(tenant_id,created_at DESC,id DESC);
CREATE FUNCTION public.mop_monitoring_enrollment_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor uuid; member_role text; doc jsonb; cfg jsonb; binding jsonb; target jsonb;
 previous public.mop_monitoring_enrollment_revision%ROWTYPE;
 asset public.mop_asset%ROWTYPE; captured timestamptz;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Monitoring enrollment history is immutable'; END IF;
 IF TG_TABLE_NAME='mop_monitoring_enrollment' THEN actor:=NEW.created_by;doc:=NEW.document;captured:=NEW.created_at; ELSE actor:=NEW.actor_id; END IF;
 SELECT m.role_code INTO member_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=actor AND m.active AND u.active AND m.permission_version=NEW.permission_version FOR SHARE OF m,u;
 IF NOT FOUND OR member_role<>'padmin' OR NOT (ARRAY['assets:read','configurations:read','configurations:write']<@public.mop_effective_capabilities(actor,NEW.tenant_id)) THEN RAISE EXCEPTION 'Current monitoring enrollment authority required'; END IF;
 IF TG_TABLE_NAME='mop_monitoring_enrollment_revision' THEN
  SELECT document,created_at INTO doc,captured FROM public.mop_monitoring_enrollment WHERE tenant_id=NEW.tenant_id AND id=NEW.id FOR UPDATE;
  IF NOT FOUND OR NEW.state->'document' IS DISTINCT FROM doc THEN RAISE EXCEPTION 'Exact same tenant enrollment required'; END IF;
  SELECT * INTO previous FROM public.mop_monitoring_enrollment_revision WHERE tenant_id=NEW.tenant_id AND id=NEW.id ORDER BY version DESC LIMIT 1;
  IF NEW.version<>COALESCE(previous.version,0)+1 OR NEW.recorded_at<COALESCE(previous.recorded_at,captured) OR NEW.recorded_at>clock_timestamp() THEN RAISE EXCEPTION 'Monitoring enrollment version conflict'; END IF;
  IF ((NEW.action='CREATE' AND NEW.version=1 AND NEW.phase='REQUESTED')
    OR(NEW.action='APPROVE' AND previous.phase='REQUESTED' AND NEW.phase='APPROVED')
    OR(NEW.action='REGISTER_DISABLED' AND previous.phase='APPROVED' AND NEW.phase='REGISTERED_DISABLED')
    OR(NEW.action='CANCEL' AND previous.phase<>'CANCELLED' AND NEW.phase='CANCELLED')
    OR(NEW.action='PROBE' AND previous.phase='REGISTERED_DISABLED' AND NEW.phase='REGISTERED_DISABLED')) IS NOT TRUE THEN RAISE EXCEPTION 'Invalid enrollment transition'; END IF;
  IF NEW.phase IN ('APPROVED','REGISTERED_DISABLED') AND
    (NEW.state->>'reviewer' IS NOT NULL AND length(NEW.state->>'approval_policy')>0) IS NOT TRUE THEN RAISE EXCEPTION 'Explicit enrollment review required'; END IF;
  IF NEW.action='APPROVE' AND NEW.state->>'reviewer' IS DISTINCT FROM actor::text THEN RAISE EXCEPTION 'Current reviewer identity required'; END IF;
  IF NEW.action IN ('REGISTER_DISABLED','PROBE') AND
    (NEW.state->'reviewer'=previous.state->'reviewer' AND NEW.state->'approval_policy'=previous.state->'approval_policy') IS NOT TRUE THEN RAISE EXCEPTION 'Enrollment approval changed'; END IF;
  IF NEW.action IN ('REGISTER_DISABLED','PROBE') THEN
   PERFORM 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id
    AND m.user_id=(NEW.state->>'reviewer')::uuid AND m.active AND u.active AND m.role_code='padmin' FOR SHARE OF m,u;
   IF NOT FOUND OR (ARRAY['assets:read','configurations:read','configurations:write']<@public.mop_effective_capabilities((NEW.state->>'reviewer')::uuid,NEW.tenant_id)) IS NOT TRUE THEN RAISE EXCEPTION 'Enrollment reviewer no longer authorized'; END IF;
  END IF;
  IF NEW.phase IN ('REQUESTED','CANCELLED') AND
    (NEW.state->'reviewer'='null'::jsonb AND NEW.state->'approval_policy'='null'::jsonb AND NEW.state->'last_probe'='null'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Unapproved enrollment has no approval or probe'; END IF;
 END IF;
 IF (doc->>'id'=NEW.id::text AND doc->>'tenant_id'=NEW.tenant_id::text AND jsonb_array_length(doc->'assets') BETWEEN 1 AND 64
   AND doc->>'configuration_sha256'=encode(sha256(convert_to(doc->>'configuration','UTF8')),'hex')) IS NOT TRUE THEN RAISE EXCEPTION 'Enrollment identity or configuration hash invalid'; END IF;
 cfg:=(doc->>'configuration')::jsonb;
 IF (cfg->>'version'='1' AND jsonb_array_length(cfg->'targets') BETWEEN 1 AND 64) IS NOT TRUE THEN RAISE EXCEPTION 'Monitoring targets required'; END IF;
 FOR target IN SELECT * FROM jsonb_array_elements(cfg->'targets') LOOP
  IF (target->>'tenant_id'=NEW.tenant_id::text AND target->'enabled'='false'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Registration cannot activate a target'; END IF;
  IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(doc->'assets') a WHERE a->>'id'=target->>'asset_id') THEN RAISE EXCEPTION 'Target asset not frozen'; END IF;
 END LOOP;
 -- Cancellation remains possible after an asset changes; all other writes recheck exact source versions.
 IF TG_TABLE_NAME='mop_monitoring_enrollment' THEN
  IF (doc->>'created_by'=actor::text AND (doc->>'created_at')::timestamptz=NEW.created_at) IS NOT TRUE OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'Enrollment creation identity invalid'; END IF;
 ELSIF NEW.action='CANCEL' THEN RETURN NEW;
 END IF;
 FOR binding IN SELECT * FROM jsonb_array_elements(doc->'assets') ORDER BY value->>'id' LOOP
  SELECT * INTO asset FROM public.mop_asset WHERE tenant_id=NEW.tenant_id AND id=(binding->>'id')::uuid FOR SHARE;
  IF NOT FOUND OR asset.version<>(binding->>'version')::bigint OR asset.management_ip IS DISTINCT FROM binding->>'management_ip' THEN RAISE EXCEPTION 'Frozen asset changed'; END IF;
 END LOOP;
 IF TG_TABLE_NAME='mop_monitoring_enrollment_revision' THEN
  IF NEW.action='REGISTER_DISABLED' THEN
   FOR target IN SELECT * FROM jsonb_array_elements(cfg->'targets') ORDER BY value->>'id' LOOP
    PERFORM pg_advisory_xact_lock(hashtextextended('mop.monitoring.registration|'||NEW.tenant_id||'|'||(target->>'id'),0));
    IF EXISTS(SELECT 1 FROM public.mop_monitoring_enrollment_revision r
      WHERE r.tenant_id=NEW.tenant_id AND r.id<>NEW.id AND r.phase='REGISTERED_DISABLED'
      AND NOT EXISTS(SELECT 1 FROM public.mop_monitoring_enrollment_revision n WHERE n.tenant_id=r.tenant_id AND n.id=r.id AND n.version>r.version)
      AND EXISTS(SELECT 1 FROM jsonb_array_elements((r.state#>>'{document,configuration}')::jsonb->'targets') t WHERE t->>'id'=target->>'id')) THEN RAISE EXCEPTION 'Monitoring target already has a current registration'; END IF;
   END LOOP;
  ELSIF NEW.action='PROBE' THEN
   IF NOT EXISTS(SELECT 1 FROM jsonb_array_elements(cfg->'targets') t WHERE t->>'id'=NEW.state#>>'{last_probe,target_id}') THEN RAISE EXCEPTION 'Probe target not registered'; END IF;
   IF (jsonb_typeof(NEW.state->'last_probe')='object'
     AND NEW.state#>>'{last_probe,status}' IN ('NOT_RUN','FAILED','TCP_REACHABLE_PROTOCOL_UNVERIFIED','SAMPLE_VERIFIED_ONCE')
     AND NEW.state#>>'{last_probe,configuration_sha256}' ~ '^[a-f0-9]{64}$'
     AND (NEW.state#>>'{last_probe,observed_at}')::timestamptz BETWEEN captured AND NEW.recorded_at) IS NOT TRUE THEN RAISE EXCEPTION 'Bounded current probe evidence required'; END IF;
   IF NEW.state#>>'{last_probe,status}'='SAMPLE_VERIFIED_ONCE' THEN
    IF ((NEW.state#>>'{last_probe,sample_count}')::bigint BETWEEN 1 AND 100000 AND jsonb_array_length(NEW.state#>'{last_probe,matched_metrics}') BETWEEN 1 AND 100) IS NOT TRUE THEN RAISE EXCEPTION 'Actual once-sample summary required'; END IF;
   ELSIF (NEW.state#>'{last_probe,sample_count}'='null'::jsonb AND NEW.state#>'{last_probe,matched_metrics}'='[]'::jsonb) IS NOT TRUE THEN RAISE EXCEPTION 'Unverified probes cannot carry successful counts'; END IF;
  END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_monitoring_enrollment_document_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_monitoring_enrollment FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_enrollment_guard();
CREATE TRIGGER mop_monitoring_enrollment_revision_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_monitoring_enrollment_revision FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_enrollment_guard();
REVOKE ALL ON public.mop_monitoring_enrollment,public.mop_monitoring_enrollment_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_monitoring_enrollment,public.mop_monitoring_enrollment_revision TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_monitoring_enrollment_guard() FROM PUBLIC;
