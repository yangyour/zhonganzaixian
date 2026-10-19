-- Project-scoped WeCom identity bindings, explicit delivery ledger and one-use browser correlation.
-- Secrets and provider tokens are never stored here. Startup never applies this migration.
CREATE TABLE public.mop_wecom_binding (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 user_id uuid NOT NULL REFERENCES public.mop_user(id),
 corp_id varchar(66) NOT NULL CHECK (corp_id ~ '^ww[A-Za-z0-9]{8,64}$'),
 wecom_user_id varchar(64) NOT NULL CHECK (wecom_user_id ~ '^[A-Za-z0-9_.@-]{1,64}$' AND lower(wecom_user_id)<>'@all'),
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 9007199254740991),
 verified_at timestamptz(3) NOT NULL,
 created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 updated_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 updated_by uuid NOT NULL REFERENCES public.mop_user(id),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,user_id,corp_id),
 FOREIGN KEY(user_id,tenant_id) REFERENCES public.mop_membership(user_id,tenant_id),
 CHECK (updated_at>=created_at AND verified_at<=updated_at)
);
CREATE UNIQUE INDEX mop_wecom_binding_member ON public.mop_wecom_binding(corp_id,lower(wecom_user_id));

CREATE TABLE public.mop_wecom_delivery (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 actor_id uuid NOT NULL REFERENCES public.mop_user(id),
 permission_version bigint NOT NULL CHECK (permission_version>0),
 binding_id uuid,
 source_message_id uuid,
 corp_id varchar(66) NOT NULL CHECK (corp_id ~ '^ww[A-Za-z0-9]{8,64}$'),
 agent_id integer NOT NULL CHECK (agent_id>0),
 recipient varchar(64) NOT NULL CHECK (recipient ~ '^[A-Za-z0-9_.@-]{1,64}$' AND lower(recipient)<>'@all'),
 message_text text NOT NULL CHECK (octet_length(message_text) BETWEEN 1 AND 2048),
 kind varchar(16) NOT NULL CHECK (kind IN ('TEST','NOTIFICATION')),
 status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED')),
 version bigint NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 9007199254740991),
 attempt_id uuid,
 provider_message_id varchar(512),
 error_code varchar(80),
 provider_code integer,
 created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 started_at timestamptz(3),
 finished_at timestamptz(3),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,source_message_id,binding_id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES public.mop_wecom_binding(tenant_id,id),
 FOREIGN KEY(source_message_id) REFERENCES public.mop_notification_message(id),
 CHECK ((kind='TEST' AND source_message_id IS NULL AND binding_id IS NULL) OR (kind='NOTIFICATION' AND source_message_id IS NOT NULL AND binding_id IS NOT NULL)),
 CHECK ((status='QUEUED' AND attempt_id IS NULL AND started_at IS NULL AND finished_at IS NULL AND version=0)
   OR (status='PROCESSING' AND attempt_id IS NOT NULL AND started_at IS NOT NULL AND finished_at IS NULL AND version=1)
   OR (status IN ('ACCEPTED','FAILED','UNKNOWN','CANCELLED') AND attempt_id IS NOT NULL AND started_at IS NOT NULL AND finished_at IS NOT NULL AND version=2)),
 CHECK ((status='ACCEPTED' AND provider_message_id IS NOT NULL AND error_code IS NULL) OR (status<>'ACCEPTED' AND provider_message_id IS NULL)),
 CHECK (started_at IS NULL OR started_at>=created_at),
 CHECK (finished_at IS NULL OR finished_at>=started_at)
);
CREATE INDEX mop_wecom_delivery_queue ON public.mop_wecom_delivery(tenant_id,created_at) WHERE status='QUEUED';
CREATE INDEX mop_wecom_delivery_ledger ON public.mop_wecom_delivery(tenant_id,actor_id,created_at DESC);

CREATE TABLE public.mop_wecom_login_state (
 state_hash char(64) PRIMARY KEY CHECK (state_hash ~ '^[a-f0-9]{64}$'),
 browser_hash char(64) NOT NULL CHECK (browser_hash ~ '^[a-f0-9]{64}$'),
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 corp_id varchar(66) NOT NULL,
 agent_id integer NOT NULL CHECK (agent_id>0),
 created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 expires_at timestamptz(3) NOT NULL,
 consumed_at timestamptz(3),
 CHECK (expires_at>created_at AND expires_at<=created_at+interval '5 minutes'),
 CHECK (consumed_at IS NULL OR consumed_at>=created_at)
);

