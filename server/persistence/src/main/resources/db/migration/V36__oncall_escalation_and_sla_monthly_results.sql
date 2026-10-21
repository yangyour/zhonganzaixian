-- Explicit, project-only migration. On-call roster, append-only handoffs, escalation policies/runs and SLA monthly results (M5-9/M5-10/M5-12/M5-13/M5-19).
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'On-call escalation and SLA monthly migration requires dedicated project migrator';
  END IF;
END $$;

CREATE TABLE public.mop_oncall_roster (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1 AND version<=9007199254740990),
  shift_date date NOT NULL,
  shift_code varchar(16) NOT NULL CHECK(shift_code IN ('DAY','NIGHT','FULL_DAY')),
  handoff_local_time char(5) NOT NULL CHECK(handoff_local_time='18:00'),
  primary_oncall_user_id uuid NOT NULL,
  backup_oncall_user_id uuid CHECK(backup_oncall_user_id IS NULL OR backup_oncall_user_id<>primary_oncall_user_id),
  note varchar(500) CHECK(note IS NULL OR length(btrim(note))>0),
  roster_policy_status varchar(24) NOT NULL CHECK(roster_policy_status='CONFIG_PENDING_Q15'),
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,shift_date,shift_code), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,primary_oncall_user_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,backup_oncall_user_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_oncall_roster_recent ON public.mop_oncall_roster(tenant_id,shift_date DESC,shift_code);

CREATE TABLE public.mop_oncall_handoff (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  roster_id uuid,
  outgoing_user_id uuid NOT NULL,
  incoming_user_id uuid NOT NULL CHECK(incoming_user_id<>outgoing_user_id),
  handoff_local_time char(5) NOT NULL CHECK(handoff_local_time='18:00'),
  unacknowledged_count integer NOT NULL CHECK(unacknowledged_count>=0 AND unacknowledged_count<=1000000),
  in_progress_count integer NOT NULL CHECK(in_progress_count>=0 AND in_progress_count<=1000000),
  pending_review_count integer NOT NULL CHECK(pending_review_count>=0 AND pending_review_count<=1000000),
  note varchar(1000) NOT NULL CHECK(length(btrim(note))>0),
  handed_off_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,roster_id) REFERENCES public.mop_oncall_roster(tenant_id,id),
  FOREIGN KEY(tenant_id,outgoing_user_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,incoming_user_id) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_oncall_handoff_recent ON public.mop_oncall_handoff(tenant_id,handed_off_at DESC,id DESC);

