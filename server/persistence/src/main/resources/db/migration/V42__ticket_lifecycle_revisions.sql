-- Additive D07 storage. V4 registration facts and V1--V32 remain unchanged.
-- Candidate resource pin does not mean this migration has been executed.
DO $$ BEGIN IF current_database() <> 'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;

CREATE TABLE public.mop_ticket_resolution_snapshot (
  tenant_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  lifecycle_version bigint NOT NULL CHECK(lifecycle_version BETWEEN 1 AND 9007199254740990),
  engineer_id uuid NOT NULL,
  resolved_at timestamptz(3) NOT NULL,
  document bytea NOT NULL CHECK(octet_length(document) BETWEEN 1 AND 65536),
  document_sha256 varchar(64) GENERATED ALWAYS AS (encode(sha256(document),'hex')) STORED NOT NULL,
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(tenant_id,ticket_id,lifecycle_version),
  UNIQUE(tenant_id,ticket_id,document_sha256),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,engineer_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(resolved_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);

CREATE TABLE public.mop_ticket_lifecycle_revision (
  tenant_id uuid NOT NULL,
  ticket_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  action varchar(32) NOT NULL CHECK(action IN ('CREATE','ACCEPT','TRANSFER','WAIT_FOR_VENDOR','RESUME','SUBMIT_RESOLUTION','CONFIRM','NOT_RESOLVED','AUTO_CLOSE','REOPEN')),
  state varchar(32) NOT NULL CHECK(state IN ('NEW','IN_PROGRESS','WAITING_VENDOR','PENDING_CUSTOMER','RESOLVED','CLOSED','REOPENED')),
  actor_id uuid,
  actor_permission_version bigint,
  actor_kind varchar(16) NOT NULL CHECK(actor_kind IN ('USER','WORKER')),
  occurred_at timestamptz(3) NOT NULL,
  reason text CHECK(reason IS NULL OR octet_length(reason)<=65536),
  assignee_id uuid,
  collaborator_ids uuid[] NOT NULL DEFAULT '{}' CHECK(cardinality(collaborator_ids)<=32),
  reopen_count integer NOT NULL CHECK(reopen_count>=0),
  resolved_at timestamptz(3),
  closed_at timestamptz(3),
  solution_snapshot_hash varchar(64),
  close_mode varchar(32) CHECK(close_mode IN ('CUSTOMER_CONFIRM','TIMEOUT_AUTO')),
  response_due_at timestamptz(3),
  recovery_due_at timestamptz(3),
  opening_snapshot jsonb NOT NULL CHECK(jsonb_typeof(opening_snapshot)='object' AND octet_length(opening_snapshot::text)<=1048576),
  production_approved boolean NOT NULL DEFAULT false CHECK(NOT production_approved),
  PRIMARY KEY(tenant_id,ticket_id,version),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,actor_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,assignee_id) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,ticket_id,solution_snapshot_hash) REFERENCES public.mop_ticket_resolution_snapshot(tenant_id,ticket_id,document_sha256),
  CHECK((actor_kind='USER' AND actor_id IS NOT NULL AND actor_permission_version IS NOT NULL AND actor_permission_version>0)
     OR (actor_kind='WORKER' AND actor_id IS NULL AND actor_permission_version IS NULL AND action='AUTO_CLOSE')),
  CHECK((state='CLOSED' AND closed_at IS NOT NULL AND close_mode IS NOT NULL)
     OR (state<>'CLOSED' AND closed_at IS NULL AND close_mode IS NULL)),
  CHECK(state NOT IN ('PENDING_CUSTOMER','RESOLVED','CLOSED') OR (resolved_at IS NOT NULL AND solution_snapshot_hash IS NOT NULL)),
  CHECK(resolved_at IS NULL OR resolved_at<=occurred_at),
  CHECK(closed_at IS NULL OR (resolved_at IS NOT NULL AND closed_at>=resolved_at AND closed_at<=occurred_at)),
  CHECK(occurred_at BETWEEN timestamptz '0001-01-01 00:00:00+00' AND timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE INDEX mop_ticket_lifecycle_latest ON public.mop_ticket_lifecycle_revision(tenant_id,ticket_id,version DESC);

CREATE FUNCTION public.mop_ticket_resolution_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  RAISE EXCEPTION 'Resolution snapshots are immutable';
END $$;
CREATE TRIGGER mop_ticket_resolution_immutable BEFORE UPDATE OR DELETE ON public.mop_ticket_resolution_snapshot
FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_resolution_immutable();

CREATE FUNCTION public.mop_ticket_lifecycle_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE prior public.mop_ticket_lifecycle_revision%ROWTYPE; role_name text; reporter uuid; target_state text;
BEGIN
  IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Ticket lifecycle revisions are immutable'; END IF;
  PERFORM pg_advisory_xact_lock(hashtextextended('mop.ticket.lifecycle|'||NEW.tenant_id||'|'||NEW.ticket_id,0));
  SELECT reporter_id INTO reporter FROM public.mop_ticket WHERE tenant_id=NEW.tenant_id AND id=NEW.ticket_id;
  IF NOT FOUND THEN RAISE EXCEPTION 'Ticket scope missing'; END IF;
  IF NEW.actor_kind='USER' THEN
    SELECT m.role_code INTO role_name FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id
      WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.actor_id AND m.active AND u.active
      AND m.permission_version=NEW.actor_permission_version FOR SHARE OF m,u;
    IF NOT FOUND THEN RAISE EXCEPTION 'Current ticket actor unavailable'; END IF;
    IF ((CASE WHEN NEW.action IN ('CONFIRM','NOT_RESOLVED','REOPEN') THEN 'tickets:customer_decide' ELSE 'tickets:lifecycle_write' END)=ANY(public.mop_effective_capabilities(NEW.actor_id,NEW.tenant_id))) IS NOT TRUE THEN RAISE EXCEPTION 'Lifecycle capability not granted'; END IF;
    IF NEW.action IN ('CONFIRM','NOT_RESOLVED','REOPEN') THEN
      IF role_name NOT IN ('viewer','tadmin') OR NEW.actor_id<>reporter THEN RAISE EXCEPTION 'Customer decision relationship missing'; END IF;
    ELSIF role_name NOT IN ('msp','padmin') OR NEW.action='AUTO_CLOSE' THEN RAISE EXCEPTION 'Engineer action denied'; END IF;
  END IF;
  IF NEW.assignee_id IS NOT NULL AND NOT EXISTS(SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=NEW.assignee_id AND m.active AND u.active AND m.role_code IN ('msp','padmin')) THEN RAISE EXCEPTION 'Assignee unavailable'; END IF;
  IF EXISTS(SELECT 1 FROM unnest(NEW.collaborator_ids) x WHERE x IS NULL OR NOT EXISTS(SELECT 1 FROM public.mop_membership m JOIN public.mop_user u ON u.id=m.user_id WHERE m.tenant_id=NEW.tenant_id AND m.user_id=x AND m.active AND u.active AND m.role_code IN ('msp','padmin')))
     OR cardinality(NEW.collaborator_ids)<>(SELECT count(DISTINCT x) FROM unnest(NEW.collaborator_ids) x) THEN RAISE EXCEPTION 'Collaborator scope invalid'; END IF;
  SELECT * INTO prior FROM public.mop_ticket_lifecycle_revision WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id ORDER BY version DESC LIMIT 1;
  IF NOT FOUND THEN
    IF NEW.version<>1 OR NEW.action<>'CREATE' OR NEW.state<>'NEW' OR NEW.reopen_count<>0 OR NEW.resolved_at IS NOT NULL OR NEW.solution_snapshot_hash IS NOT NULL THEN RAISE EXCEPTION 'Initial lifecycle revision invalid'; END IF;
    RETURN NEW;
  END IF;
  IF NEW.version<>prior.version+1 OR NEW.occurred_at<prior.occurred_at OR NEW.opening_snapshot IS DISTINCT FROM prior.opening_snapshot
     OR NEW.response_due_at IS DISTINCT FROM prior.response_due_at OR NEW.recovery_due_at IS DISTINCT FROM prior.recovery_due_at THEN RAISE EXCEPTION 'Lifecycle version, time or opening SLA snapshot changed'; END IF;
  target_state=CASE
    WHEN NEW.action='ACCEPT' AND prior.state='NEW' THEN 'IN_PROGRESS'
    WHEN NEW.action='TRANSFER' AND prior.state IN ('NEW','IN_PROGRESS','WAITING_VENDOR','REOPENED') THEN prior.state
    WHEN NEW.action='WAIT_FOR_VENDOR' AND prior.state IN ('IN_PROGRESS','REOPENED') THEN 'WAITING_VENDOR'
    WHEN NEW.action='RESUME' AND prior.state='WAITING_VENDOR' THEN 'IN_PROGRESS'
    WHEN NEW.action='SUBMIT_RESOLUTION' AND prior.state IN ('IN_PROGRESS','REOPENED') THEN 'PENDING_CUSTOMER'
    WHEN NEW.action IN ('CONFIRM','AUTO_CLOSE') AND prior.state='PENDING_CUSTOMER' THEN 'CLOSED'
    WHEN NEW.action='NOT_RESOLVED' AND prior.state='PENDING_CUSTOMER' THEN 'REOPENED'
    WHEN NEW.action='REOPEN' AND prior.state='CLOSED' THEN 'IN_PROGRESS'
    ELSE NULL END;
  IF target_state IS NULL OR NEW.state<>target_state THEN RAISE EXCEPTION 'Lifecycle transition rejected'; END IF;
  IF NEW.reopen_count<>prior.reopen_count+(CASE WHEN NEW.action IN ('NOT_RESOLVED','REOPEN') THEN 1 ELSE 0 END) THEN RAISE EXCEPTION 'Reopen counter mismatch'; END IF;
  IF NEW.action IN ('TRANSFER','WAIT_FOR_VENDOR','SUBMIT_RESOLUTION','NOT_RESOLVED','REOPEN') AND (NEW.reason IS NULL OR length(btrim(NEW.reason))=0) THEN RAISE EXCEPTION 'Action reason required'; END IF;
  IF NEW.action NOT IN ('ACCEPT','TRANSFER') AND (NEW.assignee_id IS DISTINCT FROM prior.assignee_id OR NEW.collaborator_ids IS DISTINCT FROM prior.collaborator_ids) THEN RAISE EXCEPTION 'Assignment changed outside assignment action'; END IF;
  IF NEW.action='ACCEPT' AND NEW.assignee_id IS DISTINCT FROM NEW.actor_id THEN RAISE EXCEPTION 'Accept must bind current engineer'; END IF;
  IF NEW.action='TRANSFER' AND NEW.assignee_id IS NULL THEN RAISE EXCEPTION 'Transfer target required'; END IF;
  IF NEW.action='SUBMIT_RESOLUTION' THEN
    IF NEW.resolved_at IS DISTINCT FROM NEW.occurred_at OR NOT EXISTS(SELECT 1 FROM public.mop_ticket_resolution_snapshot s WHERE s.tenant_id=NEW.tenant_id AND s.ticket_id=NEW.ticket_id AND s.lifecycle_version=NEW.version AND s.engineer_id=NEW.actor_id AND s.resolved_at=NEW.resolved_at AND s.document_sha256=NEW.solution_snapshot_hash) THEN RAISE EXCEPTION 'Resolution snapshot binding invalid'; END IF;
  ELSIF NEW.resolved_at IS DISTINCT FROM prior.resolved_at OR NEW.solution_snapshot_hash IS DISTINCT FROM prior.solution_snapshot_hash THEN RAISE EXCEPTION 'Resolution changed outside submission'; END IF;
  IF NEW.state='CLOSED' AND (NEW.closed_at IS DISTINCT FROM NEW.occurred_at OR NEW.close_mode IS DISTINCT FROM CASE WHEN NEW.action='CONFIRM' THEN 'CUSTOMER_CONFIRM' ELSE 'TIMEOUT_AUTO' END) THEN RAISE EXCEPTION 'Close receipt mismatch'; END IF;
  RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_lifecycle_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_lifecycle_revision
FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_lifecycle_guard();

REVOKE ALL ON public.mop_ticket_resolution_snapshot,public.mop_ticket_lifecycle_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_resolution_snapshot,public.mop_ticket_lifecycle_revision TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_resolution_immutable(),public.mop_ticket_lifecycle_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_resolution_immutable(),public.mop_ticket_lifecycle_guard() TO mop_test_runtime;

-- Expose grantable permissions only; existing built-in/custom role assignments are not expanded.
INSERT INTO public.mop_role_capability_limit(base_role,capability) VALUES
('padmin','tickets:lifecycle_write'),('msp','tickets:lifecycle_write'),
('tadmin','tickets:customer_decide'),('viewer','tickets:customer_decide');

-- Integration boundary: a registration is owned by exactly one workflow lineage.
-- Existing V33 workflow events are retained; no status conversion or data rewrite.
CREATE TABLE public.mop_ticket_workflow_authority (
 tenant_id uuid NOT NULL, ticket_id uuid NOT NULL,
 model varchar(16) NOT NULL CHECK(model IN ('WORKFLOW_V33','REVISION_V42')),
 PRIMARY KEY(tenant_id,ticket_id),
 FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id)
);
INSERT INTO public.mop_ticket_workflow_authority(tenant_id,ticket_id,model)
 SELECT DISTINCT tenant_id,ticket_id,'WORKFLOW_V33' FROM public.mop_ticket_workflow_event;
