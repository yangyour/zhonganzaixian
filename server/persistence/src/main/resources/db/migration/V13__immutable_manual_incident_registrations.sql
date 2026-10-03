-- Explicit independent test migration. Immutable manual-source registrations only.
-- No monitoring recovery, event disposition, ticket creation, SLA or notification side effect.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Manual registrations require dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_manual_incident_registration (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  asset_id uuid NOT NULL,
  actor_id uuid NOT NULL,
  observed_asset_version bigint NOT NULL CHECK(observed_asset_version BETWEEN 1 AND 9007199254740991),
  asset_name_at_registration varchar(100) NOT NULL CHECK(char_length(asset_name_at_registration) BETWEEN 1 AND 100),
  title varchar(200) NOT NULL CHECK(char_length(title) BETWEEN 1 AND 200),
  severity varchar(16) NOT NULL CHECK(severity IN ('disaster','high','average','warning','info')),
  registered_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(registered_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  source_state varchar(16) NOT NULL DEFAULT 'MANUAL' CHECK(source_state='MANUAL'),
  registration_status varchar(24) NOT NULL DEFAULT 'REGISTERED_ONLY' CHECK(registration_status='REGISTERED_ONLY'),
  verification_status varchar(24) NOT NULL DEFAULT 'SELF_REPORTED' CHECK(verification_status='SELF_REPORTED'),
  monitor_state varchar(32) CHECK(monitor_state IS NULL),
  disposition_state varchar(32) CHECK(disposition_state IS NULL),
  ticket_priority varchar(8) CHECK(ticket_priority IS NULL),
  observed_at timestamptz(3) CHECK(observed_at IS NULL),
  recovered_at timestamptz(3) CHECK(recovered_at IS NULL),
  manual_recovery_status varchar(24) NOT NULL DEFAULT 'NOT_CONFIRMED' CHECK(manual_recovery_status='NOT_CONFIRMED'),
  ticket_automatically_created boolean NOT NULL DEFAULT false CHECK(NOT ticket_automatically_created),
  record_source varchar(40) NOT NULL DEFAULT 'MANUAL_INCIDENT_REGISTRATION' CHECK(record_source='MANUAL_INCIDENT_REGISTRATION'),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(title !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'),
  CHECK(title !~ U&'[\0001-\001F\007F-\009F]')
);
CREATE INDEX mop_manual_incident_registration_order ON public.mop_manual_incident_registration(tenant_id,registered_at DESC,id DESC);
REVOKE ALL ON public.mop_manual_incident_registration FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_manual_incident_registration TO mop_test_runtime;
-- Future human recovery assertions or ticket bindings must be separate evidence, never overwrite original registration.
-- Fresh internal membership, same-tenant asset and observed-version checks remain mandatory in the application transaction.