CREATE FUNCTION public.mop_wecom_binding_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'WeCom bindings cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.version<>1 OR NOT NEW.active THEN RAISE EXCEPTION 'Initial binding must be verified and active'; END IF;
 ELSE
   IF (to_jsonb(NEW)-ARRAY['active','version','updated_at','updated_by']) IS DISTINCT FROM (to_jsonb(OLD)-ARRAY['active','version','updated_at','updated_by'])
     OR NEW.version<>OLD.version+1 OR NEW.active=OLD.active OR NEW.updated_at<OLD.updated_at THEN RAISE EXCEPTION 'Invalid WeCom binding revision'; END IF;
 END IF;
 IF NEW.verified_at>clock_timestamp() OR NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Future binding timestamp'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_wecom_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_wecom_binding FOR EACH ROW EXECUTE FUNCTION public.mop_wecom_binding_guard();

CREATE FUNCTION public.mop_wecom_delivery_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Delivery evidence cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'QUEUED' THEN RAISE EXCEPTION 'Delivery must be enqueued before dispatch'; END IF;
   IF NEW.kind='NOTIFICATION' AND NOT EXISTS(SELECT 1 FROM public.mop_notification_message m JOIN public.mop_wecom_binding b ON b.id=NEW.binding_id AND b.tenant_id=m.tenant_id AND b.user_id=m.recipient_id
     WHERE m.id=NEW.source_message_id AND m.tenant_id=NEW.tenant_id AND b.active AND b.corp_id=NEW.corp_id AND lower(b.wecom_user_id)=lower(NEW.recipient) AND NEW.actor_id=m.recipient_id) THEN
     RAISE EXCEPTION 'Notification recipient provenance mismatch';
   END IF;
 ELSE
   IF (to_jsonb(NEW)-ARRAY['status','version','attempt_id','provider_message_id','error_code','provider_code','started_at','finished_at']) IS DISTINCT FROM
      (to_jsonb(OLD)-ARRAY['status','version','attempt_id','provider_message_id','error_code','provider_code','started_at','finished_at'])
      OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Delivery immutable fields changed'; END IF;
   IF OLD.status='QUEUED' AND NEW.status='PROCESSING' THEN RETURN NEW; END IF;
   IF OLD.status<>'PROCESSING' OR NEW.status NOT IN ('ACCEPTED','FAILED','UNKNOWN','CANCELLED') OR NEW.attempt_id<>OLD.attempt_id OR NEW.started_at<>OLD.started_at THEN
     RAISE EXCEPTION 'Delivery transition or attempt identity invalid'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_wecom_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_wecom_delivery FOR EACH ROW EXECUTE FUNCTION public.mop_wecom_delivery_guard();

CREATE FUNCTION public.mop_wecom_login_state_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Login correlation evidence cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.consumed_at IS NOT NULL THEN RAISE EXCEPTION 'Login state is already consumed'; END IF;
 ELSIF (to_jsonb(NEW)-'consumed_at') IS DISTINCT FROM (to_jsonb(OLD)-'consumed_at') OR OLD.consumed_at IS NOT NULL OR NEW.consumed_at IS NULL OR NEW.consumed_at>=OLD.expires_at THEN
   RAISE EXCEPTION 'Login state must be consumed once before expiry';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_wecom_login_state_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_wecom_login_state FOR EACH ROW EXECUTE FUNCTION public.mop_wecom_login_state_guard();

REVOKE ALL ON public.mop_wecom_binding,public.mop_wecom_delivery,public.mop_wecom_login_state FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_wecom_binding,public.mop_wecom_delivery,public.mop_wecom_login_state TO mop_test_runtime;
GRANT UPDATE(active,version,updated_at,updated_by) ON public.mop_wecom_binding TO mop_test_runtime;
GRANT UPDATE(status,version,attempt_id,provider_message_id,error_code,provider_code,started_at,finished_at) ON public.mop_wecom_delivery TO mop_test_runtime;
GRANT UPDATE(consumed_at) ON public.mop_wecom_login_state TO mop_test_runtime;
