-- Owner-requested notification preferences only. No effective routing, receiver binding or provider dispatch.
DO $$ BEGIN
  IF current_database()<>'mop_test' OR current_user<>'mop_test_migrator' THEN
    RAISE EXCEPTION 'Own preference drafts require the dedicated test migration identity';
  END IF;
END $$;
CREATE TABLE public.mop_notification_preference_draft (
  id uuid PRIMARY KEY,
  tenant_id uuid NOT NULL REFERENCES public.mop_tenant(id),
  owner_id uuid NOT NULL,
  version bigint NOT NULL DEFAULT 1 CHECK(version BETWEEN 1 AND 9007199254740990),
  created_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  updated_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(tenant_id,owner_id),
  UNIQUE(tenant_id,id,owner_id),
  FOREIGN KEY(tenant_id,owner_id) REFERENCES public.mop_membership(tenant_id,user_id),
  CHECK(created_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK(updated_at BETWEEN created_at AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00')
);
CREATE TABLE public.mop_notification_preference_revision (
  tenant_id uuid NOT NULL,
  preference_id uuid NOT NULL,
  owner_id uuid NOT NULL,
  version bigint NOT NULL CHECK(version BETWEEN 1 AND 9007199254740990),
  previous_version bigint,
  recorded_by uuid NOT NULL CHECK(recorded_by=owner_id),
  recorded_permission_version bigint NOT NULL CHECK(recorded_permission_version BETWEEN 1 AND 9007199254740991),
  recorded_at timestamptz(3) NOT NULL DEFAULT statement_timestamp(),
  creation_xid xid8 NOT NULL DEFAULT pg_current_xact_id(),
  action varchar(16) NOT NULL CHECK(action IN ('CREATE','UPDATE')),
  requested_level varchar(16) CHECK(requested_level IN ('P1_ONLY','P1_P2','ALL')),
  requested_channels text[] CHECK(requested_channels IS NULL OR (
    COALESCE(array_ndims(requested_channels),1)=1 AND COALESCE(array_lower(requested_channels,1),1)=1
    AND cardinality(requested_channels)<=6 AND array_position(requested_channels,NULL) IS NULL
    AND requested_channels<@ARRAY['EMAIL','SMS','DINGTALK','WECHAT_WORK','WEBHOOK','VOICE']::text[])),
  quiet_window_supplied boolean NOT NULL,
  quiet_enabled boolean,
  quiet_start varchar(5),
  quiet_end varchar(5),
  content_sha256 varchar(64) NOT NULL CHECK(content_sha256 ~ '^[0-9a-f]{64}$'),
  timezone varchar(64) NOT NULL DEFAULT 'Asia/Shanghai' CHECK(timezone='Asia/Shanghai'),
  configuration_status varchar(32) NOT NULL DEFAULT 'CONFIG_PENDING' CHECK(configuration_status='CONFIG_PENDING'),
  effective boolean NOT NULL DEFAULT false CHECK(NOT effective),
  effective_level text CHECK(effective_level IS NULL),
  effective_channels text[] CHECK(effective_channels IS NULL),
  effective_quiet_window jsonb CHECK(effective_quiet_window IS NULL),
  verified_receiver_id uuid CHECK(verified_receiver_id IS NULL),
  receiver_binding_status varchar(32) NOT NULL DEFAULT 'NOT_VERIFIED' CHECK(receiver_binding_status='NOT_VERIFIED'),
  delivery_decision boolean CHECK(delivery_decision IS NULL),
  notification_dispatched boolean NOT NULL DEFAULT false CHECK(NOT notification_dispatched),
  record_source varchar(48) NOT NULL DEFAULT 'OWN_NOTIFICATION_PREFERENCE_DRAFT' CHECK(record_source='OWN_NOTIFICATION_PREFERENCE_DRAFT'),
  PRIMARY KEY(tenant_id,preference_id,version),
  FOREIGN KEY(tenant_id,preference_id,owner_id) REFERENCES public.mop_notification_preference_draft(tenant_id,id,owner_id),
  FOREIGN KEY(tenant_id,recorded_by) REFERENCES public.mop_membership(tenant_id,user_id),
  FOREIGN KEY(tenant_id,preference_id,previous_version) REFERENCES public.mop_notification_preference_revision(tenant_id,preference_id,version),
  CHECK((version=1 AND previous_version IS NULL AND action='CREATE') OR
    (version>1 AND previous_version IS NOT NULL AND previous_version=version-1 AND action='UPDATE')),
  CHECK(recorded_at BETWEEN TIMESTAMPTZ '0001-01-01 00:00:00+00' AND TIMESTAMPTZ '9999-12-31 23:59:59.999+00'),
  CHECK((NOT quiet_window_supplied AND quiet_enabled IS NULL AND quiet_start IS NULL AND quiet_end IS NULL) OR
    (quiet_window_supplied AND ((quiet_enabled IS DISTINCT FROM true AND quiet_start IS NULL AND quiet_end IS NULL) OR
      (quiet_enabled IS TRUE AND quiet_start IS NOT NULL AND quiet_end IS NOT NULL
        AND quiet_start ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$' AND quiet_end ~ '^([01][0-9]|2[0-3]):[0-5][0-9]$'
        AND quiet_start<>quiet_end))))
);
ALTER TABLE public.mop_notification_preference_draft ADD CONSTRAINT mop_notification_preference_current_revision
  FOREIGN KEY(tenant_id,id,version) REFERENCES public.mop_notification_preference_revision(tenant_id,preference_id,version) DEFERRABLE INITIALLY DEFERRED;
CREATE FUNCTION public.mop_notification_preference_head_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE observed timestamptz(3):=clock_timestamp();
BEGIN
  IF NEW.created_at>observed OR NEW.updated_at>observed THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference draft timestamp is in the future';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.version<>1 OR NEW.updated_at<>NEW.created_at THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference draft begins with one current revision';
    END IF;
  ELSIF NEW.id IS DISTINCT FROM OLD.id OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
    OR NEW.owner_id IS DISTINCT FROM OLD.owner_id OR NEW.created_at IS DISTINCT FROM OLD.created_at
    OR NEW.version<>OLD.version+1 OR NEW.updated_at<OLD.updated_at THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference owner identity and version are immutable';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_notification_preference_head_guard BEFORE INSERT OR UPDATE ON public.mop_notification_preference_draft
  FOR EACH ROW EXECUTE FUNCTION public.mop_notification_preference_head_guard();
CREATE FUNCTION public.mop_notification_preference_insert_guard() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE observed timestamptz(3):=clock_timestamp();
BEGIN
  IF NEW.creation_xid IS DISTINCT FROM pg_current_xact_id() OR NEW.recorded_at>observed THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference revision must belong to the current transaction and observed time';
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER mop_notification_preference_insert_guard BEFORE INSERT ON public.mop_notification_preference_revision
  FOR EACH ROW EXECUTE FUNCTION public.mop_notification_preference_insert_guard();
CREATE FUNCTION public.mop_notification_preference_complete() RETURNS trigger LANGUAGE plpgsql
SET search_path=pg_catalog AS $$
DECLARE
  r public.mop_notification_preference_revision%ROWTYPE;
  p public.mop_notification_preference_revision%ROWTYPE;
  h public.mop_notification_preference_draft%ROWTYPE;
  definition_bytes bytea:=''::bytea;
  field_value text;
  normalized_channels text[];
BEGIN
  SELECT * INTO STRICT r FROM public.mop_notification_preference_revision
    WHERE tenant_id=NEW.tenant_id AND preference_id=NEW.preference_id AND version=NEW.version;
  SELECT * INTO STRICT h FROM public.mop_notification_preference_draft WHERE tenant_id=r.tenant_id AND id=r.preference_id;
  IF r.version>h.version OR r.owner_id<>h.owner_id OR r.recorded_at<h.created_at
    OR (r.version=1 AND r.recorded_at<>h.created_at) OR (r.version=h.version AND r.recorded_at<>h.updated_at) THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference revision owner or head is incomplete';
  END IF;
  IF r.requested_channels IS NOT NULL THEN
    SELECT COALESCE(array_agg(value ORDER BY value COLLATE "C"),ARRAY[]::text[]) INTO normalized_channels FROM unnest(r.requested_channels) AS value;
    IF r.requested_channels<>normalized_channels OR cardinality(r.requested_channels)<>(SELECT count(DISTINCT value) FROM unnest(r.requested_channels) AS value) THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Requested channels must be unique canonical codes, not routing priority';
    END IF;
  END IF;
  FOREACH field_value IN ARRAY ARRAY['MOP_OWN_NOTIFICATION_PREFERENCE_DRAFT_V1',r.tenant_id::text,r.owner_id::text,r.requested_level] LOOP
    IF field_value IS NULL THEN definition_bytes=definition_bytes||int4send(-1);
    ELSE definition_bytes=definition_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');END IF;
  END LOOP;
  IF r.requested_channels IS NULL THEN definition_bytes=definition_bytes||int4send(-1);
  ELSE
    definition_bytes=definition_bytes||int4send(cardinality(r.requested_channels));
    FOREACH field_value IN ARRAY r.requested_channels LOOP
      definition_bytes=definition_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');
    END LOOP;
  END IF;
  IF NOT r.quiet_window_supplied THEN definition_bytes=definition_bytes||int4send(-1);
  ELSE
    definition_bytes=definition_bytes||int4send(3);
    FOREACH field_value IN ARRAY ARRAY[r.quiet_enabled::text,r.quiet_start,r.quiet_end] LOOP
      IF field_value IS NULL THEN definition_bytes=definition_bytes||int4send(-1);
      ELSE definition_bytes=definition_bytes||int4send(octet_length(convert_to(field_value,'UTF8')))||convert_to(field_value,'UTF8');END IF;
    END LOOP;
  END IF;
  definition_bytes=definition_bytes||int4send(octet_length(convert_to(r.timezone,'UTF8')))||convert_to(r.timezone,'UTF8');
  IF encode(sha256(definition_bytes),'hex')<>r.content_sha256 THEN
    RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference draft content fingerprint mismatch';
  END IF;
  IF r.version>1 THEN
    SELECT * INTO STRICT p FROM public.mop_notification_preference_revision
      WHERE tenant_id=r.tenant_id AND preference_id=r.preference_id AND version=r.previous_version;
    IF r.recorded_at<p.recorded_at THEN
      RAISE EXCEPTION USING ERRCODE='23514',MESSAGE='Preference revision time cannot move backwards';
    END IF;
  END IF;
  RETURN NULL;
END;
$$;
CREATE CONSTRAINT TRIGGER mop_notification_preference_complete AFTER INSERT ON public.mop_notification_preference_revision
  DEFERRABLE INITIALLY DEFERRED FOR EACH ROW EXECUTE FUNCTION public.mop_notification_preference_complete();
REVOKE ALL ON FUNCTION public.mop_notification_preference_head_guard(),public.mop_notification_preference_insert_guard(),public.mop_notification_preference_complete() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION public.mop_notification_preference_head_guard(),public.mop_notification_preference_insert_guard(),public.mop_notification_preference_complete() TO mop_test_runtime;
REVOKE ALL ON public.mop_notification_preference_draft,public.mop_notification_preference_revision FROM PUBLIC,mop_test_runtime;
GRANT SELECT,INSERT ON public.mop_notification_preference_draft,public.mop_notification_preference_revision TO mop_test_runtime;
GRANT UPDATE(version,updated_at) ON public.mop_notification_preference_draft TO mop_test_runtime;
-- No runtime rewriting/deleting history, editing another owner, receiver grants or notification activation.
