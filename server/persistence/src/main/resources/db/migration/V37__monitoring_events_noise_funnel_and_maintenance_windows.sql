-- V37: D15 Monitoring Event Center, Deduplication (xN), Flap Silence, Dependency Suppression,
-- Maintenance Windows, Quarantine Manual Decisions, and Auto-Ticket Attempts (M1-2, M4-7..M4-11, M4-14, M5-4).
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'MOP monitoring event center migration requires dedicated mop_test database and mop_test_migrator role';
  END IF;
END $$;

CREATE TABLE public.mop_monitoring_event (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  asset_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version>=1),
  rule_code varchar(64) NOT NULL CHECK(char_length(btrim(rule_code)) BETWEEN 1 AND 64),
  rule_name varchar(128) NOT NULL CHECK(char_length(btrim(rule_name)) BETWEEN 1 AND 128),
  severity varchar(16) NOT NULL CHECK(severity IN ('INFO','WARNING','AVERAGE','HIGH','DISASTER')),
  severity_rank integer NOT NULL CHECK(severity_rank BETWEEN 1 AND 5),
  fingerprint varchar(160) NOT NULL CHECK(char_length(btrim(fingerprint)) BETWEEN 5 AND 160),
  occurrence_count integer NOT NULL CHECK(occurrence_count>=1),
  flap_flip_count integer NOT NULL DEFAULT 0 CHECK(flap_flip_count>=0),
  flap_silenced_until timestamptz(3),
  monitor_state varchar(16) NOT NULL CHECK(monitor_state IN ('PROBLEM','RECOVERED')),
  handling_state varchar(24) NOT NULL CHECK(handling_state IN ('NEW','ACKNOWLEDGED','IN_PROGRESS','PENDING_REVIEW','RESOLVED','CLOSED')),
  suppression_state varchar(24) NOT NULL CHECK(suppression_state IN ('NONE','FLAP_SILENCED','DEPENDENCY_SUPPRESSED','MAINTENANCE_SILENCED','INFO_DEFAULT_SILENT')),
  suppression_reason varchar(256) CHECK(suppression_reason IS NULL OR char_length(btrim(suppression_reason)) BETWEEN 1 AND 256),
  notification_empty_state varchar(64) NOT NULL CHECK(notification_empty_state IN ('NORMAL_DISPATCH_ELIGIBLE','DEPENDENCY_SUPPRESSED_NO_NOTIFY','MAINTENANCE_WINDOW_SUPPRESSED','FLAP_SILENCED_NO_NOTIFY','INFO_LEVEL_NO_NOTIFY')),
  sla_unknown_marked boolean NOT NULL DEFAULT false,
  review_policy_status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING_Q29' CHECK(review_policy_status IN ('CONFIG_PENDING_Q29','PUBLISHED_Q29')),
  problem_at timestamptz(3) NOT NULL,
  last_occurred_at timestamptz(3) NOT NULL,
  recovered_at timestamptz(3),
  first_ack_at timestamptz(3),
  resolved_at timestamptz(3),
  closed_at timestamptz(3),
  root_cause_note varchar(500) CHECK(root_cause_note IS NULL OR char_length(btrim(root_cause_note)) BETWEEN 1 AND 500),
  ticket_id uuid,
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,id),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE INDEX mop_monitoring_event_tenant_fp_idx
  ON public.mop_monitoring_event(tenant_id,fingerprint,last_occurred_at DESC);

CREATE TABLE public.mop_monitoring_event_member (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  event_id uuid NOT NULL,
  raw_event_id uuid REFERENCES public.mop_raw_monitoring_event(id),
  sequence_no integer NOT NULL CHECK(sequence_no>=1),
  source_event_id varchar(256) NOT NULL CHECK(char_length(btrim(source_event_id)) BETWEEN 1 AND 256),
  source_phase varchar(16) NOT NULL CHECK(source_phase IN ('PROBLEM','RECOVERED')),
  occurred_at timestamptz(3) NOT NULL,
  merged_within_window boolean NOT NULL,
  recorded_at timestamptz(3) NOT NULL,
  UNIQUE(event_id,sequence_no),
  FOREIGN KEY(tenant_id,event_id) REFERENCES public.mop_monitoring_event(tenant_id,id)
);