CREATE TABLE public.mop_escalation_policy (
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  tier varchar(16) NOT NULL CHECK(tier IN ('BASIC','STANDARD','ADVANCED','FALLBACK')),
  version bigint NOT NULL CHECK(version>=1 AND version<=9007199254740990),
  unanswered_escalate_count integer NOT NULL CHECK(unanswered_escalate_count>=1 AND unanswered_escalate_count<=10),
  unanswered_retry_interval_sec integer CHECK(unanswered_retry_interval_sec IS NULL),
  steps_json jsonb NOT NULL CHECK(jsonb_typeof(steps_json)='array' AND jsonb_array_length(steps_json)>=1 AND jsonb_array_length(steps_json)<=8),
  policy_status varchar(24) NOT NULL CHECK(policy_status='CONFIG_PENDING_Q15'),
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  PRIMARY KEY(tenant_id,tier),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE TABLE public.mop_escalation_step_run (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  ticket_id uuid NOT NULL,
  tier varchar(16) NOT NULL CHECK(tier IN ('BASIC','STANDARD','ADVANCED','FALLBACK')),
  policy_version bigint NOT NULL CHECK(policy_version>=1 AND policy_version<=9007199254740990),
  step_order integer NOT NULL CHECK(step_order>=1 AND step_order<=8),
  trigger_condition varchar(24) NOT NULL CHECK(trigger_condition IN ('IMMEDIATE','UNACKNOWLEDGED','UNRESOLVED','COUNTDOWN_ZERO','PHONE_UNANSWERED')),
  wait_minutes integer NOT NULL CHECK(wait_minutes>=0 AND wait_minutes<=10080),
  target_role varchar(32) NOT NULL CHECK(target_role IN ('L1_ONCALL','L2_ONCALL','DUTY_LEAD','CUSTOMER_ADMIN','SERVICE_MANAGER','DECISION_MAKER')),
  unanswered_phone_count integer NOT NULL CHECK(unanswered_phone_count>=0 AND unanswered_phone_count<=10),
  escalated_to_step integer CHECK(escalated_to_step IS NULL OR (escalated_to_step=step_order+1 AND escalated_to_step<=8)),
  escalated_target_role varchar(32) CHECK((escalated_to_step IS NULL AND escalated_target_role IS NULL) OR (escalated_to_step IS NOT NULL AND escalated_target_role IN ('L1_ONCALL','L2_ONCALL','DUTY_LEAD','CUSTOMER_ADMIN','SERVICE_MANAGER','DECISION_MAKER'))),
  sms_notice_status varchar(32) NOT NULL CHECK(sms_notice_status IN ('NOT_REQUIRED','INTENT_RECORDED_PENDING_GATEWAY')),
  sms_notice_summary varchar(500) CHECK((sms_notice_status='NOT_REQUIRED' AND sms_notice_summary IS NULL) OR (sms_notice_status='INTENT_RECORDED_PENDING_GATEWAY' AND sms_notice_summary IS NOT NULL AND length(btrim(sms_notice_summary))>0)),
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_escalation_step_run_recent ON public.mop_escalation_step_run(tenant_id,recorded_at DESC,id DESC);

CREATE TABLE public.mop_sla_monthly_result (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  report_month char(7) NOT NULL CHECK(report_month ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
  version bigint NOT NULL CHECK(version>=1 AND version<=9007199254740990),
  tier_snapshot varchar(10) NOT NULL CHECK(tier_snapshot IN ('BASIC','STANDARD','ADVANCED')),
  total_ticket_count integer NOT NULL CHECK(total_ticket_count>=0 AND total_ticket_count<=10000),
  applicable_count integer NOT NULL CHECK(applicable_count>=0 AND applicable_count<=total_ticket_count),
  met_count integer NOT NULL CHECK(met_count>=0 AND met_count<=applicable_count),
  not_met_count integer NOT NULL CHECK(not_met_count>=0 AND not_met_count<=applicable_count),
  unresolved_count integer NOT NULL CHECK(unresolved_count>=0 AND unresolved_count<=total_ticket_count),
  not_applicable_count integer NOT NULL CHECK(not_applicable_count>=0 AND not_applicable_count<=total_ticket_count AND applicable_count+not_applicable_count<=total_ticket_count AND met_count+not_met_count<=applicable_count),
  achievement_numerator integer CHECK((applicable_count=0 AND achievement_numerator IS NULL) OR (applicable_count>0 AND achievement_numerator=met_count)),
  achievement_denominator integer CHECK((applicable_count=0 AND achievement_denominator IS NULL) OR (applicable_count>0 AND achievement_denominator=applicable_count)),
  compensation_ratio numeric(4,2) NOT NULL CHECK(compensation_ratio IN (0.10,0.20,0.30)),
  monthly_fee_fen bigint CHECK(monthly_fee_fen IS NULL OR (monthly_fee_fen>=0 AND monthly_fee_fen<=9007199254740990)),
  compensation_amount_fen bigint CHECK(compensation_amount_fen IS NULL),
  compensation_status varchar(24) NOT NULL CHECK(compensation_status='CONFIG_PENDING_Q26'),
  formula_version varchar(48) NOT NULL CHECK(length(btrim(formula_version))>0),
  items_json jsonb NOT NULL CHECK(jsonb_typeof(items_json)='array'),
  frozen boolean NOT NULL DEFAULT false,
  frozen_at timestamptz(3) CHECK((NOT frozen AND frozen_at IS NULL) OR (frozen AND frozen_at IS NOT NULL)),
  evaluated_by uuid NOT NULL,
  evaluated_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,report_month), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,evaluated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE INDEX mop_sla_monthly_result_recent ON public.mop_sla_monthly_result(tenant_id,report_month DESC);

CREATE FUNCTION public.mop_oncall_roster_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor_role text; primary_role text; backup_role text;
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'On-call roster deletion is forbidden'; END IF;
  IF TG_OP='INSERT' AND NEW.version<>1 THEN RAISE EXCEPTION 'On-call roster must start at version 1'; END IF;
  IF TG_OP='UPDATE' AND (NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.shift_date<>OLD.shift_date OR NEW.shift_code<>OLD.shift_code OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
    RAISE EXCEPTION 'On-call roster must advance monotonically';
  END IF;
  IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'On-call roster timestamp cannot be in the future'; END IF;
  SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.updated_by AND m.active AND u.active;
  SELECT m.role_code INTO primary_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.primary_oncall_user_id AND m.active AND u.active;
  IF actor_role NOT IN ('msp','padmin') OR primary_role NOT IN ('msp','padmin') THEN
    RAISE EXCEPTION 'On-call roster requires active internal memberships';
  END IF;
  IF NEW.backup_oncall_user_id IS NOT NULL THEN
    SELECT m.role_code INTO backup_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.backup_oncall_user_id AND m.active AND u.active;
    IF backup_role NOT IN ('msp','padmin') THEN RAISE EXCEPTION 'Backup on-call user must be an active internal member'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_oncall_roster_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_oncall_roster FOR EACH ROW EXECUTE FUNCTION public.mop_oncall_roster_guard();

CREATE FUNCTION public.mop_oncall_handoff_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE out_role text; in_role text;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'On-call handoff ledger is immutable'; END IF;
  IF NEW.handed_off_at>clock_timestamp() THEN RAISE EXCEPTION 'On-call handoff timestamp cannot be in the future'; END IF;
  SELECT m.role_code INTO out_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.outgoing_user_id AND m.active AND u.active;
  SELECT m.role_code INTO in_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.incoming_user_id AND m.active AND u.active;
  IF out_role NOT IN ('msp','padmin') OR in_role NOT IN ('msp','padmin') THEN
    RAISE EXCEPTION 'On-call handoff requires active internal memberships';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_oncall_handoff_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_oncall_handoff FOR EACH ROW EXECUTE FUNCTION public.mop_oncall_handoff_guard();

CREATE FUNCTION public.mop_escalation_policy_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor_role text;
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Escalation policy deletion is forbidden'; END IF;
  IF TG_OP='INSERT' AND NEW.version<>1 THEN RAISE EXCEPTION 'Escalation policy must start at version 1'; END IF;
  IF TG_OP='UPDATE' AND (NEW.tenant_id<>OLD.tenant_id OR NEW.tier<>OLD.tier OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at) THEN
    RAISE EXCEPTION 'Escalation policy must advance monotonically';
  END IF;
  IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Escalation policy timestamp cannot be in the future'; END IF;
  SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.updated_by AND m.active AND u.active;
  IF actor_role NOT IN ('msp','padmin') THEN RAISE EXCEPTION 'Escalation policy requires active internal membership'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_escalation_policy_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_escalation_policy FOR EACH ROW EXECUTE FUNCTION public.mop_escalation_policy_guard();

CREATE FUNCTION public.mop_escalation_step_run_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor_role text;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Escalation step run history is immutable'; END IF;
  IF NEW.recorded_at>clock_timestamp() THEN RAISE EXCEPTION 'Escalation step run timestamp cannot be in the future'; END IF;
  SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.recorded_by AND m.active AND u.active;
  IF actor_role NOT IN ('msp','padmin') THEN RAISE EXCEPTION 'Escalation step run requires active internal membership'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_escalation_step_run_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_escalation_step_run FOR EACH ROW EXECUTE FUNCTION public.mop_escalation_step_run_guard();

CREATE FUNCTION public.mop_sla_monthly_result_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE actor_role text;
BEGIN
  IF TG_OP='DELETE' THEN RAISE EXCEPTION 'SLA monthly result deletion is forbidden'; END IF;
  IF TG_OP='INSERT' AND NEW.version<>1 THEN RAISE EXCEPTION 'SLA monthly result must start at version 1'; END IF;
  IF TG_OP='UPDATE' THEN
    IF OLD.frozen THEN RAISE EXCEPTION 'Frozen SLA monthly result is immutable'; END IF;
    IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.report_month<>OLD.report_month OR NEW.version<>OLD.version+1 OR NEW.evaluated_at<OLD.evaluated_at THEN
      RAISE EXCEPTION 'SLA monthly result must advance monotonically';
    END IF;
  END IF;
  IF NEW.evaluated_at>clock_timestamp() OR (NEW.frozen_at IS NOT NULL AND NEW.frozen_at>clock_timestamp()) THEN
    RAISE EXCEPTION 'SLA monthly result timestamp cannot be in the future';
  END IF;
  SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.evaluated_by AND m.active AND u.active;
  IF actor_role NOT IN ('msp','padmin') THEN RAISE EXCEPTION 'SLA monthly result requires active internal membership'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_sla_monthly_result_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_sla_monthly_result FOR EACH ROW EXECUTE FUNCTION public.mop_sla_monthly_result_guard();

REVOKE ALL ON public.mop_oncall_roster,public.mop_oncall_handoff,public.mop_escalation_policy,public.mop_escalation_step_run,public.mop_sla_monthly_result FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_oncall_roster,public.mop_oncall_handoff,public.mop_escalation_policy,public.mop_escalation_step_run,public.mop_sla_monthly_result TO mop_test_runtime;
GRANT UPDATE(version,primary_oncall_user_id,backup_oncall_user_id,note,updated_by,updated_at) ON public.mop_oncall_roster TO mop_test_runtime;
GRANT UPDATE(version,unanswered_escalate_count,steps_json,updated_by,updated_at) ON public.mop_escalation_policy TO mop_test_runtime;
GRANT UPDATE(version,tier_snapshot,total_ticket_count,applicable_count,met_count,not_met_count,unresolved_count,not_applicable_count,achievement_numerator,achievement_denominator,compensation_ratio,monthly_fee_fen,formula_version,items_json,frozen,frozen_at,evaluated_by,evaluated_at) ON public.mop_sla_monthly_result TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_oncall_roster_guard(),public.mop_oncall_handoff_guard(),public.mop_escalation_policy_guard(),public.mop_escalation_step_run_guard(),public.mop_sla_monthly_result_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_oncall_roster_guard(),public.mop_oncall_handoff_guard(),public.mop_escalation_policy_guard(),public.mop_escalation_step_run_guard(),public.mop_sla_monthly_result_guard() TO mop_test_runtime;