CREATE FUNCTION public.mop_ticket_workflow_authority_immutable() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
 IF TG_OP<>'INSERT' OR pg_trigger_depth()<2 THEN RAISE EXCEPTION 'Workflow authority is internal and immutable'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_workflow_authority_immutable BEFORE INSERT OR UPDATE OR DELETE ON public.mop_ticket_workflow_authority
 FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_authority_immutable();
CREATE FUNCTION public.mop_ticket_workflow_authority_claim() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE desired text; actual text;
BEGIN
 desired:=CASE TG_TABLE_NAME WHEN 'mop_ticket_workflow_event' THEN 'WORKFLOW_V33' WHEN 'mop_ticket_lifecycle_revision' THEN 'REVISION_V42' ELSE NULL END;
 IF desired IS NULL THEN RAISE EXCEPTION 'Unknown workflow authority target'; END IF;
 INSERT INTO public.mop_ticket_workflow_authority(tenant_id,ticket_id,model) VALUES(NEW.tenant_id,NEW.ticket_id,desired)
  ON CONFLICT(tenant_id,ticket_id) DO NOTHING;
 SELECT model INTO actual FROM public.mop_ticket_workflow_authority WHERE tenant_id=NEW.tenant_id AND ticket_id=NEW.ticket_id;
 IF actual IS DISTINCT FROM desired THEN RAISE EXCEPTION USING ERRCODE='P7401', MESSAGE='Ticket is owned by another workflow model'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER mop_ticket_workflow_authority_claim BEFORE INSERT ON public.mop_ticket_workflow_event
 FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_authority_claim();
CREATE TRIGGER mop_ticket_workflow_authority_claim BEFORE INSERT ON public.mop_ticket_lifecycle_revision
 FOR EACH ROW EXECUTE FUNCTION public.mop_ticket_workflow_authority_claim();
REVOKE ALL ON public.mop_ticket_workflow_authority FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_ticket_workflow_authority TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_ticket_workflow_authority_immutable(),public.mop_ticket_workflow_authority_claim() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_ticket_workflow_authority_immutable(),public.mop_ticket_workflow_authority_claim() TO mop_test_runtime;
