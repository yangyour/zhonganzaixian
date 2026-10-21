-- CANDIDATE ONLY: immutable revision review evidence. No runtime activation or suppression.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Change notice approval requires the dedicated test migration identity';
  END IF;
END $$;
INSERT INTO public.mop_configuration(key,registry_json) VALUES ('change_notice.approver_policy',
'{"key":"change_notice.approver_policy","description":"报备修订审批人；必须为当前有效租户成员且不能审批本人申请","type":"object","scope":"租户","unit":null,"value":null,"decision_status":"pending","source":"D10/Q09","question_ids":["Q09"],"activation_gate":"显式审核发布后仅用于内部报备修订审批，不授予执行或生产批准","notes":"格式为reviewer_user_ids非空UUID数组，无默认角色或审批人","allowed_range":"1至1000个唯一用户UUID","contract_value_kind":"pending_customer_decision","source_registry_section":"5"}'::jsonb);
CREATE TABLE public.mop_change_notice_approval_event (
  tenant_id uuid NOT NULL,
  notice_id uuid NOT NULL,
  draft_revision bigint NOT NULL,
  content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  version bigint NOT NULL CHECK(version BETWEEN 2 AND 3),
  from_state varchar(16) NOT NULL,
  to_state varchar(16) NOT NULL,
  action varchar(16) NOT NULL,
  actor_id uuid NOT NULL,
  permission_version bigint NOT NULL CHECK(permission_version>0),
  review_policy_release_id uuid,
  note varchar(2000),
  occurred_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
  effective boolean NOT NULL DEFAULT false CHECK(NOT effective),
  device_execution_allowed boolean NOT NULL DEFAULT false CHECK(NOT device_execution_allowed),
  alert_suppression_allowed boolean NOT NULL DEFAULT false CHECK(NOT alert_suppression_allowed),
  PRIMARY KEY(tenant_id,notice_id,draft_revision,version),
  FOREIGN KEY(tenant_id,notice_id,draft_revision) REFERENCES public.mop_change_notice_revision(tenant_id,notice_id,version),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,review_policy_release_id) REFERENCES public.mop_runtime_configuration_release(tenant_id,id),
  CHECK((action='SUBMIT' AND from_state='DRAFT' AND to_state='IN_REVIEW' AND version=2)
     OR (action='WITHDRAW' AND ((from_state='DRAFT' AND version=2) OR (from_state='IN_REVIEW' AND version=3)) AND to_state='WITHDRAWN')
     OR (action='APPROVE' AND from_state='IN_REVIEW' AND to_state='APPROVED' AND version=3)
     OR (action='REJECT' AND from_state='IN_REVIEW' AND to_state='REJECTED' AND version=3)),
  CHECK((action IN ('APPROVE','REJECT'))=(review_policy_release_id IS NOT NULL)),
  CHECK(action<>'REJECT' OR note IS NOT NULL),
  CHECK(note IS NULL OR (char_length(note) BETWEEN 1 AND 2000
    AND note !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]')),
  CHECK(occurred_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 15:59:59.999+00')
);
CREATE FUNCTION public.mop_change_notice_approval_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  h public.mop_change_notice_draft%ROWTYPE;
  d public.mop_change_notice_revision%ROWTYPE;
  p public.mop_change_notice_approval_event%ROWTYPE;
  policy public.mop_runtime_configuration_release%ROWTYPE;
  observed timestamptz;
  previous_at timestamptz;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Approval evidence is append-only'; END IF;
  -- Matches repository lock order: membership, draft head, current runtime policy.
  PERFORM 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
    WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.actor_id AND m.active AND u.active
      AND m.permission_version=NEW.permission_version FOR SHARE OF m,u;
  IF NOT FOUND OR NOT ('change_notice_drafts:write'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Approval actor membership or capability is stale';
  END IF;
  SELECT * INTO STRICT h FROM public.mop_change_notice_draft WHERE tenant_id=NEW.tenant_id AND id=NEW.notice_id FOR UPDATE;
  SELECT * INTO STRICT d FROM public.mop_change_notice_revision WHERE tenant_id=NEW.tenant_id AND notice_id=NEW.notice_id AND version=NEW.draft_revision;
  IF h.version<>NEW.draft_revision OR d.archived OR d.content_sha256<>NEW.content_sha256 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Approval must bind exact unarchived draft revision';
  END IF;
  SELECT * INTO p FROM public.mop_change_notice_approval_event
    WHERE tenant_id=NEW.tenant_id AND notice_id=NEW.notice_id AND draft_revision=NEW.draft_revision ORDER BY version DESC LIMIT 1;
  previous_at=d.recorded_at;
  IF FOUND THEN
    previous_at=p.occurred_at;
    IF NEW.version<>p.version+1 OR NEW.from_state<>p.to_state THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Approval version or state chain mismatch';
    END IF;
  ELSIF NEW.version<>2 OR NEW.from_state<>'DRAFT' THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Approval must start from the immutable draft';
  END IF;
  IF NEW.action IN ('SUBMIT','WITHDRAW') THEN
    IF NEW.actor_id<>h.applicant_id THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Only applicant can submit or withdraw'; END IF;
  ELSE
    IF NEW.actor_id=h.applicant_id THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Applicant cannot review own notice'; END IF;
    PERFORM pg_advisory_xact_lock(hashtextextended('mop.runtime.configuration|'||NEW.tenant_id||'|change_notice.approver_policy',0));
    observed=date_trunc('milliseconds',clock_timestamp());
    SELECT * INTO policy FROM public.mop_runtime_configuration_release
      WHERE tenant_id=NEW.tenant_id AND configuration_key='change_notice.approver_policy' AND effective_at<=observed ORDER BY version DESC LIMIT 1;
    IF NOT FOUND OR policy.id<>NEW.review_policy_release_id OR policy.action='DISABLE'
       OR (policy.expires_at IS NOT NULL AND policy.expires_at<=observed) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Current configured Q09 approval policy is required';
    END IF;
    IF jsonb_typeof(policy.value_json)<>'object' OR NOT (policy.value_json ? 'reviewer_user_ids')
       OR (SELECT count(*) FROM jsonb_object_keys(policy.value_json))<>1
       OR jsonb_typeof(policy.value_json->'reviewer_user_ids')<>'array' THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Invalid Q09 review policy';
    END IF;
    IF jsonb_array_length(policy.value_json->'reviewer_user_ids') NOT BETWEEN 1 AND 1000
       OR EXISTS(SELECT 1 FROM jsonb_array_elements(policy.value_json->'reviewer_user_ids') v
         WHERE jsonb_typeof(v)<>'string' OR (v#>>'{}') !~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
       OR (SELECT count(*)<>count(DISTINCT v) FROM jsonb_array_elements(policy.value_json->'reviewer_user_ids') v)
       OR NOT ((policy.value_json->'reviewer_user_ids') @> jsonb_build_array(NEW.actor_id::text)) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Actor is not an explicitly configured Q09 reviewer';
    END IF;
  END IF;
  -- Use the same server instant for current-policy selection and recorded review time.
  -- The instant is sampled after authorization locks; callers cannot backdate it.
  NEW.occurred_at=COALESCE(observed,date_trunc('milliseconds',clock_timestamp()));
  IF NEW.occurred_at<previous_at THEN RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Server approval clock precedes stored evidence'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_change_notice_approval_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_change_notice_approval_event
  FOR EACH ROW EXECUTE FUNCTION public.mop_change_notice_approval_guard();
REVOKE ALL ON public.mop_change_notice_approval_event FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_change_notice_approval_event TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_change_notice_approval_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_change_notice_approval_guard() TO mop_test_runtime;
