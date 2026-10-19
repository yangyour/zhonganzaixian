-- Explicit tenant email recipients and a single-attempt SMTP submission ledger.
-- No password, token, mailbox contents or unverified delivery receipt is stored here.
CREATE TABLE public.mop_email_binding (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 user_id uuid NOT NULL REFERENCES public.mop_user(id),
 sender varchar(254) NOT NULL CHECK (sender=lower(sender) AND sender ~ '^[^[:space:]<>;,]+@[^[:space:]<>;,]+[.][^[:space:]<>;,]+$'),
 email varchar(254) NOT NULL CHECK (email=lower(email) AND email ~ '^[^[:space:]<>;,]+@[^[:space:]<>;,]+[.][^[:space:]<>;,]+$'),
 active boolean NOT NULL DEFAULT true,
 version bigint NOT NULL DEFAULT 1 CHECK (version BETWEEN 1 AND 9007199254740991),
 created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 updated_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 updated_by uuid NOT NULL REFERENCES public.mop_user(id),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,user_id,sender), UNIQUE(tenant_id,sender,email),
 FOREIGN KEY(user_id,tenant_id) REFERENCES public.mop_membership(user_id,tenant_id),
 CHECK (updated_at>=created_at)
);
CREATE TABLE public.mop_email_delivery (
 id uuid PRIMARY KEY,
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 actor_id uuid NOT NULL REFERENCES public.mop_user(id),
 permission_version bigint NOT NULL CHECK (permission_version>0),
 binding_id uuid,
 binding_version bigint,
 source_message_id uuid REFERENCES public.mop_notification_message(id),
 sender varchar(254) NOT NULL CHECK (sender=lower(sender) AND sender ~ '^[^[:space:]<>;,]+@[^[:space:]<>;,]+[.][^[:space:]<>;,]+$'),
 recipient varchar(254) NOT NULL CHECK (recipient=lower(recipient) AND recipient ~ '^[^[:space:]<>;,]+@[^[:space:]<>;,]+[.][^[:space:]<>;,]+$'),
 subject varchar(200) NOT NULL CHECK (length(subject)>0 AND subject !~ '[[:cntrl:]]'),
 message_text text NOT NULL CHECK (octet_length(message_text) BETWEEN 1 AND 32768),
 kind varchar(16) NOT NULL CHECK (kind IN ('TEST','NOTIFICATION')),
 status varchar(16) NOT NULL DEFAULT 'QUEUED' CHECK (status IN ('QUEUED','PROCESSING','ACCEPTED','FAILED','UNKNOWN','CANCELLED')),
 version bigint NOT NULL DEFAULT 0 CHECK (version BETWEEN 0 AND 2),
 attempt_id uuid,
 message_id varchar(512),
 error_code varchar(80),
 smtp_code integer CHECK (smtp_code IS NULL OR smtp_code BETWEEN 200 AND 599),
 created_at timestamptz(3) NOT NULL DEFAULT date_trunc('milliseconds',clock_timestamp()),
 started_at timestamptz(3),
 finished_at timestamptz(3),
 UNIQUE(tenant_id,id), UNIQUE(tenant_id,source_message_id,binding_id),
 FOREIGN KEY(tenant_id,binding_id) REFERENCES public.mop_email_binding(tenant_id,id),
 CHECK ((kind='TEST' AND source_message_id IS NULL AND binding_id IS NULL AND binding_version IS NULL)
   OR (kind='NOTIFICATION' AND source_message_id IS NOT NULL AND binding_id IS NOT NULL AND binding_version IS NOT NULL AND binding_version>0)),
 CHECK ((status='QUEUED' AND attempt_id IS NULL AND started_at IS NULL AND finished_at IS NULL AND version=0)
   OR (status='PROCESSING' AND attempt_id IS NOT NULL AND started_at IS NOT NULL AND finished_at IS NULL AND version=1)
   OR (status IN ('ACCEPTED','FAILED','UNKNOWN','CANCELLED') AND attempt_id IS NOT NULL AND started_at IS NOT NULL AND finished_at IS NOT NULL AND version=2)),
 CHECK ((status='ACCEPTED' AND message_id IS NOT NULL AND error_code IS NULL AND smtp_code IS NOT NULL AND smtp_code BETWEEN 200 AND 299)
   OR (status<>'ACCEPTED' AND message_id IS NULL)),
 CHECK (started_at IS NULL OR started_at>=created_at),
 CHECK (finished_at IS NULL OR finished_at>=started_at)
);
CREATE INDEX mop_email_delivery_queue ON public.mop_email_delivery(tenant_id,sender,created_at) WHERE status='QUEUED';
CREATE INDEX mop_email_delivery_ledger ON public.mop_email_delivery(tenant_id,actor_id,created_at DESC);
CREATE FUNCTION public.mop_email_binding_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Email bindings cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.version<>1 OR NOT NEW.active THEN RAISE EXCEPTION 'Initial email binding must be active'; END IF;
 ELSE
   IF (to_jsonb(NEW)-ARRAY['email','active','version','updated_at','updated_by']) IS DISTINCT FROM
      (to_jsonb(OLD)-ARRAY['email','active','version','updated_at','updated_by'])
      OR NEW.version<>OLD.version+1 OR (NEW.email=OLD.email AND NEW.active=OLD.active) OR NEW.updated_at<OLD.updated_at THEN
     RAISE EXCEPTION 'Invalid email binding revision'; END IF;
 END IF;
 IF NEW.updated_at>clock_timestamp() THEN RAISE EXCEPTION 'Future binding timestamp'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_email_binding_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_email_binding FOR EACH ROW EXECUTE FUNCTION public.mop_email_binding_guard();
