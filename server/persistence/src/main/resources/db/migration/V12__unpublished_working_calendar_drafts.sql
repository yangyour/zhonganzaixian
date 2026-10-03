-- Independent explicit test migration. V1-V11 remain frozen; no effective calendar or SLA state is written.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Working calendar drafts require dedicated test database and migration role';
  END IF;
END $$;

-- Input-only validation, fixed UTC serialization and no table reads or SECURITY DEFINER privileges.
CREATE FUNCTION public.mop_calendar_windows_valid(windows jsonb, coverage_start timestamptz, coverage_end timestamptz)
RETURNS boolean LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE SET search_path=pg_catalog AS $$
DECLARE item jsonb; start_text text; end_text text; start_value timestamptz; end_value timestamptz;
BEGIN
  IF windows IS NULL OR coverage_start IS NULL OR coverage_end IS NULL OR coverage_start>=coverage_end THEN RETURN false; END IF;
  IF jsonb_typeof(windows)<>'array' THEN RETURN false; END IF;
  IF jsonb_array_length(windows)>1000 THEN RETURN false; END IF;
  FOR item IN SELECT value FROM jsonb_array_elements(windows) LOOP
    IF jsonb_typeof(item)<>'object' THEN RETURN false; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(item))<>2 OR NOT (item ? 'start' AND item ? 'end') THEN RETURN false; END IF;
    IF jsonb_typeof(item->'start')<>'string' OR jsonb_typeof(item->'end')<>'string' THEN RETURN false; END IF;
    start_text=item->>'start'; end_text=item->>'end';
    IF start_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,3})?Z$'
       OR end_text !~ '^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}([.][0-9]{1,3})?Z$' THEN RETURN false; END IF;
    start_value=start_text::timestamptz; end_value=end_text::timestamptz;
    IF to_char(start_value AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS')<>substring(start_text from 1 for 19)
       OR to_char(end_value AT TIME ZONE 'UTC','YYYY-MM-DD"T"HH24:MI:SS')<>substring(end_text from 1 for 19) THEN RETURN false; END IF;
    IF start_value<coverage_start OR end_value>coverage_end OR start_value>=end_value THEN RETURN false; END IF;
  END LOOP;
  RETURN true;
EXCEPTION WHEN OTHERS THEN RETURN false;
END $$;
REVOKE ALL ON FUNCTION public.mop_calendar_windows_valid(jsonb,timestamptz,timestamptz) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_calendar_windows_valid(jsonb,timestamptz,timestamptz) TO mop_test_runtime;

CREATE TABLE public.mop_working_calendar_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(updated_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  UNIQUE(tenant_id,id),
  CHECK(updated_at>=created_at)
);
CREATE TABLE public.mop_working_calendar_revision (
  tenant_id uuid NOT NULL,
  calendar_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version>=1),
  previous_version bigint,
  actor_id uuid NOT NULL,
  name varchar(100) NOT NULL CHECK(char_length(name) BETWEEN 1 AND 100),
  coverage_start timestamptz(3) NOT NULL,
  coverage_end timestamptz(3) NOT NULL,
  working_windows jsonb NOT NULL,
  coverage_complete boolean NOT NULL,
  note text,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
    CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' CHECK(timezone='Asia/Shanghai'),
  lifecycle_status varchar(16) NOT NULL DEFAULT 'DRAFT' CHECK(lifecycle_status='DRAFT'),
  customer_confirmation varchar(24) NOT NULL DEFAULT 'UNVERIFIED' CHECK(customer_confirmation='UNVERIFIED'),
  effective boolean NOT NULL DEFAULT false CHECK(NOT effective),
  record_source varchar(32) NOT NULL DEFAULT 'MANUAL_CALENDAR_DRAFT' CHECK(record_source='MANUAL_CALENDAR_DRAFT'),
  PRIMARY KEY(tenant_id,calendar_id,version),
  FOREIGN KEY(tenant_id,calendar_id) REFERENCES public.mop_working_calendar_draft(tenant_id,id),
  FOREIGN KEY(tenant_id,calendar_id,previous_version) REFERENCES public.mop_working_calendar_revision(tenant_id,calendar_id,version),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((version=1 AND previous_version IS NULL) OR (version>1 AND previous_version IS NOT NULL AND previous_version=version-1)),
  CHECK(coverage_start>=TIMESTAMPTZ '0001-01-01 00:00:00+00' AND coverage_end<=TIMESTAMPTZ '9999-12-31 23:59:59.999+00' AND coverage_start<coverage_end),
  CHECK(public.mop_calendar_windows_valid(working_windows,coverage_start,coverage_end)),
  CHECK(name !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'),
  CHECK(name !~ U&'[\0001-\001F\007F-\009F]'),
  CHECK(note IS NULL OR (char_length(note) BETWEEN 1 AND 2000
    AND note !~ U&'^[\0009-\000D\001C-\0020\00A0\1680\2000-\200A\2028\2029\202F\205F\3000\FEFF]*$'
    AND note !~ U&'[\0001-\0008\000B\000C\000E-\001F\007F-\009F]'))
);
ALTER TABLE public.mop_working_calendar_draft ADD CONSTRAINT mop_working_calendar_current_revision_fk
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_working_calendar_revision(tenant_id,calendar_id,version)
  DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX mop_working_calendar_draft_order ON public.mop_working_calendar_draft(tenant_id,created_at DESC,id DESC);
REVOKE ALL ON public.mop_working_calendar_draft,public.mop_working_calendar_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_working_calendar_draft,public.mop_working_calendar_revision TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_working_calendar_draft TO mop_test_runtime;
-- A complete window declaration remains SELF-REPORTED input: no publishing, customer signoff or automatic defaults.
