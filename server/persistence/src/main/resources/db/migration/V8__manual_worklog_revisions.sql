-- Explicit independent test migration only. No API/worker automatic migration or cost calculation.
-- Manual time facts do not assign default hours, change ticket/SLA state or approve profitability.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Manual worklogs require dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_worklog (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  ticket_id uuid NOT NULL, owner_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version>=1),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,id,owner_id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES public.mop_membership(tenant_id,user_id)
);
CREATE TABLE public.mop_worklog_revision (
  tenant_id uuid NOT NULL, worklog_id uuid NOT NULL, version bigint NOT NULL CHECK(version>=1),
  actor_id uuid NOT NULL, recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE')),
  kind varchar(16) NOT NULL CHECK(kind IN ('REMOTE','ONSITE','INSPECTION','COMMUTE')),
  started_at timestamptz(3) NOT NULL, ended_at timestamptz(3) NOT NULL, note varchar(2000),
  record_source varchar(32) NOT NULL DEFAULT 'MANUAL_INTERVAL' CHECK(record_source='MANUAL_INTERVAL'),
  PRIMARY KEY(tenant_id,worklog_id,version),
  FOREIGN KEY(tenant_id,worklog_id,actor_id) REFERENCES public.mop_worklog(tenant_id,id,owner_id),
  CHECK(ended_at>=started_at),
  CHECK(started_at>=TIMESTAMPTZ '0001-01-01 00:00:00+00' AND ended_at<=TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK((version=1 AND action='CREATE') OR (version>1 AND action='UPDATE'))
);
ALTER TABLE public.mop_worklog ADD FOREIGN KEY(tenant_id,id,version)
  REFERENCES public.mop_worklog_revision(tenant_id,worklog_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_worklog_ticket_owner_order ON public.mop_worklog(tenant_id,ticket_id,owner_id,created_at,id);

REVOKE ALL ON public.mop_worklog,public.mop_worklog_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_worklog,public.mop_worklog_revision TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_worklog TO mop_test_runtime;
-- Parent ticket, owner, original creation time and historical facts cannot be changed by runtime.
