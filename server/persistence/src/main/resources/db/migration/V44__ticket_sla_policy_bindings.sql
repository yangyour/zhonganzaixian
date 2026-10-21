-- Candidate explicit migration. Binds a missing Q27 policy without modifying opening evidence.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_ticket_sla_policy_binding (
  tenant_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  lifecycle_version bigint NOT NULL CHECK(lifecycle_version BETWEEN 1 AND 9007199254740990),
  release_id uuid NOT NULL REFERENCES public.mop_runtime_configuration_release(id),
  release_version bigint NOT NULL CHECK(release_version BETWEEN 1 AND 9007199254740990),
  policy_json jsonb NOT NULL CHECK(jsonb_typeof(policy_json)='object' AND octet_length(policy_json::text)<=16384),
  clock_policy varchar(48) NOT NULL CHECK(clock_policy IN ('EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME')),
  actor_id uuid NOT NULL,
  actor_permission_version bigint NOT NULL CHECK(actor_permission_version>0),
  created_at timestamptz(3) NOT NULL,
  production_approved boolean NOT NULL DEFAULT false CHECK(NOT production_approved),
  PRIMARY KEY(tenant_id,ticket_id),
  FOREIGN KEY(tenant_id,ticket_id,lifecycle_version) REFERENCES public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((policy_json->>'clock_policy'=clock_policy) IS TRUE),
  CHECK(created_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE FUNCTION public.mop_ticket_sla_policy_binding_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE current_ticket public.mop_ticket_lifecycle_revision%ROWTYPE; publication public.mop_runtime_configuration_release%ROWTYPE; actor_role text;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'SLA policy bindings are immutable'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.ticket.lifecycle|'||NEW.tenant_id||'|'||NEW.ticket_id,0));
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.runtime.configuration|'||NEW.tenant_id||'|sla.reopen_and_mttr_policy',0));
  SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
    WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.actor_id AND m.active AND u.active AND m.permission_version=NEW.actor_permission_version FOR SHARE OF m,u;
  IF NOT FOUND OR actor_role NOT IN ('msp','padmin') OR ('tickets:lifecycle_write'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) IS NOT TRUE THEN RAISE EXCEPTION 'Current engineer lifecycle capability required'; END IF;
  SELECT * INTO current_ticket FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id ORDER BY version DESC LIMIT 1;
  IF NOT FOUND OR current_ticket.version<>NEW.lifecycle_version THEN RAISE EXCEPTION 'Current lifecycle version required'; END IF;
  IF current_ticket.opening_snapshot#>>'{sla_opening,configuration_inputs,sla.reopen_and_mttr_policy,value,clock_policy}' IN ('EXCLUDE_CONFIRMED_PAUSES','INCLUDE_ALL_ELAPSED_TIME') THEN RAISE EXCEPTION 'Existing frozen opening policy cannot be replaced'; END IF;
  IF NEW.created_at<current_ticket.occurred_at OR NEW.created_at>clock_timestamp() THEN RAISE EXCEPTION 'Binding observation time invalid'; END IF;
  SELECT * INTO publication FROM public.mop_runtime_configuration_release WHERE tenant_id=NEW.tenant_id AND configuration_key='sla.reopen_and_mttr_policy' AND effective_at<=NEW.created_at ORDER BY version DESC LIMIT 1;
  IF NOT FOUND OR publication.id<>NEW.release_id OR publication.version<>NEW.release_version OR publication.action='DISABLE' OR (publication.expires_at IS NOT NULL AND publication.expires_at<=NEW.created_at) OR publication.value_json IS DISTINCT FROM NEW.policy_json THEN RAISE EXCEPTION 'Binding requires current active Q27 publication'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_sla_policy_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_sla_policy_binding FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_sla_policy_binding_guard();
REVOKE ALL ON public.mop_ticket_sla_policy_binding FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_sla_policy_binding TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_sla_policy_binding_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_sla_policy_binding_guard() TO mop_test_runtime;
