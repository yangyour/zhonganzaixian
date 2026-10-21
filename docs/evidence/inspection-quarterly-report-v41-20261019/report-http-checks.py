"""Real HTTP checks for the disposable V41 quarterly-report environment.

The caller owns service startup/cleanup and passes ephemeral credentials in memory.
This module never writes credentials, starts services, or changes repository files.
"""

import collections
import hashlib
import http.cookiejar
import ipaddress
import json
import struct
import urllib.error
import urllib.parse
import urllib.request
import uuid

EXPECTED_HTTP_CHECKS = 90


def run(origin, users, report):
    parsed = urllib.parse.urlsplit(origin)
    assert parsed.scheme == "http" and ipaddress.ip_address(parsed.hostname).is_loopback
    assert not parsed.username and not parsed.password and not parsed.query and not parsed.fragment
    assert parsed.path in ("", "/")
    origin = origin.rstrip("/")
    start = len(report.setdefault("checks", []))
    cases = set()

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, req, fp, code, msg, headers, newurl):
            return None

    class Client:
        def __init__(self, name):
            self.name = name
            self.http = urllib.request.build_opener(
                urllib.request.ProxyHandler({}), NoRedirect(),
                urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
            self.csrf = None

        def call(self, case, path, method="GET", body=None, status=200,
                 key=None, send_csrf=True, send_key=True, raw=None, error=None):
            assert case not in cases, ("duplicate_check_name", case)
            cases.add(case)
            headers = {"Accept": "application/json", "Origin": origin}
            payload = None
            if body is not None or raw is not None:
                headers["Content-Type"] = "application/json"
                if send_key:
                    headers["Idempotency-Key"] = key or str(uuid.uuid4())
                payload = raw.encode("utf-8") if raw is not None else json.dumps(body).encode("utf-8")
            if self.csrf and send_csrf:
                headers["X-CSRF-Token"] = self.csrf
            request = urllib.request.Request(origin + "/api/v1" + path,
                                             data=payload, headers=headers, method=method)
            try:
                response = self.http.open(request, timeout=20)
            except urllib.error.HTTPError as failure:
                response = failure
            with response:
                actual_status = response.status
                content = response.read()
            try:
                value = json.loads(content)
            except (ValueError, UnicodeError):
                raise AssertionError((case, "response_not_json", actual_status)) from None
            assert isinstance(value, dict), (case, "response_not_object")
            actual_error = (value.get("error") or {}).get("code")
            report["checks"].append({"case": case, "actor": self.name,
                                     "path": path, "method": method,
                                     "status": actual_status, "expected_status": status,
                                     "error_code": actual_error})
            assert actual_status == status, (case, actual_status, status, actual_error)
            if error is not None:
                assert actual_error == error, (case, actual_error, error)
            if status < 400:
                assert "data" in value, (case, "missing_data")
                return value["data"]
            return value.get("error")

        def login(self, user):
            self.csrf = self.call(self.name + "_csrf", "/auth/csrf")["csrf_token"]
            session = self.call(self.name + "_login", "/auth/local/login", "POST",
                                {"username": user["username"], "password": user["password"]})
            self.csrf = session["csrf_token"]
            assert session["identity_source"] == "project_local"
            return session

        def switch(self, case, tenant):
            value = self.call(case, "/session/tenant", "POST", {"tenant_id": tenant})
            self.csrf = value["csrf_token"]
            assert value["tenant"]["id"] == tenant
            return value

    route = "/inspection-quarterly-reports"
    source_route = "/inspection-source-collections"
    marker = "DOT_DISPOSABLE_SYNTHETIC_HTTP_QUARTERLY"
    config_keys = ("revision", "time_zone", "duplicate_rule", "archived_rule",
                   "metric_version", "report_template_version")
    pending_config = dict.fromkeys(config_keys)

    def encoded(value):
        return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")

    def field(value):
        if value is None:
            return struct.pack(">i", -1)
        data = value.encode("utf-8")
        return struct.pack(">i", len(data)) + data

    def assert_summary(value, tenant):
        assert value["tenant_id"] == tenant and value["version"] == 1
        assert value["generation_status"] == "DRAFT"
        assert value["configuration_source"] == "REQUEST_DRAFT_NOT_APPROVED"
        assert value["source"] == "postgresql" and value["scope"] == "CURRENT_TENANT_PADMIN"
        for flag in ("report_publishable", "report_published", "evidence_access_granted"):
            assert value[flag] is False
        assert value["tenant_asset_population"] is None
        for digest in ("source_document_sha256", "source_manifest_sha256", "report_sha256", "document_sha256"):
            assert len(value[digest]) == 64 and all(c in "0123456789abcdef" for c in value[digest])

    def assert_detail(value, expected, configuration):
        assert_summary(value, expected["tenant_id"])
        for key in expected:
            if key != "projection":
                assert value[key] == expected[key], ("detail_metadata_mismatch", key)
        assert value["projection"] == "FROZEN_INPUT_OUTPUT"
        assert value["document_integrity"] == "VERIFIED_SERVER_SIDE"
        document = value["frozen_document"]
        assert document["format"] == "MOP_INSPECTION_QUARTERLY_DOCUMENT_V1"
        assert document["configuration_source"] == "REQUEST_DRAFT_NOT_APPROVED"
        assert document["tenant_id"] == expected["tenant_id"]
        assert document["source_collection_id"] == expected["source_collection_id"]
        assert document["year"] == expected["year"] and document["quarter"] == expected["quarter"]
        assert document["completeness"] == "COMPLETE_EXPLICIT_SELECTION"
        camel = ("revision", "timeZone", "duplicateRule", "archivedRule", "metricVersion", "reportTemplateVersion")
        assert document["configuration"] == dict(zip(camel, (configuration[k] for k in config_keys)))
        source = document["source_document"]
        assert source["sources"] == [] and source["tenant_id"] == expected["tenant_id"]
        manifest_bytes = (field("MOP_INSPECTION_SOURCE_MANIFEST_V1")
                          + field(expected["tenant_id"]) + struct.pack(">i", 0))
        manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
        assert source["manifest_sha256"] == expected["source_manifest_sha256"] == manifest_hash
        assert hashlib.sha256(encoded(source)).hexdigest() == expected["source_document_sha256"]
        report_bytes = (field("MOP_INSPECTION_QUARTERLY_DRAFT_V1") + field(manifest_hash)
                        + struct.pack(">ii", expected["year"], expected["quarter"])
                        + b"".join(field(configuration[k]) for k in config_keys)
                        + field("COMPLETE_EXPLICIT_SELECTION"))
        assert hashlib.sha256(report_bytes).hexdigest() == expected["report_sha256"]
        assert hashlib.sha256(encoded(document)).hexdigest() == expected["document_sha256"]
        for digest in ("source_document_sha256", "source_manifest_sha256", "report_sha256"):
            assert document[digest] == expected[digest]
        output = document["output"]
        assert output["generation_status"] == "DRAFT"
        assert output["rows"] == [] and output["findings"] == []
        assert output["summary"] and all(v == 0 for v in output["summary"].values())
        assert output["tenant_asset_population"] is None
        for flag in ("report_publishable", "report_published", "evidence_access_granted"):
            assert output[flag] is False
        pending = {"EMPTY_SELECTION", "PUBLICATION_PIPELINE_NOT_CONNECTED"}
        if configuration == pending_config:
            pending.update(("TIME_ZONE_MISSING", "DUPLICATE_RULE_MISSING", "ARCHIVE_RULE_MISSING",
                            "METRIC_VERSION_MISSING", "REPORT_TEMPLATE_VERSION_MISSING", "CONFIG_REVISION_MISSING"))
        assert set(output["pending_reasons"]) == pending

    anonymous = Client("anonymous")
    anonymous.call("anonymous_list", route, status=401, error="SESSION_EXPIRED")
    admin, reviewer = Client("padmin"), Client("reviewer")
    admin_session, reviewer_session = admin.login(users[0]), reviewer.login(users[5])
    tenant = admin_session["tenant"]["id"]
    assert reviewer_session["tenant"]["id"] == tenant
    source = admin.call("source_create", source_route, "POST",
                        {"expected_version": 0, "label": marker, "sources": []})
    assert source["source_count"] == 0 and source["tenant_asset_population"] is None
    source_detail = admin.call("source_read", source_route + "/" + source["id"])
    assert source_detail["sources"] == []
    body = {"expected_version": 0, "label": marker, "source_collection_id": source["id"],
            "expected_source_document_sha256": source["document_sha256"],
            "expected_source_manifest_sha256": source["source_manifest_sha256"],
            "year": 2026, "quarter": 4, "configuration": pending_config}
    key = str(uuid.uuid4())
    saved = admin.call("report_create", route, "POST", body, key=key)
    assert_summary(saved, tenant)
    assert saved["projection"] == "METADATA_ONLY" and "frozen_document" not in saved
    assert saved == admin.call("report_replay", route, "POST", body, key=key)
    reordered = dict(reversed(list(body.items())))
    reordered["configuration"] = dict(reversed(list(pending_config.items())))
    assert saved == admin.call("canonical_key_order_replay", route, "POST", reordered, key=key)
    admin.call("idempotency_payload_conflict", route, "POST", dict(body, label=marker + " CHANGED"),
               key=key, status=409, error="IDEMPOTENCY_CONFLICT")
    path = route + "/" + saved["id"]
    original = admin.call("frozen_detail", path)
    assert_detail(original, saved, pending_config)
    listing = admin.call("metadata_list", route + "?page_size=10")
    assert listing["tenant_id"] == tenant and listing["source"] == "postgresql"
    assert listing["page_size_limit"] == 20 and saved["id"] in {x["id"] for x in listing["items"]}
    assert all(x["projection"] == "METADATA_ONLY" and "frozen_document" not in x for x in listing["items"])
    last = listing["items"][-1]
    cursor = urllib.parse.urlencode({"page_size": 10, "before_recorded_at": last["recorded_at"], "before_id": last["id"]})
    older = admin.call("paired_cursor_list", route + "?" + cursor)
    assert not ({x["id"] for x in listing["items"]} & {x["id"] for x in older["items"]})

    for name in ("document", "manifest"):
        admin.call("source_" + name + "_hash_mismatch", route, "POST",
                   dict(body, **{"expected_source_" + name + "_sha256": "0" * 64}),
                   status=409, error="INSPECTION_REPORT_SOURCE_HASH_CONFLICT")
    admin.call("missing_source", route, "POST", dict(body, source_collection_id=str(uuid.uuid4())), status=404)
    admin.call("csrf_missing", route, "POST", body, status=403, send_csrf=False, error="CSRF_INVALID")
    admin.call("idempotency_key_missing", route, "POST", body, status=400, send_key=False, error="VALIDATION_ERROR")
    for name, bad in (("nonzero_version", dict(body, expected_version=1)),
                      ("invalid_quarter", dict(body, quarter=5)),
                      ("noninteger_year", dict(body, year=2026.0)),
                      ("null_configuration_object", dict(body, configuration=None)),
                      ("missing_configuration_field", {k: v for k, v in body.items() if k != "configuration"}),
                      ("missing_configuration_key", dict(body, configuration={k: v for k, v in pending_config.items() if k != "time_zone"})),
                      ("invalid_time_zone", dict(body, configuration=dict(pending_config, time_zone="Mars/Unknown"))),
                      ("invalid_selection_rule", dict(body, configuration=dict(pending_config, duplicate_rule="GUESS_LATEST"))),
                      ("client_publication_override", dict(body, report_published=True)),
                      ("client_tenant_override", dict(body, tenant_id=tenant))):
        admin.call(name, route, "POST", bad, status=400, error="VALIDATION_ERROR")
    for name, raw in (("duplicate_json_key", '{"year":2026,"year":2025}'),
                      ("trailing_json", "{} {}"), ("null_json", "null")):
        admin.call(name, route, "POST", raw=raw, status=400, error="VALIDATION_ERROR")
    admin.call("oversize_body", route, "POST", raw=" " * 16385, status=413,
               error="INSPECTION_SOURCE_TECHNICAL_LIMIT")
    for name, query in (("invalid_page_size", "page_size=11"), ("duplicate_query", "page_size=10&page_size=15"),
                        ("unpaired_cursor", "before_id=" + saved["id"]),
                        ("unknown_query", "year=2026"), ("invalid_cursor", "before_id=invalid&before_recorded_at=2026-10-19T00%3A00%3A00Z")):
        admin.call(name, route + "?" + query, status=400, error="VALIDATION_ERROR")
    admin.call("detail_query_rejected", path + "?year=2026", status=400, error="VALIDATION_ERROR")
    admin.call("missing_report", route + "/" + str(uuid.uuid4()), status=404)

    for index, name in ((1, "msp"), (2, "tadmin"), (3, "viewerA"), (4, "viewerB")):
        other = Client(name)
        other.login(users[index])
        for action, destination, method, payload in (("list", route, "GET", None),
                                                     ("read", path, "GET", None),
                                                     ("create", route, "POST", body)):
            other.call(name + "_denied_" + action, destination, method, payload,
                       status=403, error="ACTION_FORBIDDEN")

    reviewer_key = str(uuid.uuid4())
    reviewer_body = dict(body, label=marker + " REVIEWER")
    reviewer_saved = reviewer.call("reviewer_create", route, "POST", reviewer_body, key=reviewer_key)
    assert reviewer_saved == reviewer.call("reviewer_replay", route, "POST", reviewer_body, key=reviewer_key)
    other_tenants = [x["id"] for x in admin_session["allowed_tenants"] if x["id"] != tenant]
    assert other_tenants, "second_synthetic_tenant_required"
    other_tenant = other_tenants[0]
    admin.switch("switch_other_tenant", other_tenant)
    try:
        admin.call("foreign_report_404", path, status=404)
        admin.call("foreign_source_create_404", route, "POST", body, status=404)
        foreign_listing = admin.call("foreign_list_scope", route + "?page_size=20")
        assert all(x["tenant_id"] == other_tenant for x in foreign_listing["items"])
        assert saved["id"] not in {x["id"] for x in foreign_listing["items"]}
        foreign_source = admin.call("foreign_source_create", source_route, "POST",
                                    {"expected_version": 0, "label": marker + " FOREIGN", "sources": []})
        foreign_body = dict(body, source_collection_id=foreign_source["id"],
                            expected_source_document_sha256=foreign_source["document_sha256"],
                            expected_source_manifest_sha256=foreign_source["source_manifest_sha256"])
        foreign_saved = admin.call("foreign_report_create", route, "POST", foreign_body)
        assert_summary(foreign_saved, other_tenant)
    finally:
        admin.switch("restore_original_tenant", tenant)
    admin.call("reverse_foreign_report_404", route + "/" + foreign_saved["id"], status=404)
    admin.call("reverse_foreign_source_404", route, "POST", foreign_body, status=404)

    explicit_config = {"revision": "SYNTHETIC_REQUEST", "time_zone": "UTC",
                       "duplicate_rule": "REQUIRE_SINGLE_SOURCE", "archived_rule": "EXCLUDE",
                       "metric_version": "SYNTHETIC_V1", "report_template_version": "SYNTHETIC_V1"}
    configured = admin.call("configured_report_create", route, "POST",
                            dict(body, label=marker + " CONFIGURED", configuration=explicit_config))
    configured_detail = admin.call("configured_report_detail", route + "/" + configured["id"])
    assert_detail(configured_detail, configured, explicit_config)
    assert configured["report_sha256"] != saved["report_sha256"]
    assert original == admin.call("original_frozen_detail_unchanged", path)

    # Current capabilities must be checked before an existing successful receipt is returned.
    # Only the spare disposable reviewer changes; the initiating administrator stays intact.
    roles = admin.call("identity_role_inventory", "/identity/roles")["items"]
    member_path = "/identity/users/" + users[5]["platform_user_id"] + "/memberships"
    memberships = admin.call("identity_reviewer_membership", member_path)["items"]
    original_member = next(x for x in memberships if x["tenant_id"] == tenant)
    assert original_member["base_role"] == "padmin" and original_member["active"] is True
    original_role = original_member["role_id"] or next(
        x["id"] for x in roles if x["builtin"] and x["base_role"] == original_member["base_role"])
    role_body = {"expected_version": 0, "name": marker + " " + uuid.uuid4().hex[:8],
                 "description": "Disposable synthetic capability-revocation HTTP check",
                 "base_role": "padmin", "permissions": ["inspection_quarterly_reports:read", "inspection_quarterly_reports:write"],
                 "enabled": True}
    role = admin.call("create_restricted_role", "/identity/roles", "POST", role_body)
    assignment_attempted = False
    try:
        assignment_attempted = True
        changed = admin.call("revoke_source_read", member_path + "/" + tenant, "PUT",
                             {"expected_version": original_member["permission_version"], "role_id": role["id"], "active": True})
        assert changed["permission_version"] > original_member["permission_version"]
        reviewer.call("source_read_revoked_before_replay", route, "POST", reviewer_body,
                      key=reviewer_key, status=403, error="ACTION_FORBIDDEN")
        readable = reviewer.call("read_still_allowed_without_source_read", route + "/" + reviewer_saved["id"])
        assert_detail(readable, reviewer_saved, pending_config)
        restricted_session = reviewer.call("source_read_revoked_session", "/session")
        assert "inspection_source_collections:read" not in restricted_session["capabilities"]
        assert "inspection_quarterly_reports:write" in restricted_session["capabilities"]
        role_body.update(expected_version=role["version"], permissions=["inspection_quarterly_reports:read", "inspection_source_collections:read"])
        role = admin.call("revoke_report_write", "/identity/roles/" + role["id"], "PATCH", role_body)
        reviewer.call("report_write_revoked_before_replay", route, "POST", reviewer_body,
                      key=reviewer_key, status=403, error="ACTION_FORBIDDEN")
        write_revoked_session = reviewer.call("report_write_revoked_session", "/session")
        assert "inspection_source_collections:read" in write_revoked_session["capabilities"]
        assert "inspection_quarterly_reports:write" not in write_revoked_session["capabilities"]
    finally:
        if assignment_attempted:
            current_members = admin.call("restore_membership_read", member_path)["items"]
            current_member = next(x for x in current_members if x["tenant_id"] == tenant)
            admin.call("restore_original_role", member_path + "/" + tenant, "PUT",
                       {"expected_version": current_member["permission_version"],
                        "role_id": original_role, "active": original_member["active"]})
    restored = reviewer.call("restored_permissions", "/session")
    assert set(restored["capabilities"]) == set(reviewer_session["capabilities"])
    assert reviewer_saved == reviewer.call("replay_after_restoration", route, "POST", reviewer_body, key=reviewer_key)
    assert original == admin.call("final_frozen_detail_unchanged", path)
    role_body.update(expected_version=role["version"], enabled=False)
    disabled = admin.call("disable_unused_synthetic_role", "/identity/roles/" + role["id"], "PATCH", role_body)
    assert disabled["enabled"] is False and disabled["assigned_members"] == 0

    checks = report["checks"][start:]
    assert len(checks) == len(cases) == EXPECTED_HTTP_CHECKS, ("http_check_count", len(checks), EXPECTED_HTTP_CHECKS)
    report["quarterly_report_flow"] = {
        "source": "real_http_and_postgresql", "http_checks": len(checks),
        "status_counts": dict(sorted(collections.Counter(str(c["status"]) for c in checks).items())),
        "created_source_collections": 2, "created_reports": 4,
        "explicit_empty_selection_not_population": True,
        "create_read_metadata_list_and_cursor": True,
        "source_document_and_manifest_hash_conflicts": True,
        "independent_source_manifest_report_document_hashes": True,
        "null_configuration_preserved": True, "explicit_configuration_preserved": True,
        "frozen_detail_repeated_identically": True,
        "worksheet_mutation_immutability": "covered_separately_by_real_postgresql_tests",
        "idempotent_original_and_canonical_key_order_replay": True,
        "idempotency_payload_conflict": True,
        "current_source_read_before_replay": True, "current_report_write_before_replay": True,
        "permission_revocation_via_real_identity_http": True,
        "reviewer_effective_permissions_restored": True,
        "reviewer_original_assignment_was_implicit": original_member["role_id"] is None,
        "synthetic_custom_role_disabled": True,
        "cross_tenant_404_both_directions": True,
        "non_admin_roles_403": ["msp", "tadmin", "viewerA", "viewerB"],
        "anonymous_401": True, "csrf_403": True,
        "invalid_input_and_body_limits": True,
        "all_publication_and_evidence_flags_false": True,
        "external_notifications_sent": False,
    }
