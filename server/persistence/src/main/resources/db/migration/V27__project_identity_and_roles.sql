-- Project-owned local identity and configurable roles. No external identity provider is enabled.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Project identity requires the dedicated migration identity';
  END IF;
END $$;

CREATE TABLE public.mop_identity_security (
  user_id uuid PRIMARY KEY REFERENCES public.mop_user(id),
  revision bigint NOT NULL DEFAULT 1 CHECK(revision BETWEEN 1 AND 9007199254740990),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp()
);
INSERT INTO public.mop_identity_security(user_id) SELECT id FROM public.mop_user;
CREATE FUNCTION public.mop_identity_user_seed() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  INSERT INTO public.mop_identity_security(user_id) VALUES(NEW.id);
  RETURN NEW;
END $$;
CREATE TRIGGER mop_identity_user_seed AFTER INSERT ON public.mop_user FOR EACH ROW EXECUTE FUNCTION public.mop_identity_user_seed();

CREATE TABLE public.mop_local_account (
  user_id uuid PRIMARY KEY REFERENCES public.mop_user(id),
  username varchar(64) NOT NULL CHECK(username ~ '^[A-Za-z0-9][A-Za-z0-9_.@-]{2,63}$'),
  username_key varchar(64) GENERATED ALWAYS AS (lower(username COLLATE "C")) STORED UNIQUE NOT NULL,
  password_hash varchar(256) NOT NULL CHECK(password_hash ~ '^\{pbkdf2-sha256-v1\}[0-9a-f]{96}$'),
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  must_change_password boolean NOT NULL DEFAULT false,
  failed_attempts integer NOT NULL DEFAULT 0 CHECK(failed_attempts BETWEEN 0 AND 1000000),
  locked_until timestamptz(3),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  last_login_at timestamptz(3)
);
CREATE TABLE public.mop_local_password_history (
  user_id uuid NOT NULL REFERENCES public.mop_local_account(user_id),
  revision bigint NOT NULL CHECK(revision>0),
  password_hash varchar(256) NOT NULL CHECK(password_hash ~ '^\{pbkdf2-sha256-v1\}[0-9a-f]{96}$'),
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(user_id,revision)
);
CREATE FUNCTION public.mop_local_account_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF ROW(NEW.user_id,NEW.username,NEW.created_at) IS DISTINCT FROM ROW(OLD.user_id,OLD.username,OLD.created_at) THEN
    RAISE EXCEPTION 'Local account identity is immutable';
  END IF;
  IF ROW(NEW.password_hash,NEW.must_change_password) IS DISTINCT FROM ROW(OLD.password_hash,OLD.must_change_password) THEN
    IF NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Credential changes require a new version'; END IF;
    UPDATE public.mop_identity_security SET revision=revision+1,updated_at=statement_timestamp() WHERE user_id=NEW.user_id;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_local_account_guard BEFORE UPDATE ON public.mop_local_account FOR EACH ROW EXECUTE FUNCTION public.mop_local_account_guard();
CREATE TABLE public.mop_identity_policy (
  singleton boolean PRIMARY KEY DEFAULT true CHECK(singleton),
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  minimum_password_length integer CHECK(minimum_password_length BETWEEN 1 AND 128),
  password_history_count integer CHECK(password_history_count BETWEEN 1 AND 20),
  failed_attempt_limit integer CHECK(failed_attempt_limit BETWEEN 1 AND 100),
  lock_minutes integer CHECK(lock_minutes BETWEEN 1 AND 1440),
  idle_minutes integer CHECK(idle_minutes BETWEEN 1 AND 1440),
  force_change_on_reset boolean,
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  CHECK((failed_attempt_limit IS NULL)=(lock_minutes IS NULL))
);
-- User explicitly deferred these policies. No suggested defaults are enabled.
INSERT INTO public.mop_identity_policy(singleton) VALUES(true);

