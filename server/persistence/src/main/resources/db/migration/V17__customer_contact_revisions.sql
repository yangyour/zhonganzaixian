-- Independently applied contact facts only; existing tenants are not populated with invented people.
-- Frozen V1-V16 are unchanged. No notification, identity grant, permanent delete or automatic migration.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Contact revisions require dedicated test database and migration role';
  END IF;
END $$;
CREATE TABLE public.mop_contact_tenant_guard (
  tenant_id uuid PRIMARY KEY REFERENCES public.mop_tenant(id),
  version bigint NOT NULL DEFAULT 0 CHECK(version>=0)
);
CREATE TABLE public.mop_customer_contact (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  created_by uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740991),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_customer_contact_revision (
  tenant_id uuid NOT NULL,
  contact_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740991),
  previous_version bigint,
  recorded_by uuid NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp() CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE','ARCHIVE','RESTORE')),
  name varchar(200) NOT NULL CHECK(char_length(name) BETWEEN 1 AND 200
    AND name !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND name !~ U&'[\0001-\001F\007F-\009F]'),
  roles text[] NOT NULL CHECK(array_ndims(roles)=1 AND array_lower(roles,1)=1 AND cardinality(roles) BETWEEN 1 AND 3
    AND array_position(roles,NULL) IS NULL AND roles <@ ARRAY['REPORTER','DECISION_MAKER','TECHNICAL_CONTACT']
    AND cardinality(roles)=(('REPORTER'=ANY(roles))::int+('DECISION_MAKER'=ANY(roles))::int+('TECHNICAL_CONTACT'=ANY(roles))::int)),
  mobile varchar(16) CHECK(mobile ~ '^\+[1-9][0-9]{1,14}$'),
  email varchar(254) CHECK(email ~ $email$^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*@[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)+$$email$ AND char_length(split_part(email,'@',1))<=64),
  wecom_user_id varchar(256) CHECK(char_length(wecom_user_id) BETWEEN 1 AND 256
    AND wecom_user_id !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND wecom_user_id !~ U&'[\0001-\001F\007F-\009F]'),
  preferred_priorities text[] CHECK(cardinality(preferred_priorities)=0 OR (array_ndims(preferred_priorities)=1 AND array_lower(preferred_priorities,1)=1
    AND cardinality(preferred_priorities) BETWEEN 1 AND 4 AND array_position(preferred_priorities,NULL) IS NULL
    AND preferred_priorities <@ ARRAY['P1','P2','P3','P4']
    AND cardinality(preferred_priorities)=(('P1'=ANY(preferred_priorities))::int+('P2'=ANY(preferred_priorities))::int+('P3'=ANY(preferred_priorities))::int+('P4'=ANY(preferred_priorities))::int))),
  quiet_declared boolean NOT NULL DEFAULT false,
  quiet_enabled boolean,
  quiet_start varchar(5),
  quiet_end varchar(5),
  enabled boolean NOT NULL,
  archived boolean NOT NULL DEFAULT false,
  record_source varchar(40) NOT NULL DEFAULT 'SELF_REPORTED_CONTACT' CHECK(record_source='SELF_REPORTED_CONTACT'),
  PRIMARY KEY(tenant_id,contact_id,version),
  FOREIGN KEY(tenant_id,contact_id) REFERENCES public.mop_customer_contact(tenant_id,id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,contact_id,previous_version) REFERENCES public.mop_customer_contact_revision(tenant_id,contact_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE' AND NOT archived)
    OR (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action<>'CREATE')),
  CHECK(NOT archived OR NOT enabled),
  CHECK((action='ARCHIVE' AND archived) OR (action IN ('CREATE','UPDATE','RESTORE') AND NOT archived)),
  CHECK(quiet_declared OR (quiet_enabled IS NULL AND quiet_start IS NULL AND quiet_end IS NULL)),
  CHECK((quiet_enabled IS NOT TRUE AND quiet_start IS NULL AND quiet_end IS NULL)
    OR (quiet_enabled IS TRUE AND quiet_start IS NOT NULL AND quiet_end IS NOT NULL
      AND quiet_start ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND quiet_end ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND quiet_start<>quiet_end))
);
ALTER TABLE public.mop_customer_contact ADD CONSTRAINT mop_contact_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_customer_contact_revision(tenant_id,contact_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_customer_contact_order ON public.mop_customer_contact(tenant_id,created_at,id);

-- A common tenant row serializes all contact changes. Updating the epoch also forces stale
-- repeatable-read transactions to fail instead of accepting mutually inconsistent snapshots.
CREATE FUNCTION public.mop_contact_decision_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
BEGIN
  INSERT INTO public.mop_contact_tenant_guard(tenant_id,version) VALUES (NEW.tenant_id,0) ON CONFLICT(tenant_id) DO NOTHING;
  UPDATE public.mop_contact_tenant_guard SET version=version+1 WHERE tenant_id=NEW.tenant_id;
  IF NOT EXISTS (
    SELECT 1 FROM public.mop_customer_contact h JOIN public.mop_customer_contact_revision r
      ON r.tenant_id=h.tenant_id AND r.contact_id=h.id AND r.version=h.version
    WHERE h.tenant_id=NEW.tenant_id AND NOT r.archived AND r.enabled AND 'DECISION_MAKER'=ANY(r.roles)
  ) THEN
    RAISE EXCEPTION USING ERRCODE='23514',CONSTRAINT='mop_contact_enabled_decision_required',MESSAGE='At least one enabled decision maker must remain in this tenant';
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_contact_decision_guard_trigger
  AFTER INSERT OR UPDATE ON public.mop_customer_contact DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION public.mop_contact_decision_guard();
REVOKE ALL ON FUNCTION public.mop_contact_decision_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_contact_decision_guard() TO mop_test_runtime;
REVOKE ALL ON public.mop_contact_tenant_guard,public.mop_customer_contact,public.mop_customer_contact_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_contact_tenant_guard,public.mop_customer_contact,public.mop_customer_contact_revision TO mop_test_runtime;
GRANT UPDATE(version) ON public.mop_contact_tenant_guard TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_customer_contact TO mop_test_runtime;
-- Runtime has no UPDATE of historical contact facts, DELETE, TRUNCATE, ownership or grant rights.
