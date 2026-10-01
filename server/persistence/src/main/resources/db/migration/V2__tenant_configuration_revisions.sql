-- Independent tenant proposals only. Internal approval is not customer confirmation or activation.
DO $$ BEGIN IF current_database() <> 'mop_test' THEN RAISE EXCEPTION 'MOP test migrations require dedicated mop_test database'; END IF; END $$;

CREATE TABLE public.mop_configuration_revision (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  configuration_key varchar(128) NOT NULL REFERENCES public.mop_configuration(key),
  proposal jsonb NOT NULL CHECK (jsonb_typeof(proposal)='object'),
  workflow_status varchar(32) NOT NULL DEFAULT 'DRAFT' CHECK (workflow_status IN ('DRAFT','IN_REVIEW','APPROVED','REJECTED','WITHDRAWN')),
  version bigint NOT NULL DEFAULT 1 CHECK (version>=1),
  created_by uuid NOT NULL,
  created_at timestamptz(3) NOT NULL DEFAULT clock_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT clock_timestamp(),
  reviewed_by uuid,
  reviewed_at timestamptz(3),
  review_comment varchar(500),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,reviewed_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK (reviewed_by IS NULL OR reviewed_by<>created_by),
  CHECK ((workflow_status IN ('APPROVED','REJECTED') AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL AND review_comment IS NOT NULL)
    OR (workflow_status NOT IN ('APPROVED','REJECTED') AND reviewed_by IS NULL AND reviewed_at IS NULL AND review_comment IS NULL))
);
CREATE INDEX mop_configuration_revision_tenant_key ON public.mop_configuration_revision(tenant_id,configuration_key,created_at DESC,id DESC);

CREATE TABLE public.mop_configuration_revision_history (
  tenant_id uuid NOT NULL,
  revision_id uuid NOT NULL,
  revision_version bigint NOT NULL CHECK (revision_version>=1),
  payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
  PRIMARY KEY(tenant_id,revision_id,revision_version),
  FOREIGN KEY(tenant_id,revision_id) REFERENCES public.mop_configuration_revision(tenant_id,id)
);

REVOKE ALL ON public.mop_configuration_revision,public.mop_configuration_revision_history FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_configuration_revision,public.mop_configuration_revision_history TO mop_test_runtime;
-- Tenant, configuration key, proposal, creator and creation time are immutable to the runtime role.
GRANT UPDATE(workflow_status,version,updated_at,reviewed_by,reviewed_at,review_comment) ON public.mop_configuration_revision TO mop_test_runtime;