CREATE TABLE public.mop_role_capability_limit (
  base_role varchar(32) NOT NULL CHECK(base_role IN ('viewer','tadmin','msp','padmin')),
  capability varchar(128) NOT NULL CHECK(capability ~ '^[a-z_]+:[a-z_]+$'),
  PRIMARY KEY(base_role,capability)
);
-- The reviewed business-category capability limits are inserted below before roles are seeded.
INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES
('padmin','users:read'),
('padmin','users:write'),
('padmin','roles:read'),
('padmin','roles:write'),
('padmin','dashboard:read'),
('padmin','notification_preferences:read_own'),
('padmin','notification_preferences:write_own'),
('padmin','change_notice_drafts:read'),
('padmin','change_notice_drafts:write'),
('padmin','backup_plan_drafts:read'),
('padmin','backup_plan_drafts:write'),
('padmin','inspection_source_collections:read'),
('padmin','inspection_source_collections:write'),
('padmin','inspection_worksheets:read'),
('padmin','inspection_worksheets:write'),
('padmin','inspection_templates:read'),
('padmin','inspection_templates:write'),
('padmin','contacts:read'),
('padmin','contacts:write'),
('padmin','operations:compare_explicit'),
('padmin','capacity_scenarios:read'),
('padmin','capacity_scenarios:write'),
('padmin','capacity:estimate'),
('padmin','search:read'),
('padmin','assets:read'),
('padmin','assets:write'),
('padmin','configurations:read'),
('padmin','configurations:write'),
('padmin','audit:read'),
('padmin','tickets:read'),
('padmin','ticket_comments:read'),
('padmin','ticket_comments:write'),
('padmin','ticket_notes:read_internal'),
('padmin','ticket_notes:write_internal'),
('padmin','manual_incidents:read'),
('padmin','manual_incidents:write'),
('padmin','manual_recovery:read'),
('padmin','manual_recovery:confirm'),
('padmin','sla_scenarios:read'),
('padmin','asset_dates:read'),
('msp','dashboard:read'),
('msp','notification_preferences:read_own'),
('msp','notification_preferences:write_own'),
('msp','search:read'),
('msp','assets:read'),
('msp','assets:write'),
('msp','tickets:read'),
('msp','ticket_comments:read'),
('msp','ticket_comments:write'),
('msp','worklogs:read_own'),
('msp','worklogs:write_own'),
('msp','ticket_notes:read_internal'),
('msp','ticket_notes:write_internal'),
('msp','manual_incidents:read'),
('msp','manual_incidents:write'),
('msp','manual_recovery:read'),
('msp','manual_recovery:confirm'),
('msp','sla_scenarios:read'),
('msp','asset_dates:read'),
('tadmin','dashboard:read'),
('tadmin','notification_preferences:read_own'),
('tadmin','notification_preferences:write_own'),
('tadmin','contacts:read'),
('tadmin','contacts:write'),
('tadmin','search:read'),
('tadmin','assets:read'),
('tadmin','assets:write'),
('tadmin','tickets:read'),
('tadmin','ticket_comments:read'),
('tadmin','ticket_comments:write'),
('tadmin','tickets:report'),
('viewer','dashboard:read'),
('viewer','notification_preferences:read_own'),
('viewer','notification_preferences:write_own'),
('viewer','search:read'),
('viewer','assets:read'),
('viewer','tickets:read'),
('viewer','ticket_comments:read'),
('viewer','ticket_comments:write'),
('viewer','tickets:report');

CREATE TABLE public.mop_project_role (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  name varchar(100) NOT NULL CHECK(btrim(name)<>''),
  description varchar(2000),
  base_role varchar(32) NOT NULL CHECK(base_role IN ('viewer','tadmin','msp','padmin')),
  permissions text[] NOT NULL,
  builtin boolean NOT NULL DEFAULT false,
  enabled boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_by uuid REFERENCES public.mop_user(id),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,id), UNIQUE(tenant_id,name)
);
CREATE UNIQUE INDEX mop_role_one_builtin ON public.mop_project_role(tenant_id,base_role) WHERE builtin;
CREATE TABLE public.mop_member_role_assignment (
  tenant_id uuid NOT NULL,
  user_id uuid NOT NULL,
  role_id uuid NOT NULL,
  PRIMARY KEY(tenant_id,user_id),
  FOREIGN KEY(tenant_id,user_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,role_id) REFERENCES public.mop_project_role(tenant_id,id)
);
CREATE TABLE public.mop_external_identity_binding (
  id uuid PRIMARY KEY,
  user_id uuid NOT NULL REFERENCES public.mop_user(id),
  provider varchar(64) NOT NULL CHECK(provider='oidc'),
  issuer varchar(2000) NOT NULL CHECK(issuer ~ '^https?://[^[:space:]]+$'),
  subject varchar(255) NOT NULL CHECK(subject<>''),
  active boolean NOT NULL DEFAULT true,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(provider,issuer,subject)
);

