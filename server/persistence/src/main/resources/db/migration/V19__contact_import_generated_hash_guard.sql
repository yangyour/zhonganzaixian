-- V18 is frozen. Generated columns are computed after BEFORE triggers. Compare immutable base fields only.
-- PostgreSQL 17 reference: https://www.postgresql.org/docs/17/ddl-generated-columns.html
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Contact import guard fix requires dedicated test database and migration role';
  END IF;
END $$;
CREATE OR REPLACE FUNCTION public.mop_contact_import_transition_guard() RETURNS trigger
LANGUAGE plpgsql SECURITY INVOKER SET search_path=pg_catalog AS $$
DECLARE observed timestamptz(3):=clock_timestamp();
BEGIN
  IF TG_OP='INSERT' THEN
    IF NEW.state<>'PENDING' OR NEW.attempts<>0 OR NEW.fencing_token<>0 OR NEW.version<>1 THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import acceptance must begin queued';
    END IF;
    RETURN NEW;
  END IF;
  IF OLD.state IN ('SUCCEEDED','FAILED') OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import terminal/version transition rejected';
  END IF;
  IF ROW(NEW.id,NEW.tenant_id,NEW.created_by,NEW.permission_version,NEW.row_count,NEW.payload_bytes,NEW.source_format,NEW.trace_id,NEW.created_at)
    IS DISTINCT FROM ROW(OLD.id,OLD.tenant_id,OLD.created_by,OLD.permission_version,OLD.row_count,OLD.payload_bytes,OLD.source_format,OLD.trace_id,OLD.created_at) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import immutable acceptance changed';
  END IF;
  IF NEW.state='LEASED' THEN
    IF NEW.attempts<>OLD.attempts+1 OR NEW.fencing_token<>OLD.fencing_token+1
      OR NEW.lease_started_at>observed
      OR (OLD.state='PENDING' AND (OLD.next_at>observed OR NEW.lease_started_at<OLD.next_at))
      OR (OLD.state='LEASED' AND (OLD.lease_until>observed OR NEW.lease_started_at<OLD.lease_until)) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import claim is early or unfenced';
    END IF;
  ELSE
    IF OLD.state<>'LEASED' OR observed<OLD.lease_started_at OR observed>=OLD.lease_until
      OR NEW.attempts<>OLD.attempts OR NEW.fencing_token<>OLD.fencing_token
      OR (NEW.state='PENDING' AND NEW.next_at<=observed) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Contact import completion or retry lease invalid';
    END IF;
  END IF;
  RETURN NEW;
END;
$$;
