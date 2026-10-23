"""Small, visibly synthetic data sets, exclusively for demo_environment namespaces."""
import argparse
import datetime as dt
import hashlib
import json
import time

from demo_environment import Environment, json_text, require


def seed(env):
    env.ensure_reviewer()
    anchor = dt.datetime.fromisoformat(env.state['created_at']).replace(microsecond=0)
    stamp = lambda delta=dt.timedelta(): (anchor + delta).isoformat().replace('+00:00', 'Z')
    failures = []
    for context, details in env.state['contexts'].items():
        main = env.client(context)
        admin = main if context == 'padmin' else env.client(context, 'admin')
        engineer = main if context == 'msp' else env.client(context, 'engineer')
        customer = main if context == 'tadmin' else env.client(context, 'customer')
        reviewer = env.client(context, 'reviewer')
        once = lambda key, path, body, client=admin: env.once(client, key, path, body)

        def step(name, operation):
            try:
                result = operation()
                print(json_text({'context': context, 'step': name, 'status': 'OK'}), flush=True)
                return result
            except Exception as error:
                failures.append({'context': context, 'step': name, 'error': str(error)})
                print(json_text(failures[-1]), flush=True)
                return None

        def publish_test_configuration(key, value):
            proposal = once('configuration:' + key, '/configurations/' + key + '/revisions', {'proposed_value': value,
                'reason': '[DEMO] 用户授权的独立测试配置，不代表客户生产确认', 'evidence_refs': [{
                    'reference': 'document-ref:DEMO-20261023', 'title': '[DEMO] 独立测试数据授权', 'sha256': hashlib.sha256(b'DEMO-20261023').hexdigest()}]})
            submitted = once('submit:' + key, '/configuration-revisions/' + proposal['id'] + '/submit', {'expected_version': proposal['version']})
            approved = once('approve:' + key, '/configuration-revisions/' + proposal['id'] + '/review', {'expected_version': submitted['version'],
                'decision': 'approve', 'comment': '[DEMO] 仅批准隔离测试值，非生产参数'}, reviewer)
            definition = admin.call('/runtime-configurations/' + key)
            return once('release:' + key, '/runtime-configurations/' + key + '/releases', {
                'expected_version': 0, 'expected_definition_hash': definition['definition_hash'], 'action': 'PUBLISH',
                'revision_id': approved['id'], 'revision_version': approved['version'], 'target_release_id': None,
                'activation': 'IMMEDIATE', 'effective_at': None, 'expires_at': None, 'reason': '[DEMO] 仅在独立测试租户生效'})
        step('demo-device-quota', lambda: publish_test_configuration('tenant.contract_device_quota', 20))
        assets = []
        for index, (kind, label) in enumerate([('router', '核心路由器'), ('switch', '接入交换机'), ('firewall', '边界防火墙'), ('ap', '无线AP'), ('wireless_controller', '无线控制器'), ('other', '应用服务器')], 10):
            asset = step('asset-' + kind, lambda kind=kind, label=label, index=index: once('asset:' + kind, '/assets', {
                'name': '[DEMO] ' + label, 'type': kind, 'management_ip': '192.0.2.' + str(index), 'site_id': details['site_id']}))
            if asset:
                assets.append(asset)
        require(len(assets) == 6, 'Six owned demo assets required')
        step('topology', lambda: once('topology', '/assets/' + assets[0]['id'] + '/dependencies', {
            'expected_version': 0, 'additions': [{'parent_id': assets[0]['id'], 'child_id': asset['id'],
                'proposed_type': 'DEMO_LOGICAL_DEPENDENCY', 'proposed_suppression_scope': None, 'proposed_enabled': False} for asset in assets[1:]]}))
        for index, role in [(2, 'DECISION_MAKER'), (0, 'REPORTER'), (1, 'TECHNICAL_CONTACT')]:
            contact = {'name': '[DEMO] ' + ['报障联系人', '技术联系人', '服务负责人'][index], 'roles': [role],
                'mobile': None, 'email': 'demo-' + str(index) + '@example.invalid', 'wecom_user_id': None,
                'preferred_priorities': [], 'quiet_window': None, 'enabled': True}
            step('contact-' + str(index), lambda index=index, contact=contact: once('contact:' + str(index), '/contacts', {'expected_version': 0, **contact}))
        step('contact-import', lambda: once('contact-import', '/contact-imports', {'expected_version': 0, 'mode': 'CREATE_ONLY',
            'acknowledge_exact_duplicate_rows': False, 'rows': [{**contact, 'name': '[DEMO] 批量导入联系人', 'email': 'import-demo@example.invalid'}]}))
        step('notification-preferences', lambda: once('preferences', '/my/notification-preferences', {'expected_version': 0,
            'intent': 'SAVE_REQUEST_DRAFT_ONLY', 'requested_level': 'P1_P2', 'requested_channels': ['EMAIL'],
            'requested_quiet_window': None}, main))

        tickets = []
        for index, label in enumerate(['网络延迟排查', '交换机端口检查', '无线覆盖优化']):
            def create_ticket(index=index, label=label):
                receipt = once('ticket:' + str(index), '/tickets', {'title': '[DEMO] ' + label, 'description': '虚构测试工单，仅用于独立演示租户的流程体验，不代表真实故障。',
                    'source': 'customer_report', 'asset_id': assets[index]['id'], 'category': '网络不通', 'impact_scope': 'individual',
                    'contact_name': '[DEMO] 测试客户', 'contact_phone': '+00 000', 'urgency': 'normal', 'attachment_ids': []}, customer)
                operation = customer.call('/operations/' + receipt['operation_id'])
                require(operation.get('result_ref', '').startswith('/api/v1/tickets/'), 'Ticket operation not complete')
                ticket = admin.call(operation['result_ref'].replace('/api/v1', '', 1))
                tickets.append(ticket)
                return ticket
            step('ticket-' + str(index), create_ticket)
        for index, ticket in enumerate(tickets):
            base = '/tickets/' + ticket['id']
            step('comment-' + str(index), lambda base=base, ticket=ticket, index=index: once('comment:' + str(index), base + '/comments',
                {'expected_ticket_version': ticket['version'], 'text': '[DEMO] 已登记测试问题，等待演示处理。'}, customer))
            step('note-' + str(index), lambda base=base, ticket=ticket, index=index: once('note:' + str(index), base + '/internal-notes',
                {'expected_ticket_version': ticket['version'], 'text': '[DEMO] 内部排查记录：仅演示，不执行真实设备命令。'}, engineer))
            def workflow(index=index, ticket=ticket, base=base):
                activated = once('activate:' + str(index), base + '/activate', {'expected_version': 0, 'priority': ['P2', 'P3', 'P1'][index], 'service_tier': 'STANDARD', 'reason': '[DEMO] 启用独立测试工单流程'})
                if index == 0:
                    if context == 'padmin':
                        return once('accept:0', base + '/accept', {'expected_version': activated['version']}, main)
                    return activated
                accepted = once('accept:' + str(index), base + '/accept', {'expected_version': activated['version']}, engineer)
                started = once('start:' + str(index), base + '/start', {'expected_version': accepted['version']}, engineer)
                if index == 2:
                    return once('resolve:' + str(index), base + '/resolve', {'expected_version': started['version'], 'note': '[DEMO] 演示处理结果，未操作真实设备'}, engineer)
                return started
            step('workflow-' + str(index), workflow)
            if index == 1:
                step('worklog', lambda base=base, ticket=ticket: once('worklog', base + '/worklogs', {'expected_ticket_version': ticket['version'], 'kind': 'REMOTE',
                    'started_at': stamp(dt.timedelta(hours=-2)), 'ended_at': stamp(dt.timedelta(hours=-1)), 'note': '[DEMO] 虚构测试工时，不用于实际计费'}, engineer))

        step('manual-incident', lambda: once('manual-incident', '/manual-incidents', {'title': '[DEMO] 接入交换机间歇性丢包',
            'severity': 'warning', 'asset_id': assets[1]['id'], 'expected_asset_version': assets[1]['version']}))
        for index, severity in enumerate(['WARNING', 'HIGH']):
            step('event-' + str(index), lambda index=index, severity=severity: once('event:' + str(index), '/events/ingest', {
                'asset_id': assets[index]['id'], 'rule_code': 'DEMO_' + severity, 'rule_name': '[DEMO] ' + ['延迟偏高', '接口故障'][index],
                'severity': severity, 'source_phase': 'PROBLEM', 'source_event_id': 'DEMO-' + context + '-' + str(index),
                'occurred_at': stamp(dt.timedelta(minutes=-10 + index)), 'simulate_autoticket_failure': False}))
        step('maintenance', lambda: once('maintenance', '/maintenance-windows', {'name': '[DEMO] 停机维护演示窗口', 'asset_id': assets[2]['id'],
            'starts_at': stamp(dt.timedelta(days=1)), 'ends_at': stamp(dt.timedelta(days=1, hours=1)),
            'silence_mode': 'SUPPRESS_NOTIFY', 'sla_exclusion_requested': False, 'reason': '[DEMO] 虚构计划，不对实际设备执行'}))
        step('service-tier', lambda: once('service-tier', '/service-tiers/changes', {'expected_version': 0, 'to_tier': 'STANDARD',
            'effective_mode': 'IMMEDIATE', 'contract_device_count': 6, 'billing_cycle': 'MONTHLY', 'monthly_fee_fen': 10000,
            'reason': '[DEMO] 独立测试服务档位与虚构金额，不用于真实计费'}))
        step('oncall', lambda: once('oncall', '/oncall/rosters', {'expected_version': 0, 'shift_date': anchor.astimezone(dt.timezone(dt.timedelta(hours=8))).date().isoformat(),
            'shift_code': 'FULL_DAY', 'primary_oncall_user_id': engineer.session['user']['id'], 'backup_oncall_user_id': env.state['users']['admin']['id'],
            'note': '[DEMO] 虚构值班，不联系人员'}))
        step('sla-report', lambda: once('sla-report', '/sla/monthly-results', {'report_month': anchor.strftime('%Y-%m'), 'expected_version': 0, 'freeze': False}))

        template = step('inspection-template', lambda: once('inspection-template', '/inspection-templates', {'expected_version': 0,
            'device_type': 'switch', 'name': '[DEMO] 交换机日常巡检', 'items': [
                {'key': 'ports', 'section': '接口检查', 'label': '接口状态', 'instruction': '仅录入虚构演示观察'},
                {'key': 'backup', 'section': '配置记录', 'label': '配置备份记录', 'instruction': '不连接真实设备'}]}))
        if template:
            worksheet = step('inspection-worksheet', lambda: once('inspection-worksheet', '/inspection-worksheets', {'expected_version': 0,
                'asset_id': assets[1]['id'], 'template_id': template['id'], 'template_version': template['version'],
                'observations': [{'key': 'ports', 'result': 'ATTENTION', 'note': '[DEMO] 端口2状态待复核'}, {'key': 'backup', 'result': 'NORMAL', 'note': '[DEMO] 虚构人工检查记录'}],
                'reported_performed_at': stamp(dt.timedelta(hours=-3))}))
            if worksheet:
                source = admin.call('/inspection-worksheets/' + worksheet['id'] + '/revisions/1')
                collection = step('inspection-collection', lambda: once('inspection-collection', '/inspection-source-collections', {'expected_version': 0,
                    'label': '[DEMO] 本季度巡检来源', 'sources': [{'worksheet_id': source['worksheet_id'], 'version': source['version'], 'expected_source_sha256': source['source_snapshot_sha256']}]}))
                if collection:
                    detail = admin.call('/inspection-source-collections/' + collection['id'])
                    step('quarterly-report', lambda: once('quarterly-report', '/inspection-quarterly-reports', {'expected_version': 0, 'label': '[DEMO] 季度巡检报告草稿',
                        'source_collection_id': collection['id'], 'expected_source_document_sha256': detail['document_sha256'], 'expected_source_manifest_sha256': detail['source_manifest_sha256'],
                        'year': anchor.year, 'quarter': (anchor.month - 1) // 3 + 1, 'configuration': {key: None for key in ['revision', 'time_zone', 'duplicate_rule', 'archived_rule', 'metric_version', 'report_template_version']}}))
        step('backup-draft', lambda: once('backup-draft', '/backup-plan-drafts', {'expected_version': 0, 'name': '[DEMO] 交换机配置备份计划',
            'target_asset_ids': [assets[1]['id']], 'daily_local_time': '02:00', 'credential_ref': None, 'requested_enabled': False,
            'note': '[DEMO] 停用演示草稿，无设备凭据，不执行备份'}))
        step('change-draft', lambda: once('change-draft', '/change-notice-drafts', {'expected_version': 0,
            'change_summary': '[DEMO] 交换机维护报备草稿', 'asset_ids': [assets[1]['id']], 'planned_start_at': stamp(dt.timedelta(days=1)),
            'planned_end_at': stamp(dt.timedelta(days=1, hours=1)), 'ticket_id': tickets[0]['id'] if tickets else None}))
        step('calendar', lambda: once('calendar', '/working-calendars', {'name': '[DEMO] 工作日历草稿', 'coverage_start': stamp(), 'coverage_end': stamp(dt.timedelta(days=2)),
            'working_windows': [{'start': stamp(), 'end': stamp(dt.timedelta(hours=8))}], 'coverage_complete': True, 'note': '[DEMO] 测试覆盖窗，不是实际服务日历'}))
        calculation = {'inputs': {'hosts': '6', 'items_per_host': '20', 'interval_seconds': '60', 'history_days': 30, 'bytes_per_value': '32', 'trend_ratio': '0.1', 'trend_days': 365, 'headroom_ratio': '0.3'},
            'model': {'version': 'DEMO-ASSUMPTION-1', 'bytes_per_trend_row': '64', 'pool_nvps_limit': '1000'},
            'proxy_buffer': {'site_nvps': '2', 'outage_seconds': '3600', 'bytes_per_value': '32', 'margin_factor': '1.5'}}
        step('capacity-scenario', lambda: once('capacity-scenario', '/capacity/scenarios', {'expected_version': 0, 'label': '[DEMO] 六台设备容量测算',
            'note': '[DEMO] 虚构输入，仅用于测算交互，不作为容量承诺', 'calculation': calculation, 'benchmark_reference': None}))
        if context != 'tadmin':
            step('asset-export', lambda: once('asset-export', '/asset-export-jobs', {'expected_version': 0, 'mode': 'PREPARE_ONLY_NO_PUBLICATION',
                'filters': {'type': None, 'status': None, 'site_id': None, 'query': '[DEMO]'}}, main))
    env.state['seed_failures'] = failures
    env.save()
    return failures


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--private-root', required=True)
    args = parser.parse_args()
    env = Environment(args.private_root)
    try:
        with env.lock():
            failures = seed(env)
            require(env.protected_fingerprint() == env.state['protected_before'], 'Protected rows changed')
            require(env.connect().schema() == env.state['schema_history'], 'Schema changed')
        print(json_text({'failures': failures, 'resource_count': len(env.state['resources']), 'real_data_unchanged': True}))
    finally:
        env.close()
