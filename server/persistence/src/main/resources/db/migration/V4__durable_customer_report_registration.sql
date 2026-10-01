-- MOP is the selected phase-one ticket authority; registration is not a configured business workflow.
DO $$ BEGIN IF current_database() <> 'mop_test' THEN RAISE EXCEPTION 'MOP test migrations require dedicated mop_test database'; END IF; END $$;

CREATE TABLE public.mop_ticket (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  receipt_no varchar(40) NOT NULL UNIQUE,
  title varchar(200) NOT NULL, description varchar(3000) NOT NULL,
  source varchar(32) NOT NULL CHECK(source='customer_report'),
  category varchar(64) NOT NULL CHECK(category IN ('网络不通','网速慢','WiFi问题','设备异常','其他')),
  impact_scope varchar(32) NOT NULL CHECK(impact_scope IN ('company','partial_area','individual')),
  urgency varchar(32) NOT NULL CHECK(urgency IN ('normal','urgent')),
  asset_id uuid, asset_name varchar(100), reporter_id uuid NOT NULL,
  contact_name varchar(100) NOT NULL, contact_phone varchar(64) NOT NULL,
  contact_binding_status varchar(32) NOT NULL DEFAULT 'UNVERIFIED' CHECK(contact_binding_status='UNVERIFIED'),
  registration_status varchar(32) NOT NULL DEFAULT 'REGISTERED' CHECK(registration_status='REGISTERED'),
  state varchar(32) CHECK(state IS NULL),
  external_id text CHECK(external_id IS NULL), external_state text CHECK(external_state IS NULL),
  sync_state varchar(32) NOT NULL DEFAULT 'NOT_APPLICABLE' CHECK(sync_state='NOT_APPLICABLE'),
  authority_source varchar(32) NOT NULL DEFAULT 'MOP' CHECK(authority_source='MOP'),
  authority_status varchar(32) NOT NULL DEFAULT 'RULES_PENDING' CHECK(authority_status='RULES_PENDING'),
  priority varchar(32) CHECK(priority IS NULL), response_due_at timestamptz(3) CHECK(response_due_at IS NULL),
  recovery_due_at timestamptz(3) CHECK(recovery_due_at IS NULL),
  operation_id uuid NOT NULL UNIQUE, version bigint NOT NULL DEFAULT 1 CHECK(version=1),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,reporter_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  CHECK((asset_id IS NULL AND asset_name IS NULL) OR (asset_id IS NOT NULL AND asset_name IS NOT NULL))
);
CREATE INDEX mop_ticket_tenant_order ON public.mop_ticket(tenant_id,created_at,id);
CREATE INDEX mop_ticket_reporter_order ON public.mop_ticket(tenant_id,reporter_id,created_at,id);

CREATE TABLE public.mop_ticket_history (
  tenant_id uuid NOT NULL, ticket_id uuid NOT NULL, version bigint NOT NULL CHECK(version=1),
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  PRIMARY KEY(tenant_id,ticket_id,version), FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)
);

CREATE TABLE public.mop_operation (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, ticket_id uuid NOT NULL,
  kind varchar(64) NOT NULL CHECK(kind='CUSTOMER_REPORT_REGISTRATION'),
  status varchar(32) NOT NULL DEFAULT 'PENDING' CHECK(status='PENDING'),
  step varchar(64) NOT NULL DEFAULT 'AWAITING_TICKET_CONFIGURATION' CHECK(step='AWAITING_TICKET_CONFIGURATION'),
  progress numeric CHECK(progress IS NULL), result_ref varchar(255) NOT NULL,
  error jsonb NOT NULL CHECK(jsonb_typeof(error)='object'),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,ticket_id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)
);
ALTER TABLE public.mop_ticket ADD CONSTRAINT mop_ticket_operation_fk FOREIGN KEY(tenant_id,operation_id) REFERENCES public.mop_operation(tenant_id,id) DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE public.mop_outbox (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, ticket_id uuid NOT NULL, operation_id uuid NOT NULL,
  topic varchar(128) NOT NULL, payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING' CHECK(status='CONFIG_PENDING'),
  blocked_question_ids jsonb NOT NULL CHECK(jsonb_typeof(blocked_question_ids)='array'),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(), next_at timestamptz(3) CHECK(next_at IS NULL),
  UNIQUE(tenant_id,ticket_id), UNIQUE(tenant_id,operation_id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,operation_id) REFERENCES public.mop_operation(tenant_id,id)
);

REVOKE ALL ON public.mop_ticket,public.mop_ticket_history,public.mop_operation,public.mop_outbox FROM PUBLIC,mop_test_runtime;
-- All four registration records are append-only for the runtime; no client or worker mutation route exists.
GRANT SELECT,INSERT ON public.mop_ticket,public.mop_ticket_history,public.mop_operation,public.mop_outbox TO mop_test_runtime;