CREATE FUNCTION public.mop_project_role_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM unnest(NEW.permissions) p WHERE p IS NULL) OR cardinality(NEW.permissions)>256 OR
     cardinality(NEW.permissions)<>(SELECT count(DISTINCT p) FROM unnest(NEW.permissions) p) OR
     EXISTS(SELECT 1 FROM unnest(NEW.permissions) p WHERE NOT EXISTS(SELECT 1 FROM public.mop_role_capability_limit l WHERE l.base_role=NEW.base_role AND l.capability=p)) THEN
    RAISE EXCEPTION 'Role capabilities exceed its explicit business category';
  END IF;
  IF TG_OP='UPDATE' THEN
    IF ROW(NEW.id,NEW.tenant_id,NEW.base_role,NEW.builtin,NEW.created_by,NEW.created_at) IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.base_role,OLD.builtin,OLD.created_by,OLD.created_at) OR NEW.version<>OLD.version+1 THEN
      RAISE EXCEPTION 'Role identity is immutable and version must advance';
    END IF;
    IF OLD.builtin AND current_user='mop_test_runtime' THEN RAISE EXCEPTION 'Built-in role is protected; create an explicit custom role'; END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_project_role_guard BEFORE INSERT OR UPDATE ON public.mop_project_role FOR EACH ROW EXECUTE FUNCTION public.mop_project_role_guard();

CREATE FUNCTION public.mop_seed_tenant_roles() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  INSERT INTO public.mop_project_role(id,tenant_id,name,base_role,permissions,builtin)
  SELECT md5('mop-project-role-v1:'||NEW.id::text||':'||l.base_role)::uuid,NEW.id,
    CASE l.base_role WHEN 'padmin' THEN '平台管理员' WHEN 'msp' THEN 'MSP工程师' WHEN 'tadmin' THEN '客户管理员' ELSE '客户观察者' END,
    l.base_role,array_agg(l.capability ORDER BY l.capability),true
  FROM public.mop_role_capability_limit l GROUP BY l.base_role;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_seed_tenant_roles AFTER INSERT ON public.mop_tenant FOR EACH ROW EXECUTE FUNCTION public.mop_seed_tenant_roles();
INSERT INTO public.mop_project_role(id,tenant_id,name,base_role,permissions,builtin)
SELECT md5('mop-project-role-v1:'||t.id::text||':'||l.base_role)::uuid,t.id,
  CASE l.base_role WHEN 'padmin' THEN '平台管理员' WHEN 'msp' THEN 'MSP工程师' WHEN 'tadmin' THEN '客户管理员' ELSE '客户观察者' END,
  l.base_role,array_agg(l.capability ORDER BY l.capability),true
FROM public.mop_tenant t CROSS JOIN public.mop_role_capability_limit l GROUP BY t.id,l.base_role;

CREATE FUNCTION public.mop_effective_capabilities(p_user uuid,p_tenant uuid) RETURNS text[]
LANGUAGE sql STABLE SET search_path=pg_catalog AS $$
  SELECT COALESCE((SELECT r.permissions FROM public.mop_membership m
    JOIN public.mop_user u ON u.id=m.user_id
    LEFT JOIN public.mop_member_role_assignment a ON a.tenant_id=m.tenant_id AND a.user_id=m.user_id
    JOIN public.mop_project_role r ON r.tenant_id=m.tenant_id AND r.id=COALESCE(a.role_id,
      (SELECT d.id FROM public.mop_project_role d WHERE d.tenant_id=m.tenant_id AND d.base_role=m.role_code AND d.builtin))
    WHERE m.user_id=p_user AND m.tenant_id=p_tenant AND m.active AND u.active AND r.enabled AND r.base_role=m.role_code),ARRAY[]::text[])
$$;

CREATE FUNCTION public.mop_role_permission_epoch() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF ROW(NEW.permissions,NEW.enabled) IS DISTINCT FROM ROW(OLD.permissions,OLD.enabled) THEN
    UPDATE public.mop_membership m SET permission_version=permission_version+1 WHERE m.tenant_id=NEW.tenant_id AND
      (EXISTS(SELECT 1 FROM public.mop_member_role_assignment a WHERE a.tenant_id=m.tenant_id AND a.user_id=m.user_id AND a.role_id=NEW.id)
       OR (NEW.builtin AND m.role_code=NEW.base_role AND NOT EXISTS(SELECT 1 FROM public.mop_member_role_assignment a WHERE a.tenant_id=m.tenant_id AND a.user_id=m.user_id)));
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_role_permission_epoch AFTER UPDATE ON public.mop_project_role FOR EACH ROW EXECUTE FUNCTION public.mop_role_permission_epoch();

