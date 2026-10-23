"""Add small explicitly fictional asset detail and warranty examples to owned demo assets."""
import argparse
import datetime as dt
from demo_environment import Environment, require, json_text


def enrich(env):
    for role in env.state['contexts']:
        admin = env.client(role, 'admin')
        for index, kind in enumerate(['router', 'switch', 'firewall', 'ap', 'wireless_controller', 'other'], 1):
            asset = env.state['resources'][role + ':asset:' + kind]
            current = admin.call('/assets/' + asset['id'])
            path = '/assets/' + asset['id'] + '/manual-metadata'
            env.once(admin, 'metadata:' + kind, path, {'expected_version': current['version'], 'name': asset['name'],
                'type': asset['type'], 'site_id': asset['site_id'], 'brand': 'DEMO测试品牌', 'model': 'DEMO-' + kind.upper(),
                'serial_number': 'DEMO-' + role.upper() + '-' + str(index).zfill(3), 'location': '[DEMO] 测试机柜A-' + str(index),
                'owner_id': env.state['users']['engineer']['id'], 'business_description': '[DEMO] 测试业务系统',
                'purchase_date': '2026-09-01', 'warranty_expiry': '2027-10-23', 'overdue_service_date': '2030-09-01',
                'collection_method_note': '[DEMO] 人工测试台账，未采集实际设备', 'note': '[DEMO] 虚构资料，仅供测试查看和编辑'}, method='PATCH')
            if kind == 'router':
                current = admin.call('/assets/' + asset['id'])
                env.once(admin, 'warranty-example', path + '/warranty', {'expected_version': current['version'], 'warranty_start': '2026-09-01',
                    'warranty_expiry': '2027-10-23', 'warranty_supplier': '[DEMO] 测试维保服务商', 'overdue_service_date': '2030-09-01',
                    'note': '[DEMO] 虚构维保声明，不代表实际保障或供应商核验'}, method='PATCH')
        collection = env.state['resources'][role + ':inspection-collection']
        source = admin.call('/inspection-source-collections/' + collection['id'])
        anchor = dt.datetime.fromisoformat(env.state['created_at'])
        env.once(admin, 'quarterly-report-populated', '/inspection-quarterly-reports', {
            'expected_version': 0, 'label': '[DEMO] 季度巡检完整样例', 'source_collection_id': collection['id'],
            'expected_source_document_sha256': source['document_sha256'], 'expected_source_manifest_sha256': source['source_manifest_sha256'],
            'year': anchor.year, 'quarter': (anchor.month - 1) // 3 + 1,
            'configuration': {'revision': 'DEMO-DRAFT-1', 'time_zone': 'Asia/Shanghai', 'duplicate_rule': 'REQUIRE_SINGLE_SOURCE',
                'archived_rule': 'EXCLUDE', 'metric_version': 'DEMO-METRIC-1', 'report_template_version': 'DEMO-TEMPLATE-1'}})
    require(env.protected_fingerprint() == env.state['protected_before'], 'Protected rows changed')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        with env.lock():
            enrich(env)
        print(json_text({'metadata_examples': 18, 'warranty_examples': 3, 'populated_report_drafts': 3, 'real_rows_unchanged': True}))
    finally:
        env.close()
