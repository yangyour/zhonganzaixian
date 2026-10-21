-- Explicit, project-only migration. Original registration and V1-V32 are unchanged.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Ticket workflow migration requires dedicated project migrator';
  END IF;
END $$;

CREATE TABLE public.mop_ticket_workflow_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  action varchar(24) NOT NULL CHECK(action IN ('OPEN','ACCEPT','REASSIGN','START','RESOLVE','CLOSE','REOPEN','CANCEL','AUTO_CLOSE')),
  actor_id uuid,
  snapshot jsonb NOT NULL CHECK(jsonb_typeof(snapshot)='object'),
  details jsonb NOT NULL CHECK(jsonb_typeof(details)='object'),
  snapshot_hash text NOT NULL CHECK(snapshot_hash ~ '^[a-f0-9]{64}$'),
  created_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,ticket_id,version), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK((actor_id IS NULL)=(action='AUTO_CLOSE')),
  CHECK((snapshot->>'version')::bigint=version),
  CHECK(snapshot->>'state' IN ('OPEN','ACCEPTED','IN_PROGRESS','RESOLVED','CLOSED','CANCELLED'))
);
CREATE INDEX mop_ticket_workflow_latest ON public.mop_ticket_workflow_event(tenant_id,ticket_id,version DESC);

CREATE TABLE public.mop_ticket_workflow_outbox (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, ticket_id uuid NOT NULL,
  event_id uuid NOT NULL, workflow_version bigint NOT NULL,
  effect_key varchar(160) NOT NULL, event_type varchar(80) NOT NULL,
  payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
  created_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,ticket_id,effect_key), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES public.mop_ticket_workflow_event(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id,workflow_version) REFERENCES public.mop_ticket_workflow_event(tenant_id,ticket_id,version)
);

CREATE TABLE public.mop_ticket_workflow_clock (
  id uuid PRIMARY KEY, tenant_id uuid NOT NULL, ticket_id uuid NOT NULL,
  clock_key varchar(80) NOT NULL, due_at timestamptz(3) NOT NULL,
  kind varchar(24) NOT NULL CHECK(kind IN ('SLA','CONFIRMATION_REMINDER','AUTO_CLOSE')),
  cycle integer NOT NULL CHECK(cycle>=0),
  created_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,ticket_id,clock_key), UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)
);
CREATE INDEX mop_ticket_workflow_clock_due ON public.mop_ticket_workflow_clock(tenant_id,due_at,id);
CREATE TABLE public.mop_ticket_workflow_clock_receipt (
  task_id uuid PRIMARY KEY, tenant_id uuid NOT NULL,
  outcome varchar(24) NOT NULL CHECK(outcome IN ('EMITTED','OBSOLETE','AUTO_CLOSED')),
  completed_at timestamptz(3) NOT NULL,
  FOREIGN KEY(tenant_id,task_id) REFERENCES public.mop_ticket_workflow_clock(tenant_id,id)
);

