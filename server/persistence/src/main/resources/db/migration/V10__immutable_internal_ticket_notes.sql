-- Explicit independent test migration only. Internal notes are not customer messages or ticket transitions.
-- V1-V9 remain frozen. No parent-ticket UPDATE grant, external notification, attachment or SLA mutation.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Internal ticket notes require dedicated test database and migration role';
  END IF;
END $$;

CREATE TABLE public.mop_ticket_internal_note (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  ticket_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  observed_ticket_version bigint NOT NULL CHECK(observed_ticket_version>=1),
  note text NOT NULL CHECK(char_length(note) BETWEEN 1 AND 2000),
  note_sha256 char(64) NOT NULL,
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  visibility varchar(16) NOT NULL DEFAULT 'INTERNAL' CHECK(visibility='INTERNAL'),
  record_source varchar(32) NOT NULL DEFAULT 'MANUAL_INTERNAL_NOTE' CHECK(record_source='MANUAL_INTERNAL_NOTE'),
  verification_status varchar(24) NOT NULL DEFAULT 'SELF_REPORTED' CHECK(verification_status='SELF_REPORTED'),
  ticket_state_changed boolean NOT NULL DEFAULT false CHECK(NOT ticket_state_changed),
  sla_changed boolean NOT NULL DEFAULT false CHECK(NOT sla_changed),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(note !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'),
  CHECK(note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  CHECK(note_sha256 ~ '^[0-9a-f]{64}$' AND note_sha256=encode(sha256(convert_to(note,'UTF8')),'hex'))
);
CREATE INDEX mop_ticket_internal_note_order ON public.mop_ticket_internal_note(tenant_id,ticket_id,created_at DESC,id DESC);
REVOKE ALL ON public.mop_ticket_internal_note FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_internal_note TO mop_test_runtime;
-- Runtime cannot edit/delete history or expose it via customer DTOs; application must reauthorize current internal membership.
