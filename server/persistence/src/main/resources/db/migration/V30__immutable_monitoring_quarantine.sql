-- Explicit test-only held candidates. Claims are unverified data, never business ownership or dispatch authority.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Monitoring quarantine requires dedicated test database and migration role';
  END IF;
END $$;
CREATE FUNCTION public.mop_quarantine_fingerprint(pool_id uuid, source_id text, phase text, claimed_tenant uuid, claimed_asset uuid, reason text, source_seconds bigint, source_nanos integer, payload bytea)
RETURNS text LANGUAGE plpgsql IMMUTABLE SET search_path=pg_catalog AS $$
DECLARE result bytea := ''::bytea; item text; bytes bytea;
BEGIN
  FOREACH item IN ARRAY ARRAY['MOP_MONITORING_QUARANTINE_V1',pool_id::text,source_id,phase,claimed_tenant::text,claimed_asset::text,reason] LOOP
    IF item IS NULL THEN result := result || int4send(-1);
    ELSE bytes := convert_to(item,'UTF8'); result := result || int4send(octet_length(bytes)) || bytes;
    END IF;
  END LOOP;
  result := result || int8send(source_seconds) || int4send(source_nanos);
  bytes := convert_to(encode(sha256(payload),'hex'),'UTF8');
  RETURN encode(sha256(result || int4send(octet_length(bytes)) || bytes),'hex');
END;
$$;
REVOKE ALL ON FUNCTION public.mop_quarantine_fingerprint(uuid,text,text,uuid,uuid,text,bigint,integer,bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_quarantine_fingerprint(uuid,text,text,uuid,uuid,text,bigint,integer,bytea) TO mop_test_runtime;
CREATE TABLE public.mop_monitoring_quarantine (
  id uuid PRIMARY KEY,
  pool_id uuid NOT NULL,
  source_event_id text NOT NULL CHECK(char_length(source_event_id) BETWEEN 1 AND 256 AND char_length(source_event_id)+regexp_count(source_event_id,U&'[\+010000-\+10FFFF]')<=256),
  source_phase varchar(16) NOT NULL CHECK(source_phase IN ('PROBLEM','RECOVERED')),
  claimed_tenant_id uuid,
  claimed_asset_id uuid,
  quarantine_reason varchar(32) NOT NULL CHECK(quarantine_reason IN ('UNKNOWN_MAPPING','CROSS_TENANT_CLAIM','SOURCE_KEY_CONFLICT')),
  occurred_epoch_second bigint NOT NULL CHECK(occurred_epoch_second BETWEEN -31557014167219200 AND 31556889864403199),
  occurred_nano integer NOT NULL CHECK(occurred_nano BETWEEN 0 AND 999999999),
  received_epoch_second bigint NOT NULL CHECK(received_epoch_second BETWEEN -31557014167219200 AND 31556889864403199),
  received_nano integer NOT NULL CHECK(received_nano BETWEEN 0 AND 999999999),
  payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 1048576),
  payload_sha256 text GENERATED ALWAYS AS (encode(sha256(payload),'hex')) STORED NOT NULL,
  candidate_fingerprint text GENERATED ALWAYS AS (public.mop_quarantine_fingerprint(pool_id,source_event_id,source_phase,claimed_tenant_id,claimed_asset_id,quarantine_reason,occurred_epoch_second,occurred_nano,payload)) STORED NOT NULL,
  quarantine_status varchar(32) NOT NULL DEFAULT 'HELD_UNVERIFIED' CHECK(quarantine_status='HELD_UNVERIFIED'),
  dispatch_allowed boolean NOT NULL DEFAULT false CHECK(NOT dispatch_allowed),
  source_authorized boolean NOT NULL DEFAULT false CHECK(NOT source_authorized),
  UNIQUE(candidate_fingerprint),
  CHECK(source_event_id !~ U&'[\0001-\001F\007F-\009F]'),
  CHECK(source_event_id !~ U&'^[\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]|[\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]$')
);
REVOKE ALL ON public.mop_monitoring_quarantine FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_monitoring_quarantine TO mop_test_runtime;
-- No claim-based FK: unknown and cross-tenant claims must be retained without becoming authorized bindings.
-- No automatic promotion, acknowledgement to monitoring source, processing, cleanup or notification.