CREATE TABLE public.mop_event_noise_record (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL,
  event_id uuid NOT NULL,
  layer varchar(16) NOT NULL CHECK(layer IN ('RAW_INTAKE','MERGE','FLAP','DEPENDENCY','MAINTENANCE','EFFECTIVE_ALERT')),
  detail varchar(500) NOT NULL CHECK(char_length(btrim(detail)) BETWEEN 1 AND 500),
  recorded_at timestamptz(3) NOT NULL,
  FOREIGN KEY(tenant_id,event_id) REFERENCES public.mop_monitoring_event(tenant_id,id)
);

CREATE INDEX mop_event_noise_record_tenant_time_idx
  ON public.mop_event_noise_record(tenant_id,recorded_at DESC,id DESC);

CREATE TABLE public.mop_maintenance_window (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  version bigint NOT NULL CHECK(version>=1),
  name varchar(128) NOT NULL CHECK(char_length(btrim(name)) BETWEEN 1 AND 128),
  asset_id uuid NOT NULL,
  starts_at timestamptz(3) NOT NULL,
  ends_at timestamptz(3) NOT NULL,
  silence_mode varchar(24) NOT NULL CHECK(silence_mode IN ('SUPPRESS_NOTIFY','PAUSE_COLLECTION')),
  status varchar(16) NOT NULL CHECK(status IN ('SCHEDULED','ACTIVE','ENDED','CANCELLED')),
  sla_exclusion_requested boolean NOT NULL DEFAULT false,
  sla_exclusion_confirmed boolean NOT NULL DEFAULT false,
  sla_exclusion_confirmed_by uuid,
  sla_exclusion_confirmed_at timestamptz(3),
  reason varchar(500) NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 1 AND 500),
  created_by uuid NOT NULL,
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,id),
  CHECK(ends_at>starts_at),
  CHECK((NOT sla_exclusion_confirmed) OR (sla_exclusion_requested AND sla_exclusion_confirmed_by IS NOT NULL AND sla_exclusion_confirmed_at IS NOT NULL)),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  FOREIGN KEY(tenant_id,created_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE INDEX mop_maintenance_window_tenant_asset_idx
  ON public.mop_maintenance_window(tenant_id,asset_id,starts_at DESC);

CREATE TABLE public.mop_monitoring_quarantine_decision (
  quarantine_id uuid PRIMARY KEY REFERENCES public.mop_monitoring_quarantine(id),
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  decision varchar(16) NOT NULL CHECK(decision IN ('BIND_ASSET','DISCARD')),
  bound_asset_id uuid,
  result_event_id uuid,
  reason varchar(500) NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 1 AND 500),
  decided_by uuid NOT NULL,
  decided_at timestamptz(3) NOT NULL,
  CHECK((decision='DISCARD' AND bound_asset_id IS NULL AND result_event_id IS NULL) OR (decision='BIND_ASSET' AND bound_asset_id IS NOT NULL)),
  FOREIGN KEY(tenant_id,decided_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE TABLE public.mop_autoticket_attempt (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  event_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version>=1),
  attempt_count integer NOT NULL CHECK(attempt_count BETWEEN 1 AND 6),
  max_retries integer NOT NULL DEFAULT 5 CHECK(max_retries=5),
  backoff_schedule_sec varchar(32) NOT NULL DEFAULT '30,120,600,600,600',
  status varchar(32) NOT NULL CHECK(status IN ('SUCCEEDED','RETRY_SCHEDULED','QUARANTINED_MANUAL_REQUIRED','MANUAL_CREATED','SUPPRESSED_SKIPPED')),
  mapped_priority varchar(4) CHECK(mapped_priority IS NULL OR mapped_priority IN ('P1','P2','P3')),
  mapping_policy_status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING_Q14' CHECK(mapping_policy_status IN ('CONFIG_PENDING_Q14','PUBLISHED_Q14')),
  ticket_id uuid,
  next_retry_at timestamptz(3),
  last_error varchar(500) CHECK(last_error IS NULL OR char_length(btrim(last_error)) BETWEEN 1 AND 500),
  updated_by uuid NOT NULL,
  updated_at timestamptz(3) NOT NULL,
  UNIQUE(tenant_id,event_id),
  FOREIGN KEY(tenant_id,event_id) REFERENCES public.mop_monitoring_event(tenant_id,id),
  FOREIGN KEY(tenant_id,ticket_id) REFERENCES public.mop_ticket(tenant_id,id),
  FOREIGN KEY(tenant_id,updated_by) REFERENCES public.mop_membership(tenant_id,user_id)
);

CREATE FUNCTION public.mop_monitoring_event_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'mop_monitoring_event is immutable against DELETE';
  END IF;
  IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.asset_id<>OLD.asset_id OR NEW.fingerprint<>OLD.fingerprint OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'mop_monitoring_event update must preserve identity and increment version by 1';
  END IF;
  IF OLD.handling_state='CLOSED' AND NEW.handling_state<>'CLOSED' THEN
    RAISE EXCEPTION 'Closed monitoring event cannot transition back to active states';
  END IF;
  IF NEW.handling_state='RESOLVED' AND (OLD.handling_state<>'PENDING_REVIEW' OR NEW.root_cause_note IS NULL) THEN
    RAISE EXCEPTION 'Monitoring event requires PENDING_REVIEW and root_cause_note before RESOLVED (M4-11)';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER mop_monitoring_event_guard BEFORE UPDATE OR DELETE ON public.mop_monitoring_event
  FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_event_guard();

CREATE FUNCTION public.mop_monitoring_event_append_only_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  RAISE EXCEPTION '% is append-only and cannot be updated or deleted', TG_TABLE_NAME;
END $$;

CREATE TRIGGER mop_monitoring_event_member_guard BEFORE UPDATE OR DELETE ON public.mop_monitoring_event_member
  FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_event_append_only_guard();

CREATE TRIGGER mop_event_noise_record_guard BEFORE UPDATE OR DELETE ON public.mop_event_noise_record
  FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_event_append_only_guard();

CREATE TRIGGER mop_monitoring_quarantine_decision_guard BEFORE UPDATE OR DELETE ON public.mop_monitoring_quarantine_decision
  FOR EACH ROW EXECUTE FUNCTION public.mop_monitoring_event_append_only_guard();

CREATE FUNCTION public.mop_maintenance_window_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'mop_maintenance_window is immutable against DELETE';
  END IF;
  IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.asset_id<>OLD.asset_id OR NEW.created_by<>OLD.created_by OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'mop_maintenance_window update must preserve identity and increment version by 1';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER mop_maintenance_window_guard BEFORE UPDATE OR DELETE ON public.mop_maintenance_window
  FOR EACH ROW EXECUTE FUNCTION public.mop_maintenance_window_guard();

CREATE FUNCTION public.mop_autoticket_attempt_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'mop_autoticket_attempt is immutable against DELETE';
  END IF;
  IF NEW.id<>OLD.id OR NEW.tenant_id<>OLD.tenant_id OR NEW.event_id<>OLD.event_id OR NEW.version<>OLD.version+1 THEN
    RAISE EXCEPTION 'mop_autoticket_attempt update must preserve identity and increment version by 1';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER mop_autoticket_attempt_guard BEFORE UPDATE OR DELETE ON public.mop_autoticket_attempt
  FOR EACH ROW EXECUTE FUNCTION public.mop_autoticket_attempt_guard();

REVOKE ALL ON public.mop_monitoring_event,public.mop_monitoring_event_member,public.mop_event_noise_record,public.mop_maintenance_window,public.mop_monitoring_quarantine_decision,public.mop_autoticket_attempt FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_monitoring_event,public.mop_monitoring_event_member,public.mop_event_noise_record,public.mop_maintenance_window,public.mop_monitoring_quarantine_decision,public.mop_autoticket_attempt TO mop_test_runtime;
GRANT UPDATE(version,occurrence_count,flap_flip_count,flap_silenced_until,monitor_state,handling_state,suppression_state,suppression_reason,notification_empty_state,sla_unknown_marked,last_occurred_at,recovered_at,first_ack_at,resolved_at,closed_at,root_cause_note,ticket_id,updated_by,updated_at) ON public.mop_monitoring_event TO mop_test_runtime;
GRANT UPDATE(version,ends_at,status,sla_exclusion_confirmed,sla_exclusion_confirmed_by,sla_exclusion_confirmed_at,updated_by,updated_at) ON public.mop_maintenance_window TO mop_test_runtime;
GRANT UPDATE(version,attempt_count,status,ticket_id,next_retry_at,last_error,updated_by,updated_at) ON public.mop_autoticket_attempt TO mop_test_runtime;
