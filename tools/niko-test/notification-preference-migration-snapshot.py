"""Read-only full-row equality of the 56 existing project tables; no credentials or row output."""
from pathlib import Path
import importlib.util,json
spec=importlib.util.spec_from_file_location('notification_preference_snapshot_base',Path('tools/niko-test/contact-import-fix-migration-snapshot.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)
base.BEFORE=base.LOCAL/'notification-preference-v26-before.json'
base.AFTER=base.LOCAL/'notification-preference-v26-after.json'
base.TABLES=('mop_asset', 'mop_asset_dependency_edge', 'mop_asset_dependency_graph', 'mop_asset_dependency_revision', 'mop_asset_export_attempt', 'mop_asset_export_job', 'mop_asset_import_artifact', 'mop_asset_import_job', 'mop_asset_import_mapping', 'mop_asset_import_operation', 'mop_asset_import_source', 'mop_asset_manual_details', 'mop_asset_manual_revision', 'mop_audit', 'mop_capacity_scenario_draft', 'mop_configuration', 'mop_configuration_revision', 'mop_configuration_revision_history', 'mop_contact_import_job', 'mop_contact_import_result', 'mop_contact_tenant_guard', 'mop_customer_contact', 'mop_customer_contact_revision', 'mop_file', 'mop_file_grant', 'mop_idempotency', 'mop_inspection_template', 'mop_inspection_template_item', 'mop_inspection_template_revision', 'mop_inspection_worksheet', 'mop_inspection_worksheet_revision', 'mop_manual_incident_registration', 'mop_manual_recovery_confirmation', 'mop_membership', 'mop_operation', 'mop_outbox', 'mop_photo_job', 'mop_site', 'mop_tenant', 'mop_ticket', 'mop_ticket_comment', 'mop_ticket_history', 'mop_ticket_internal_note', 'mop_user', 'mop_working_calendar_draft', 'mop_working_calendar_revision', 'mop_worklog', 'mop_worklog_revision','mop_inspection_source_collection','mop_inspection_source_collection_item','mop_backup_plan_draft','mop_backup_plan_revision','mop_backup_plan_target','mop_change_notice_draft','mop_change_notice_revision','mop_change_notice_asset')
if __name__=='__main__':
    try:base.main()
    except Exception as failure:
        print(json.dumps({'status':'FAILED','exception_type':type(failure).__name__,'details':'redacted'}));raise SystemExit(1)
