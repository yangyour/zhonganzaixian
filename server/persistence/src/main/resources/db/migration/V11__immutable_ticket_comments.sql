-- Explicit independent test migration. Customer-visible comments never convert INTERNAL notes.
-- V1-V10 remain frozen. No parent UPDATE grant, workflow/SLA change, provider call or notification.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Ticket comments require dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_ticket_comment (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  ticket_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  observed_ticket_version bigint NOT NULL CHECK(observed_ticket_version>=1),
  author_kind varchar(16) NOT NULL CHECK(author_kind IN ('REPORTER','ENGINEER')),
  comment_text text NOT NULL CHECK(char_length(comment_text) BETWEEN 1 AND 2000),
  comment_sha256 char(64) NOT NULL,
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  visibility varchar(24) NOT NULL DEFAULT 'REPORTER_AND_INTERNAL' CHECK(visibility='REPORTER_AND_INTERNAL'),
  record_source varchar(32) NOT NULL DEFAULT 'MANUAL_TICKET_COMMENT' CHECK(record_source='MANUAL_TICKET_COMMENT'),
  verification_status varchar(24) NOT NULL DEFAULT 'SELF_REPORTED' CHECK(verification_status='SELF_REPORTED'),
  ticket_state_changed boolean NOT NULL DEFAULT false CHECK(NOT ticket_state_changed),
  sla_changed boolean NOT NULL DEFAULT false CHECK(NOT sla_changed),
  itsm_applied boolean NOT NULL DEFAULT false CHECK(NOT itsm_applied),
  itsm_sync_status varchar(24) NOT NULL DEFAULT 'NOT_REQUESTED' CHECK(itsm_sync_status='NOT_REQUESTED'),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(comment_text !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'),
  CHECK(comment_text !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  CHECK(comment_sha256 ~ '^[0-9a-f]{64}$' AND comment_sha256=encode(sha256(convert_to(comment_text,'UTF8')),'hex'))
);
CREATE INDEX mop_ticket_comment_order ON public.mop_ticket_comment(tenant_id,ticket_id,created_at DESC,id DESC);
REVOKE ALL ON public.mop_ticket_comment FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_comment TO mop_test_runtime;
-- Fresh reporter/role/permission/parent-version authorization is still mandatory in the application transaction.
-- Private actor_id is an audit fact, never a customer DTO field; author_kind is fixed by the authorized writer.
