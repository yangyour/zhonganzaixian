-- Explicit, project-only migration. Tenant service tier snapshot and append-only change history (M2-10/M5-8/M5-20).
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Service tier migration requires dedicated project migrator';
  END IF;
END $$;

CREATE TABLE public.mop_service_tier (
  tenant_id uuid PRIMARY KEY REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1 AND version<=9007199254740990),
  tier varchar(10) NOT NULL CHECK(tier IN ('BASIC','STANDARD','ADVANCED')),
  availability_target numeric(5,2) CHECK(availability_target IS NULL OR (availability_target>0 AND availability_target<=100)),
  contract_device_count integer CHECK(contract_device_count IS NULL OR (contract_device_count>=0 AND contract_device_count<=1000000)),
  billing_cycle varchar(16) CHECK(billing_cycle IS NULL OR billing_cycle IN ('MONTHLY','QUARTERLY','YEARLY')),
  monthly_fee_fen bigint CHECK(monthly_fee_fen IS NULL OR (monthly_fee_fen>=0 AND monthly_fee_fen<=9007199254740990)),
  effective_mode varchar(24) NOT NULL CHECK(effective_mode IN ('IMMEDIATE','NEXT_MONTH_FIRST_DAY')),
  effective_from timestamptz(3) NOT NULL,
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE TABLE public.mop_service_tier_change (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1 AND version<=9007199254740990),
  previous_version bigint CHECK((version=1 AND previous_version IS NULL) OR (version>1 AND previous_version=version-1)),
  from_tier varchar(10) CHECK((version=1 AND from_tier IS NULL) OR (version>1 AND from_tier IN ('BASIC','STANDARD','ADVANCED'))),
  to_tier varchar(10) NOT NULL CHECK(to_tier IN ('BASIC','STANDARD','ADVANCED')),
  availability_target numeric(5,2) CHECK(availability_target IS NULL OR (availability_target>0 AND availability_target<=100)),
  contract_device_count integer CHECK(contract_device_count IS NULL OR (contract_device_count>=0 AND contract_device_count<=1000000)),
  billing_cycle varchar(16) CHECK(billing_cycle IS NULL OR billing_cycle IN ('MONTHLY','QUARTERLY','YEARLY')),
  monthly_fee_fen bigint CHECK(monthly_fee_fen IS NULL OR (monthly_fee_fen>=0 AND monthly_fee_fen<=9007199254740990)),
  effective_mode varchar(24) NOT NULL CHECK(effective_mode IN ('IMMEDIATE','NEXT_MONTH_FIRST_DAY')),
  effective_from timestamptz(3) NOT NULL,
  reason varchar(1000) NOT NULL CHECK(length(btrim(reason))>0),
  approved_by uuid NOT NULL,
  changed_at timestamptz(3) NOT NULL,
  customer_approval_status varchar(16) NOT NULL CHECK(customer_approval_status='UNVERIFIED'),
  UNIQUE(tenant_id,version), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,approved_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,previous_version) REFERENCES public.mop_service_tier_change(tenant_id,version)
);
CREATE INDEX mop_service_tier_change_recent ON public.mop_service_tier_change(tenant_id,version DESC);

CREATE FUNCTION public.mop_service_tier_head_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Service tier head deletion is forbidden'; END IF;
  IF TG_OP='INSERT' AND NEW.version<>1 THEN RAISE EXCEPTION 'Service tier head must start at version 1'; END IF;
  IF TG_OP='UPDATE' AND (NEW.tenant_id<>OLD.tenant_id OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
    RAISE EXCEPTION 'Service tier head must advance monotonically';
  END IF;
  IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Service tier timestamp cannot be in the future'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_service_tier_head_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_service_tier FOR EACH ROW EXECUTE FUNCTION public.mop_service_tier_head_guard();

CREATE FUNCTION public.mop_service_tier_change_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE prev_tier text; prev_at timestamptz; role text;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Service tier change history is immutable'; END IF;
  IF NEW.changed_at>clock_timestamp() THEN RAISE EXCEPTION 'Service tier change timestamp cannot be in the future'; END IF;
  SELECT m.role_code INTO role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
    WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.approved_by AND m.active AND u.active;
  IF role IS DISTINCT FROM 'padmin' THEN RAISE EXCEPTION 'Service tier change requires active platform admin membership'; END IF;
  IF NEW.version>1 THEN
    SELECT to_tier,changed_at INTO prev_tier,prev_at FROM public.mop_service_tier_change
      WHERE tenant_id=NEW.tenant_id AND version=NEW.previous_version;
    IF NOT FOUND OR prev_tier<>NEW.from_tier OR NEW.changed_at<prev_at THEN
      RAISE EXCEPTION 'Service tier change lineage mismatch';
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_service_tier_change_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_service_tier_change FOR EACH ROW EXECUTE FUNCTION public.mop_service_tier_change_guard();

CREATE FUNCTION public.mop_service_tier_sync_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE h public.mop_service_tier%ROWTYPE; c public.mop_service_tier_change%ROWTYPE;
BEGIN
  SELECT * INTO h FROM public.mop_service_tier WHERE tenant_id=NEW.tenant_id AND xmin::text=pg_current_xact_id()::text;
  SELECT * INTO c FROM public.mop_service_tier_change WHERE tenant_id=NEW.tenant_id AND version=NEW.version AND xmin::text=pg_current_xact_id()::text;
  IF h.tenant_id IS NULL OR c.id IS NULL OR h.version<>c.version
    OR (h.tier,h.availability_target,h.contract_device_count,h.billing_cycle,h.monthly_fee_fen,h.effective_mode,h.effective_from,h.updated_by,h.updated_at)
       IS DISTINCT FROM (c.to_tier,c.availability_target,c.contract_device_count,c.billing_cycle,c.monthly_fee_fen,c.effective_mode,c.effective_from,c.approved_by,c.changed_at) THEN
    RAISE EXCEPTION 'Service tier head and change history must commit together';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER mop_service_tier_head_sync AFTER INSERT OR UPDATE ON public.mop_service_tier DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_service_tier_sync_guard();
CREATE CONSTRAINT TRIGGER mop_service_tier_change_sync AFTER INSERT ON public.mop_service_tier_change DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_service_tier_sync_guard();

REVOKE ALL ON public.mop_service_tier,public.mop_service_tier_change FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_service_tier,public.mop_service_tier_change TO mop_test_runtime;
GRANT UPDATE(version,tier,availability_target,contract_device_count,billing_cycle,monthly_fee_fen,effective_mode,effective_from,updated_by,updated_at) ON public.mop_service_tier TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_service_tier_head_guard(),public.mop_service_tier_change_guard(),public.mop_service_tier_sync_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_service_tier_head_guard(),public.mop_service_tier_change_guard(),public.mop_service_tier_sync_guard() TO mop_test_runtime;