CREATE FUNCTION public.mop_email_delivery_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP='DELETE' THEN RAISE EXCEPTION 'Email delivery evidence cannot be deleted'; END IF;
 IF TG_OP='INSERT' THEN
   IF NEW.status<>'QUEUED' THEN RAISE EXCEPTION 'Email must be enqueued before dispatch'; END IF;
   IF NEW.kind='NOTIFICATION' AND NOT EXISTS (
     SELECT 1 FROM public.mop_notification_message m JOIN public.mop_email_binding b
       ON b.id=NEW.binding_id AND b.tenant_id=m.tenant_id AND b.user_id=m.recipient_id
     WHERE m.id=NEW.source_message_id AND m.tenant_id=NEW.tenant_id AND b.active
       AND b.sender=NEW.sender AND b.email=NEW.recipient AND b.version=NEW.binding_version AND NEW.actor_id=m.recipient_id
   ) THEN RAISE EXCEPTION 'Email notification recipient provenance mismatch'; END IF;
 ELSE
   IF (to_jsonb(NEW)-ARRAY['status','version','attempt_id','message_id','error_code','smtp_code','started_at','finished_at']) IS DISTINCT FROM
      (to_jsonb(OLD)-ARRAY['status','version','attempt_id','message_id','error_code','smtp_code','started_at','finished_at'])
      OR NEW.version<>OLD.version+1 THEN RAISE EXCEPTION 'Immutable email fields changed'; END IF;
   IF OLD.status='QUEUED' AND NEW.status='PROCESSING' THEN RETURN NEW; END IF;
   IF OLD.status<>'PROCESSING' OR NEW.status NOT IN ('ACCEPTED','FAILED','UNKNOWN','CANCELLED')
      OR NEW.attempt_id<>OLD.attempt_id OR NEW.started_at<>OLD.started_at THEN RAISE EXCEPTION 'Invalid email transition'; END IF;
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_email_delivery_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_email_delivery FOR EACH ROW EXECUTE FUNCTION public.mop_email_delivery_guard();
REVOKE ALL ON public.mop_email_binding,public.mop_email_delivery FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_email_binding,public.mop_email_delivery TO mop_test_runtime;
GRANT UPDATE(email,active,version,updated_at,updated_by) ON public.mop_email_binding TO mop_test_runtime;
GRANT UPDATE(status,version,attempt_id,message_id,error_code,smtp_code,started_at,finished_at) ON public.mop_email_delivery TO mop_test_runtime;
