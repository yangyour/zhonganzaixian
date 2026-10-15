-- Immutable tenant-scoped runtime configuration releases. No customer confirmation is fabricated.
DO $$ BEGIN IF current_database() <> 'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;

CREATE TABLE public.mop_runtime_configuration_release (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  configuration_key varchar(128) NOT NULL REFERENCES public.mop_configuration(key),
  version bigint NOT NULL CHECK (version BETWEEN 1 AND 9007199254740990),
  action varchar(16) NOT NULL CHECK (action IN ('PUBLISH','ROLLBACK','DISABLE')),
  revision_id uuid,
  revision_version bigint,
  target_release_id uuid,
  value_json jsonb,
  definition_hash varchar(64) NOT NULL CHECK (definition_hash ~ '^[0-9a-f]{64}$'),
  environment varchar(16) NOT NULL DEFAULT 'remote_test' CHECK (environment='remote_test'),
  scope varchar(32) NOT NULL DEFAULT 'CURRENT_TENANT' CHECK (scope='CURRENT_TENANT'),
  effective_at timestamptz(3) NOT NULL,
  expires_at timestamptz(3),
  published_by uuid NOT NULL,
  published_permission_version bigint NOT NULL CHECK (published_permission_version>0),
  published_at timestamptz(3) NOT NULL DEFAULT clock_timestamp(),
  reason varchar(500) NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 500),
  customer_confirmation_status varchar(24) NOT NULL DEFAULT 'UNVERIFIED' CHECK (customer_confirmation_status='UNVERIFIED'),
  production_approved boolean NOT NULL DEFAULT false CHECK (NOT production_approved),
  UNIQUE(tenant_id,configuration_key,version),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,revision_id) REFERENCES public.mop_configuration_revision(tenant_id,id),
  FOREIGN KEY(tenant_id,target_release_id) REFERENCES public.mop_runtime_configuration_release(tenant_id,id),
  FOREIGN KEY(tenant_id,published_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK (expires_at IS NULL OR expires_at>effective_at),
  CHECK (effective_at>=published_at),
  CHECK ((action='DISABLE' AND revision_id IS NULL AND revision_version IS NULL AND target_release_id IS NULL AND value_json IS NULL)
    OR (action='PUBLISH' AND revision_id IS NOT NULL AND revision_version>0 AND target_release_id IS NULL AND value_json IS NOT NULL AND jsonb_typeof(value_json)<>'null')
    OR (action='ROLLBACK' AND revision_id IS NOT NULL AND revision_version>0 AND target_release_id IS NOT NULL AND value_json IS NOT NULL AND jsonb_typeof(value_json)<>'null'))
);
CREATE INDEX mop_runtime_configuration_effective ON public.mop_runtime_configuration_release(tenant_id,configuration_key,version DESC);

CREATE FUNCTION public.mop_runtime_configuration_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE source_record public.mop_configuration_revision%ROWTYPE; target_record public.mop_runtime_configuration_release%ROWTYPE; latest bigint;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Runtime releases are immutable'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.runtime.configuration|'||NEW.tenant_id||'|'||NEW.configuration_key,0));
  SELECT COALESCE(max(version),0) INTO latest FROM public.mop_runtime_configuration_release WHERE tenant_id=NEW.tenant_id AND configuration_key=NEW.configuration_key;
  IF NEW.version<>latest+1 THEN RAISE EXCEPTION 'Runtime release version mismatch'; END IF;
  IF NOT EXISTS(SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.published_by AND m.active AND u.active AND m.role_code='padmin' AND m.permission_version=NEW.published_permission_version) THEN RAISE EXCEPTION 'Runtime publisher unauthorized'; END IF;
  IF EXISTS(SELECT 1 FROM public.mop_configuration WHERE key=NEW.configuration_key AND registry_json->>'contract_value_kind'='fixed_constraint') THEN RAISE EXCEPTION 'Fixed contract constraint'; END IF;
  IF NEW.action<>'DISABLE' THEN
    SELECT * INTO source_record FROM public.mop_configuration_revision WHERE tenant_id=NEW.tenant_id AND id=NEW.revision_id;
    IF NOT FOUND OR source_record.configuration_key<>NEW.configuration_key OR source_record.version<>NEW.revision_version OR source_record.workflow_status<>'APPROVED' OR NEW.value_json IS DISTINCT FROM source_record.proposal->'proposed_value' THEN RAISE EXCEPTION 'Approved source mismatch'; END IF;
  END IF;
  IF NEW.action='ROLLBACK' THEN
    SELECT * INTO target_record FROM public.mop_runtime_configuration_release WHERE tenant_id=NEW.tenant_id AND id=NEW.target_release_id;
    IF NOT FOUND OR target_record.configuration_key<>NEW.configuration_key OR target_record.action='DISABLE' OR target_record.value_json IS DISTINCT FROM NEW.value_json OR target_record.revision_id<>NEW.revision_id OR target_record.revision_version<>NEW.revision_version THEN RAISE EXCEPTION 'Rollback source mismatch'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_runtime_configuration_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_runtime_configuration_release FOR EACH ROW EXECUTE FUNCTION public.mop_runtime_configuration_guard();
REVOKE ALL ON public.mop_runtime_configuration_release FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_runtime_configuration_release TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_runtime_configuration_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_runtime_configuration_guard() TO mop_test_runtime;
