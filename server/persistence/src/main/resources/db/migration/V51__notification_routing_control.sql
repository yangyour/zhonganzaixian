-- Explicit notification control journal. No external sending or automatic migration.
DO $$ BEGIN
 IF current_user<>'mop_test_migrator' OR NOT
   (current_database()='mop_test' OR current_database() ~ '^mop_notification_test_[a-f0-9]{12}$') THEN
   RAISE EXCEPTION 'Dedicated notification migration destination required';
 END IF;
END $$;
CREATE TABLE public.mop_notification_control (
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 kind varchar(16) NOT NULL CHECK(kind IN ('POLICY','PREFERENCE','BINDING','REQUEST','ROUTE','RECEIPT')),
 object_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version BETWEEN 0 AND 100000),
 root_version bigint NOT NULL DEFAULT 0 CHECK(root_version=0),
 expected_version bigint NOT NULL CHECK(expected_version=version-1),
 command_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),
 action varchar(40) NOT NULL CHECK(action ~ '^[A-Z][A-Z0-9_]{0,39}$'),
 trace_id varchar(128) NOT NULL CHECK(trace_id ~ '^[A-Za-z0-9._:-]{1,128}$'),
 state jsonb NOT NULL CHECK(jsonb_typeof(state)='object' AND octet_length(state::text)<=65536),
 command_sha256 varchar(64) NOT NULL CHECK(command_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',transaction_timestamp()),
 PRIMARY KEY(tenant_id,kind,object_id,version),
 UNIQUE(tenant_id,command_id),
 FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
 FOREIGN KEY(tenant_id,kind,object_id,root_version)
   REFERENCES public.mop_notification_control(tenant_id,kind,object_id,version)
);
CREATE INDEX mop_notification_control_roots ON public.mop_notification_control(tenant_id,kind,recorded_at,object_id)
 WHERE version=0;
CREATE FUNCTION public.mop_notification_control_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE prior bigint;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Notification control history immutable' USING ERRCODE='23514'; END IF;
 IF NEW.version>0 THEN
  PERFORM 1 FROM public.mop_notification_control WHERE tenant_id=NEW.tenant_id AND kind=NEW.kind
   AND object_id=NEW.object_id AND version=0 FOR UPDATE;
  IF NOT FOUND THEN RAISE EXCEPTION 'Notification control root required' USING ERRCODE='23503'; END IF;
  SELECT max(version) INTO prior FROM public.mop_notification_control
   WHERE tenant_id=NEW.tenant_id AND kind=NEW.kind AND object_id=NEW.object_id;
  IF NEW.version<>prior+1 THEN RAISE EXCEPTION 'Notification control version conflict' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.recorded_at=date_trunc('milliseconds',transaction_timestamp());
 RETURN NEW;
END $$;
CREATE TRIGGER mop_notification_control_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_notification_control
 FOR EACH ROW EXECUTE FUNCTION public.mop_notification_control_guard();
REVOKE ALL ON public.mop_notification_control FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_notification_control TO mop_test_runtime;
GRANT UPDATE(root_version) ON public.mop_notification_control TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_notification_control_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_notification_control_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_notification_control IS 'Tenant-scoped immutable routing configurations, source requests, route operations and authenticated receipt inbox. Current authority and schema-validated state required on every operation. No credentials or provider payloads.';
