-- Explicit test-only storage foundation. No authenticated intake, inbox claim, incident or notification.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Raw event storage requires dedicated test database and migration role';
  END IF;
END $$;
CREATE FUNCTION public.mop_raw_event_fingerprint(pool_id uuid, source_id text, phase text, tenant_id uuid, asset_id uuid, source_seconds bigint, source_nanos integer, payload bytea)
RETURNS text LANGUAGE plpgsql IMMUTABLE STRICT SET search_path=pg_catalog AS $$
DECLARE result bytea := ''::bytea; item text; bytes bytea;
BEGIN
  FOREACH item IN ARRAY ARRAY['MOP_RAW_MONITORING_EVENT_V1',pool_id::text,source_id,phase,tenant_id::text,asset_id::text] LOOP
    bytes := convert_to(item,'UTF8'); result := result || int4send(octet_length(bytes)) || bytes;
  END LOOP;
  result := result || int8send(source_seconds) || int4send(source_nanos);
  bytes := convert_to(encode(sha256(payload),'hex'),'UTF8');
  RETURN encode(sha256(result || int4send(octet_length(bytes)) || bytes),'hex');
END;
$$;
REVOKE ALL ON FUNCTION public.mop_raw_event_fingerprint(uuid,text,text,uuid,uuid,bigint,integer,bytea) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_raw_event_fingerprint(uuid,text,text,uuid,uuid,bigint,integer,bytea) TO mop_test_runtime;
CREATE TABLE public.mop_raw_monitoring_event (
  id uuid PRIMARY KEY,
  pool_id uuid NOT NULL,
  source_event_id text NOT NULL CHECK(char_length(source_event_id) BETWEEN 1 AND 256 AND char_length(source_event_id)+regexp_count(source_event_id,U&'[\+010000-\+10FFFF]')<=256),
  source_phase varchar(16) NOT NULL CHECK(source_phase IN ('PROBLEM','RECOVERED')),
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  asset_id uuid NOT NULL,
  occurred_epoch_second bigint NOT NULL CHECK(occurred_epoch_second BETWEEN -31557014167219200 AND 31556889864403199),
  occurred_nano integer NOT NULL CHECK(occurred_nano BETWEEN 0 AND 999999999),
  received_epoch_second bigint NOT NULL CHECK(received_epoch_second BETWEEN -31557014167219200 AND 31556889864403199),
  received_nano integer NOT NULL CHECK(received_nano BETWEEN 0 AND 999999999),
  payload bytea NOT NULL CHECK(octet_length(payload) BETWEEN 1 AND 1048576),
  payload_sha256 text GENERATED ALWAYS AS (encode(sha256(payload),'hex')) STORED NOT NULL,
  content_fingerprint text GENERATED ALWAYS AS (public.mop_raw_event_fingerprint(pool_id,source_event_id,source_phase,tenant_id,asset_id,occurred_epoch_second,occurred_nano,payload)) STORED NOT NULL,
  source_authorization_status varchar(16) NOT NULL DEFAULT 'UNVERIFIED' CHECK(source_authorization_status='UNVERIFIED'),
  storage_status varchar(16) NOT NULL DEFAULT 'STORED_ONLY' CHECK(storage_status='STORED_ONLY'),
  UNIQUE(pool_id,source_event_id,source_phase),
  FOREIGN KEY(tenant_id,asset_id) REFERENCES public.mop_asset(tenant_id,id),
  CHECK(source_event_id !~ U&'[\0001-\001F\007F-\009F]'),
  CHECK(source_event_id !~ U&'^[\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]|[\0009-\000D\001C-\0020\1680\2000-\2006\2008-\200A\2028\2029\205F\3000]$')
);
REVOKE ALL ON public.mop_raw_monitoring_event FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_raw_monitoring_event TO mop_test_runtime;
-- No runtime UPDATE/DELETE/TRUNCATE. Retention requires a separately approved policy.
-- Binding ownership FK is not monitoring-source authentication or current mapping authorization.
