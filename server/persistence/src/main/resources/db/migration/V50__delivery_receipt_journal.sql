-- Explicit test-only receipt history. No dispatch, authenticated-provider assertion or retry policy.
DO $$ BEGIN IF current_database()<>'mop_test' THEN RAISE EXCEPTION 'Dedicated MOP test database required'; END IF; END $$;
CREATE TABLE public.mop_delivery_receipt_journal (
 tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
 delivery_id uuid NOT NULL,
 version bigint NOT NULL CHECK(version BETWEEN 0 AND 12000),
 root_version bigint NOT NULL DEFAULT 0 CHECK(root_version=0),
 resource_pool_id uuid NOT NULL,
 event_id uuid NOT NULL,
 channel varchar(16) NOT NULL CHECK(channel IN ('EMAIL','SMS','DINGTALK','WECHAT_WORK','WEBHOOK','VOICE')),
 receiver_id uuid NOT NULL,
 stage varchar(64) NOT NULL CHECK(stage ~ '^[A-Z][A-Z0-9_]{0,63}$'),
 command_id uuid NOT NULL,
 actor_id uuid NOT NULL,
 permission_version bigint NOT NULL CHECK(permission_version BETWEEN 1 AND 9007199254740991),
 trace_id varchar(128) NOT NULL CHECK(length(trace_id)>0 AND trace_id !~ '[^A-Za-z0-9._:-]'),
 expected_version bigint NOT NULL CHECK(expected_version=version-1),
 previous_sha256 varchar(64),
 encoded_record bytea NOT NULL CHECK(octet_length(encoded_record) BETWEEN 1 AND 1024),
 record_sha256 varchar(64) NOT NULL CHECK(record_sha256 ~ '^[0-9a-f]{64}$'),
 command_sha256 varchar(64) NOT NULL CHECK(command_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz(3) NOT NULL,
 record_source varchar(40) NOT NULL DEFAULT 'INTERNAL_DELIVERY_RECEIPT_JOURNAL' CHECK(record_source='INTERNAL_DELIVERY_RECEIPT_JOURNAL'),
 provider_receipt_verified boolean NOT NULL DEFAULT false CHECK(NOT provider_receipt_verified),
 automatic_send_enabled boolean NOT NULL DEFAULT false CHECK(NOT automatic_send_enabled),
 PRIMARY KEY(tenant_id,delivery_id,version),
 UNIQUE(tenant_id,command_id),
 FOREIGN KEY(tenant_id,delivery_id,root_version) REFERENCES public.mop_delivery_receipt_journal(tenant_id,delivery_id,version),
 CHECK((version=0 AND previous_sha256 IS NULL) OR (version>0 AND previous_sha256 IS NOT NULL AND previous_sha256 ~ '^[0-9a-f]{64}$')),
 CHECK(recorded_at>=timestamptz '0001-01-01 00:00:00+00' AND recorded_at<=timestamptz '9999-12-31 23:59:59.999+00')
);
CREATE UNIQUE INDEX mop_delivery_receipt_dedup ON public.mop_delivery_receipt_journal(tenant_id,resource_pool_id,event_id,channel,receiver_id,stage) WHERE version=0;
CREATE FUNCTION public.mop_delivery_receipt_journal_guard() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$
DECLARE root public.mop_delivery_receipt_journal%ROWTYPE; prior public.mop_delivery_receipt_journal%ROWTYPE;
BEGIN
 IF TG_OP<>'INSERT' THEN RAISE EXCEPTION 'Delivery receipt journal immutable' USING ERRCODE='23514'; END IF;
 IF NEW.recorded_at IS NOT NULL THEN RAISE EXCEPTION 'Caller recording time forbidden' USING ERRCODE='23514'; END IF;
 IF NEW.version>0 THEN
  SELECT * INTO root FROM public.mop_delivery_receipt_journal WHERE tenant_id=NEW.tenant_id AND delivery_id=NEW.delivery_id AND version=0 FOR UPDATE;
  IF root.delivery_id IS NULL THEN RAISE EXCEPTION 'Original delivery root required' USING ERRCODE='23503'; END IF;
  IF ROW(NEW.resource_pool_id,NEW.event_id,NEW.channel,NEW.receiver_id,NEW.stage) IS DISTINCT FROM ROW(root.resource_pool_id,root.event_id,root.channel,root.receiver_id,root.stage) THEN RAISE EXCEPTION 'Original delivery scope required' USING ERRCODE='23514'; END IF;
  SELECT * INTO prior FROM public.mop_delivery_receipt_journal WHERE tenant_id=NEW.tenant_id AND delivery_id=NEW.delivery_id ORDER BY version DESC LIMIT 1;
  IF NEW.version IS DISTINCT FROM prior.version+1 OR NEW.previous_sha256 IS DISTINCT FROM prior.record_sha256 THEN RAISE EXCEPTION 'Contiguous original journal chain required' USING ERRCODE='23514'; END IF;
 END IF;
 NEW.recorded_at=date_trunc('milliseconds',transaction_timestamp());
 RETURN NEW;
END $$;
CREATE TRIGGER mop_delivery_receipt_journal_guard BEFORE INSERT OR UPDATE OR DELETE ON public.mop_delivery_receipt_journal FOR EACH ROW EXECUTE FUNCTION public.mop_delivery_receipt_journal_guard();
REVOKE ALL ON public.mop_delivery_receipt_journal FROM PUBLIC;
GRANT SELECT,INSERT ON public.mop_delivery_receipt_journal TO mop_test_runtime;
-- Row-lock capability only: every UPDATE is rejected by the immutable trigger.
GRANT UPDATE(root_version) ON public.mop_delivery_receipt_journal TO mop_test_runtime;
REVOKE ALL ON FUNCTION public.mop_delivery_receipt_journal_guard() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_delivery_receipt_journal_guard() TO mop_test_runtime;
COMMENT ON TABLE public.mop_delivery_receipt_journal IS 'Immutable bounded queue root and single-transition commands. Root row serializes appends. No provider authentication, dispatch permission, external outbox or automatic retry policy. Full scope and domain replay must be verified by application on every load.';
