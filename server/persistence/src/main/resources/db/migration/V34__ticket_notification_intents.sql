-- Explicit, project-only migration. In-app ticket notification records only; no external channel delivery.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Notification intent migration requires dedicated project migrator';
  END IF;
END $$;

CREATE TABLE public.mop_notification_intent (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  source_type varchar(24) NOT NULL CHECK(source_type='TICKET_WORKFLOW'),
  outbox_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  event_type varchar(80) NOT NULL CHECK(event_type ~ '^ticket\.[a-z0-9_]{1,64}$'),
  phase varchar(64) NOT NULL CHECK(phase ~ '^[A-Z][A-Z0-9_]{0,63}$'),
  priority varchar(2) CHECK(priority IN ('P1','P2','P3')),
  recipient_status varchar(16) NOT NULL CHECK(recipient_status IN ('COMPLETE','PARTIAL','PENDING_RULE')),
  recipient_count integer NOT NULL CHECK(recipient_count BETWEEN 0 AND 100),
  pending_reasons jsonb NOT NULL CHECK(jsonb_typeof(pending_reasons)='array' AND jsonb_array_length(pending_reasons)<=16),
  external_dispatch varchar(24) NOT NULL CHECK(external_dispatch='NOT_CONFIGURED'),
  external_blockers jsonb NOT NULL CHECK(jsonb_typeof(external_blockers)='array' AND jsonb_array_length(external_blockers)<=16
    AND external_blockers @> '["Q14_CHANNEL_MATRIX","Q18_PROVIDER_CREDENTIALS"]'::jsonb),
  created_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,outbox_id), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,outbox_id) REFERENCES public.mop_ticket_workflow_outbox(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  CHECK((recipient_status='COMPLETE')=(jsonb_array_length(pending_reasons)=0)),
  CHECK((recipient_status='PENDING_RULE')=(recipient_count=0))
);
CREATE INDEX mop_notification_intent_recent ON public.mop_notification_intent(tenant_id,created_at DESC,id DESC);

CREATE TABLE public.mop_notification_message (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  intent_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  recipient_id uuid NOT NULL,
  audience varchar(10) NOT NULL CHECK(audience IN ('CUSTOMER','INTERNAL')),
  title varchar(160) NOT NULL CHECK(length(btrim(title))>0),
  body varchar(600) NOT NULL CHECK(length(btrim(body))>0),
  created_at timestamptz(3) NOT NULL,
  version bigint NOT NULL DEFAULT 0 CHECK(version IN (0,1)),
  read_at timestamptz(3),
  UNIQUE(tenant_id,intent_id,recipient_id), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,intent_id) REFERENCES public.mop_notification_intent(tenant_id,id),
  FOREIGN KEY(tenant_id,recipient_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((version=0 AND read_at IS NULL) OR (version=1 AND read_at IS NOT NULL AND read_at>=created_at))
);
CREATE INDEX mop_notification_message_owner ON public.mop_notification_message(tenant_id,recipient_id,created_at DESC,id DESC);
CREATE INDEX mop_notification_message_unread ON public.mop_notification_message(tenant_id,recipient_id) WHERE read_at IS NULL;

CREATE FUNCTION public.mop_notification_intent_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE o_ticket uuid; o_type text; o_created timestamptz;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Notification intents are immutable'; END IF;
  SELECT ticket_id,event_type,created_at INTO o_ticket,o_type,o_created FROM public.mop_ticket_workflow_outbox WHERE tenant_id=NEW.tenant_id AND id=NEW.outbox_id;
  IF NOT FOUND OR o_ticket<>NEW.ticket_id OR o_type<>NEW.event_type OR NEW.created_at<o_created OR NEW.created_at>clock_timestamp()
    OR NEW.phase<>upper(substr(NEW.event_type,8)) THEN
    RAISE EXCEPTION 'Notification intent provenance mismatch';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_notification_intent_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_notification_intent FOR EACH ROW EXECUTE FUNCTION public.mop_notification_intent_guard();

CREATE FUNCTION public.mop_notification_intent_count() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF (SELECT count(*) FROM public.mop_notification_message WHERE tenant_id=NEW.tenant_id AND intent_id=NEW.id)<>NEW.recipient_count THEN
    RAISE EXCEPTION 'Notification recipient count mismatch';
  END IF;
  RETURN NULL;
END $$;
CREATE CONSTRAINT TRIGGER mop_notification_intent_count AFTER INSERT ON public.mop_notification_intent DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_notification_intent_count();

CREATE FUNCTION public.mop_notification_message_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE i public.mop_notification_intent%ROWTYPE; member_role text; reporter uuid;
BEGIN
  IF TG_OP='INSERT' THEN
    SELECT * INTO i FROM public.mop_notification_intent WHERE tenant_id=NEW.tenant_id AND id=NEW.intent_id AND xmin::text=pg_current_xact_id()::text;
    IF NOT FOUND OR NEW.ticket_id<>i.ticket_id OR NEW.created_at<>i.created_at OR NEW.version<>0 OR NEW.read_at IS NOT NULL THEN
      RAISE EXCEPTION 'Notification message must accompany its intent transaction';
    END IF;
    SELECT m.role_code INTO member_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
      WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.recipient_id AND m.active AND u.active;
    IF NOT FOUND THEN RAISE EXCEPTION 'Notification recipient inactive'; END IF;
    IF NEW.audience='CUSTOMER' THEN
      SELECT reporter_id INTO reporter FROM public.mop_ticket WHERE tenant_id=NEW.tenant_id AND id=NEW.ticket_id;
      IF member_role NOT IN ('viewer','tadmin') OR reporter IS DISTINCT FROM NEW.recipient_id THEN RAISE EXCEPTION 'Customer notification recipient mismatch'; END IF;
    ELSIF member_role NOT IN ('msp','padmin') THEN
      RAISE EXCEPTION 'Internal notification recipient mismatch';
    END IF;
  ELSIF TG_OP='UPDATE' THEN
    IF (NEW.id,NEW.tenant_id,NEW.intent_id,NEW.ticket_id,NEW.recipient_id,NEW.audience,NEW.title,NEW.body,NEW.created_at)
       IS DISTINCT FROM (OLD.id,OLD.tenant_id,OLD.intent_id,OLD.ticket_id,OLD.recipient_id,OLD.audience,OLD.title,OLD.body,OLD.created_at)
       OR OLD.version<>0 OR OLD.read_at IS NOT NULL OR NEW.version<>1 OR NEW.read_at IS NULL
       OR NEW.read_at<OLD.created_at OR NEW.read_at>clock_timestamp() THEN
      RAISE EXCEPTION 'Notification content and first-read history are immutable';
    END IF;
  ELSE RAISE EXCEPTION 'Notification message deletion is forbidden';
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_notification_message_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_notification_message FOR EACH ROW EXECUTE FUNCTION public.mop_notification_message_guard();

REVOKE ALL ON public.mop_notification_intent,public.mop_notification_message FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_notification_intent,public.mop_notification_message TO mop_test_runtime;
GRANT UPDATE(version,read_at) ON public.mop_notification_message TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_notification_intent_guard(),public.mop_notification_intent_count(),public.mop_notification_message_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_notification_intent_guard(),public.mop_notification_intent_count(),public.mop_notification_message_guard() TO mop_test_runtime;