CREATE FUNCTION public.mop_ticket_workflow_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE previous public.mop_ticket_workflow_event%ROWTYPE; from_state text; to_state text; actor_role text; parent_reporter uuid; parent_created timestamptz;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Ticket workflow facts are immutable'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.ticket.workflow|'||NEW.tenant_id||'|'||NEW.ticket_id,0));
  SELECT reporter_id,created_at INTO parent_reporter,parent_created FROM public.mop_ticket WHERE tenant_id=NEW.tenant_id AND id=NEW.ticket_id;
  IF NOT FOUND OR (NEW.snapshot->>'openedAt')::timestamptz IS DISTINCT FROM parent_created
    OR (NEW.snapshot->'sla'->>'startedAt')::timestamptz IS DISTINCT FROM parent_created
    OR (NEW.snapshot->>'updatedAt')::timestamptz IS DISTINCT FROM NEW.created_at OR NEW.created_at>clock_timestamp() THEN
    RAISE EXCEPTION 'Workflow registration or clock provenance mismatch';
  END IF;
  to_state=NEW.snapshot->>'state';
  IF NEW.actor_id IS NOT NULL THEN
    SELECT m.role_code INTO actor_role FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
      WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.actor_id AND m.active AND u.active;
    IF NOT FOUND THEN RAISE EXCEPTION 'Inactive workflow actor'; END IF;
    IF NEW.action IN ('CLOSE','REOPEN') THEN
      IF actor_role NOT IN ('viewer','tadmin') OR NEW.actor_id<>parent_reporter
        OR NOT ('tickets:report'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) THEN RAISE EXCEPTION 'Customer workflow actor not authorized'; END IF;
    ELSIF actor_role NOT IN ('msp','padmin') OR NOT ('ticket_notes:write_internal'=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) THEN
      RAISE EXCEPTION 'Internal workflow actor not authorized';
    END IF;
    IF NEW.action='CANCEL' AND actor_role<>'padmin' THEN RAISE EXCEPTION 'Cancel requires current administrator'; END IF;
  END IF;
  SELECT * INTO previous FROM public.mop_ticket_workflow_event
    WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id ORDER BY version DESC LIMIT 1;
  IF NOT FOUND THEN
    IF NEW.version<>1 OR NEW.action<>'OPEN' OR NEW.snapshot->>'state'<>'OPEN' THEN RAISE EXCEPTION 'Workflow must begin at OPEN version 1'; END IF;
  ELSE
    from_state=previous.snapshot->>'state';
    IF NEW.version<>previous.version+1 OR NEW.action='OPEN' OR NEW.created_at<previous.created_at
      OR NEW.snapshot->'sla' IS DISTINCT FROM previous.snapshot->'sla'
      OR NEW.snapshot->'openedAt' IS DISTINCT FROM previous.snapshot->'openedAt'
      OR (previous.snapshot->>'acceptedAt' IS NOT NULL AND NEW.snapshot->'acceptedAt' IS DISTINCT FROM previous.snapshot->'acceptedAt') THEN
      RAISE EXCEPTION 'Workflow version or frozen clock mismatch';
    END IF;
    IF NOT ((NEW.action='ACCEPT' AND from_state='OPEN' AND to_state='ACCEPTED')
      OR (NEW.action='START' AND from_state='ACCEPTED' AND to_state='IN_PROGRESS')
      OR (NEW.action='REASSIGN' AND from_state IN ('ACCEPTED','IN_PROGRESS') AND to_state='ACCEPTED')
      OR (NEW.action='RESOLVE' AND from_state='IN_PROGRESS' AND to_state='RESOLVED')
      OR (NEW.action IN ('CLOSE','AUTO_CLOSE') AND from_state='RESOLVED' AND to_state='CLOSED')
      OR (NEW.action='REOPEN' AND from_state IN ('RESOLVED','CLOSED') AND to_state='IN_PROGRESS')
      OR (NEW.action='CANCEL' AND from_state IN ('OPEN','ACCEPTED','IN_PROGRESS') AND to_state='CANCELLED')) THEN RAISE EXCEPTION 'Workflow transition forbidden'; END IF;
    IF NEW.action IN ('START','REASSIGN','RESOLVE') AND actor_role<>'padmin' AND NEW.actor_id::text IS DISTINCT FROM previous.snapshot->>'assigneeId' THEN RAISE EXCEPTION 'Workflow actor is not assigned'; END IF;
    IF NEW.action='REOPEN' AND from_state='CLOSED' AND NEW.created_at>(previous.snapshot->>'closedAt')::timestamptz+interval '72 hours' THEN RAISE EXCEPTION 'Reopen window expired'; END IF;
    IF NEW.action='AUTO_CLOSE' AND ((previous.snapshot->>'confirmDueAt') IS NULL OR NEW.created_at<(previous.snapshot->>'confirmDueAt')::timestamptz OR NEW.snapshot->>'closeMode'<>'timeout_auto') THEN RAISE EXCEPTION 'Auto-close is not due'; END IF;
    IF NEW.action='CLOSE' AND NEW.snapshot->>'closeMode'<>'customer_confirm' THEN RAISE EXCEPTION 'Customer closure mode mismatch'; END IF;
    IF NEW.action='ACCEPT' AND (NEW.snapshot->>'assigneeId' IS DISTINCT FROM NEW.actor_id::text OR (NEW.snapshot->>'acceptedAt')::timestamptz IS DISTINCT FROM NEW.created_at) THEN RAISE EXCEPTION 'First response provenance mismatch'; END IF;
    IF NEW.action='REASSIGN' THEN
      IF NEW.snapshot->>'assigneeId' IS NOT DISTINCT FROM previous.snapshot->>'assigneeId' OR NOT EXISTS(
        SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id::text=NEW.snapshot->>'assigneeId'
        AND m.active AND u.active AND m.role_code IN ('msp','padmin') AND 'ticket_notes:write_internal'=ANY(public.mop_effective_capabilities(m.user_id,m.tenant_id))) THEN RAISE EXCEPTION 'Reassignment target not authorized'; END IF;
    END IF;
  END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_workflow_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_workflow_event FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_guard();
CREATE FUNCTION public.mop_ticket_workflow_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN RAISE EXCEPTION 'Ticket workflow facts are immutable'; END $$;
CREATE TRIGGER mop_ticket_workflow_outbox_immutable BEFORE UPDATE OR DELETE ON public.mop_ticket_workflow_outbox FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_immutable();
CREATE TRIGGER mop_ticket_workflow_clock_immutable BEFORE UPDATE OR DELETE ON public.mop_ticket_workflow_clock FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_immutable();
CREATE TRIGGER mop_ticket_workflow_receipt_immutable BEFORE UPDATE OR DELETE ON public.mop_ticket_workflow_clock_receipt FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_immutable();

REVOKE ALL ON public.mop_ticket_workflow_event,public.mop_ticket_workflow_outbox,public.mop_ticket_workflow_clock,public.mop_ticket_workflow_clock_receipt FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_workflow_event,public.mop_ticket_workflow_outbox,public.mop_ticket_workflow_clock,public.mop_ticket_workflow_clock_receipt TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_workflow_guard(),public.mop_ticket_workflow_immutable() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_workflow_guard(),public.mop_ticket_workflow_immutable() TO mop_test_runtime;