CREATE FUNCTION public.mop_assignment_scope_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE selected_tenant uuid; selected_user uuid;
BEGIN
  selected_tenant=COALESCE(NEW.tenant_id,OLD.tenant_id); selected_user=COALESCE(NEW.user_id,OLD.user_id);
  IF EXISTS(SELECT 1 FROM public.mop_member_role_assignment a JOIN public.mop_project_role r ON r.tenant_id=a.tenant_id AND r.id=a.role_id
    JOIN public.mop_membership m ON m.tenant_id=a.tenant_id AND m.user_id=a.user_id
    WHERE a.tenant_id=selected_tenant AND a.user_id=selected_user AND r.base_role<>m.role_code) THEN
    RAISE EXCEPTION 'Assigned role must match current member business category';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER mop_assignment_scope_guard AFTER INSERT OR UPDATE ON public.mop_member_role_assignment DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_assignment_scope_guard();
CREATE CONSTRAINT TRIGGER mop_member_role_scope_guard AFTER UPDATE ON public.mop_membership DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_assignment_scope_guard();

CREATE FUNCTION public.mop_assignment_epoch() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  UPDATE public.mop_membership SET permission_version=permission_version+1 WHERE tenant_id=COALESCE(NEW.tenant_id,OLD.tenant_id) AND user_id=COALESCE(NEW.user_id,OLD.user_id);
  RETURN COALESCE(NEW,OLD);
END $$;
CREATE TRIGGER mop_assignment_epoch AFTER INSERT OR UPDATE OR DELETE ON public.mop_member_role_assignment FOR EACH ROW EXECUTE FUNCTION public.mop_assignment_epoch();

REVOKE ALL ON public.mop_identity_security,public.mop_local_account,public.mop_local_password_history,public.mop_identity_policy,
  public.mop_role_capability_limit,public.mop_project_role,public.mop_member_role_assignment,public.mop_external_identity_binding FROM PUBLIC,mop_test_runtime;
GRANT SELECT ON public.mop_identity_security,public.mop_local_account,public.mop_local_password_history,public.mop_identity_policy,
  public.mop_role_capability_limit,public.mop_project_role,public.mop_member_role_assignment,public.mop_external_identity_binding TO mop_test_runtime;
GRANT INSERT ON public.mop_identity_security,public.mop_local_account,public.mop_local_password_history,public.mop_project_role,public.mop_member_role_assignment,public.mop_external_identity_binding,public.mop_user,public.mop_membership TO mop_test_runtime;
GRANT UPDATE(revision,updated_at) ON public.mop_identity_security TO mop_test_runtime;
GRANT UPDATE(password_hash,version,must_change_password,failed_attempts,locked_until,updated_at,last_login_at) ON public.mop_local_account TO mop_test_runtime;
GRANT DELETE ON public.mop_local_password_history,public.mop_member_role_assignment TO mop_test_runtime;
GRANT UPDATE(version,minimum_password_length,password_history_count,failed_attempt_limit,lock_minutes,idle_minutes,force_change_on_reset,updated_at) ON public.mop_identity_policy TO mop_test_runtime;
GRANT UPDATE(name,description,permissions,enabled,version,updated_at) ON public.mop_project_role TO mop_test_runtime;
GRANT UPDATE(role_id) ON public.mop_member_role_assignment TO mop_test_runtime;
GRANT UPDATE(active,version,updated_at) ON public.mop_external_identity_binding TO mop_test_runtime;
GRANT UPDATE(display_name,active) ON public.mop_user TO mop_test_runtime;
GRANT UPDATE(role_code,active,permission_version) ON public.mop_membership TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_project_role_guard(),public.mop_seed_tenant_roles(),public.mop_role_permission_epoch(),public.mop_assignment_scope_guard(),public.mop_assignment_epoch(),public.mop_effective_capabilities(uuid,uuid) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_project_role_guard(),public.mop_seed_tenant_roles(),public.mop_role_permission_epoch(),public.mop_assignment_scope_guard(),public.mop_assignment_epoch(),public.mop_effective_capabilities(uuid,uuid) TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_identity_user_seed() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_identity_user_seed() TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_local_account_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_local_account_guard() TO mop_test_runtime;
