-- Explicit independent test migration. A human-authored recovery assertion is separate from original registration.
-- Never update V13, monitoring/disposition, ticket status, SLA or an original request receipt.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Manual recovery confirmations require dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_manual_recovery_confirmation (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  incident_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version=1),
  note text NOT NULL CHECK(char_length(note) BETWEEN 1 AND 2000),
  note_sha256 char(64) NOT NULL,
  reported_recovered_at timestamptz(3)
    CHECK(reported_recovered_at IS NULL OR reported_recovered_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  manual_recovery_status varchar(24) NOT NULL DEFAULT 'HUMAN_CONFIRMED' CHECK(manual_recovery_status='HUMAN_CONFIRMED'),
  verification_status varchar(24) NOT NULL DEFAULT 'SELF_REPORTED' CHECK(verification_status='SELF_REPORTED'),
  monitor_state varchar(32) CHECK(monitor_state IS NULL),
  disposition_state varchar(32) CHECK(disposition_state IS NULL),
  monitoring_recovery_applicable boolean NOT NULL DEFAULT false CHECK(NOT monitoring_recovery_applicable),
  ticket_state_changed boolean NOT NULL DEFAULT false CHECK(NOT ticket_state_changed),
  sla_changed boolean NOT NULL DEFAULT false CHECK(NOT sla_changed),
  record_source varchar(40) NOT NULL DEFAULT 'MANUAL_RECOVERY_CONFIRMATION' CHECK(record_source='MANUAL_RECOVERY_CONFIRMATION'),
  UNIQUE(tenant_id,id),
  UNIQUE(tenant_id,incident_id),
  FOREIGN KEY(tenant_id,incident_id) REFERENCES public.mop_manual_incident_registration(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(note !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'),
  CHECK(note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'),
  CHECK(note_sha256 ~ '^[0-9a-f]{64}$' AND note_sha256=encode(sha256(convert_to(note,'UTF8')),'hex')),
  CHECK(reported_recovered_at IS NULL OR reported_recovered_at<=recorded_at)
);
REVOKE ALL ON public.mop_manual_recovery_confirmation FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_manual_recovery_confirmation TO mop_test_runtime;
-- One immutable human confirmation per manual registration; correction/retraction requires a separately designed evidence workflow.
-- The application must check current membership before replay and append confirmation/audit/receipt atomically.
