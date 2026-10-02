"""Reuse existing MinIO for MOP's isolated asset-import test namespace only.

No service installation, global configuration, port, lifecycle, existing bucket
or existing account is changed. Secrets and ownership recovery state stay private.
"""
import io
import json
import logging
import secrets
import uuid
from datetime import datetime, timezone
from pathlib import Path

import urllib3
from minio import Minio
from minio.commonconfig import Tags
from minio.credentials import StaticProvider
from minio.error import S3Error, MinioAdminException
from minio.minioadmin import MinioAdmin
from inspect_test_server import ROOT, connect

BUCKET = 'mop-test-asset-imports'
ENDPOINT = '127.0.0.1:19000'
PROJECT = 'zhonganzaixian-mop-test'
PRIVATE = ROOT / 'deploy/private/asset-import-namespace.json'
RUNTIME = ROOT / 'deploy/private/runtime.json'
POLICY_NAME = 'mop-test-asset-imports-runtime'
POLICY = {
    'Version': '2012-10-17',
    'Statement': [
        {'Effect': 'Allow', 'Action': ['s3:GetBucketLocation', 's3:ListBucket'], 'Resource': ['arn:aws:s3:::' + BUCKET]},
        {'Effect': 'Allow', 'Action': ['s3:GetObject', 's3:PutObject'], 'Resource': ['arn:aws:s3:::' + BUCKET + '/asset-import-sources/*']},
    ],
}


def save_private(value):
    temporary = PRIVATE.with_suffix('.pending.json')
    temporary.write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')
    temporary.replace(PRIVATE)


def safe_admin_missing(error):
    # SDK stores an error body. Inspect it in memory; never print or persist it.
    text = str(error)
    return any(code in text for code in ['NoSuchUser', 'XMinioAdminNoSuchUser', 'NoSuchPolicy', 'XMinioAdminNoSuchPolicy'])


def canonical_policy(value):
    # MinIO normalizes IAM action/statement order; order does not grant rights.
    if isinstance(value, dict):
        return {key: canonical_policy(item) for key, item in sorted(value.items())}
    if isinstance(value, list):
        return sorted((canonical_policy(item) for item in value), key=lambda item: json.dumps(item, sort_keys=True))
    return value


def main():
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    logging.getLogger('urllib3').setLevel(logging.CRITICAL)
    stage = 'load_private_runtime'
    client = None
    try:
        runtime = json.loads(RUNTIME.read_text(encoding='utf-8-sig'))
        assert runtime['environment']['SPRING_DATASOURCE_URL'].endswith('/mop_test')
        assert runtime['environment']['MOP_OIDC_ISSUER'].endswith('/realms/mop-test')
        stage = 'read_existing_service_state'
        client = connect()
        _, stdout, stderr = client.exec_command("docker inspect --format '{{json .Config.Env}}' shared-minio", timeout=15)
        environment = dict(item.split('=', 1) for item in json.loads(stdout.read().decode()) if '=' in item)
        stderr.read()
        assert stdout.channel.recv_exit_status() == 0
        root_key = environment['MINIO_ROOT_USER']
        root_secret = environment['MINIO_ROOT_PASSWORD']
        _, stdout, stderr = client.exec_command('docker exec shared-minio minio --version', timeout=15)
        version_output = stdout.read().decode()
        stderr.read()
        assert stdout.channel.recv_exit_status() == 0
        assert 'RELEASE.2025-09-07T16-13-09Z' in version_output
        client.close()
        client = None
        http = urllib3.PoolManager(timeout=urllib3.Timeout(connect=5, read=12), retries=False)
        root = Minio(ENDPOINT, access_key=root_key, secret_key=root_secret, secure=False, region='us-east-1', http_client=http)
        admin = MinioAdmin(endpoint=ENDPOINT, credentials=StaticProvider(root_key, root_secret), secure=False, region='us-east-1', http_client=http)
        stage = 'verify_namespace_ownership'
        exists = root.bucket_exists(BUCKET)
        if PRIVATE.exists():
            state = json.loads(PRIVATE.read_text(encoding='utf-8-sig'))
            assert state['project'] == PROJECT and state['bucket'] == BUCKET and state['policy'] == POLICY
            uuid.UUID(state['ownership_id'])
        else:
            # Never adopt an existing bucket or policy/account as this project's own.
            assert not exists
            try:
                admin.policy_info(POLICY_NAME)
                raise RuntimeError('Existing named policy is not privately owned')
            except MinioAdminException as error:
                if not safe_admin_missing(error):
                    raise
            state = {'project': PROJECT, 'bucket': BUCKET, 'ownership_id': str(uuid.uuid4()), 'policy': POLICY,
                     'access_key': 'mop-asset-import-' + secrets.token_hex(8), 'secret_key': secrets.token_urlsafe(36), 'status': 'PREPARING'}
            save_private(state)
        if exists:
            tags = root.get_bucket_tags(BUCKET)
            assert tags and tags.get('mop-project') == PROJECT and tags.get('mop-owner') == state['ownership_id']
        else:
            stage = 'create_project_bucket'
            root.make_bucket(BUCKET, location='us-east-1')
            tags = Tags.new_bucket_tags()
            tags['mop-project'] = PROJECT
            tags['mop-owner'] = state['ownership_id']
            root.set_bucket_tags(BUCKET, tags)
        stage = 'check_private_bucket_policy'
        try:
            anonymous_policy = json.loads(root.get_bucket_policy(BUCKET))
            assert not any(item.get('Effect') == 'Allow' for item in anonymous_policy.get('Statement', []))
        except S3Error as error:
            if error.code != 'NoSuchBucketPolicy':
                raise
        stage = 'prepare_project_account'
        try:
            user_info = json.loads(admin.user_info(state['access_key']))
            assert user_info['status'] == 'enabled'
        except MinioAdminException as error:
            if not safe_admin_missing(error):
                raise
            admin.user_add(state['access_key'], state['secret_key'])
            user_info = json.loads(admin.user_info(state['access_key']))
        assert user_info.get('policyName', '') in ['', POLICY_NAME]
        assert not user_info.get('memberOf')
        stage = 'prepare_project_policy'
        try:
            existing_policy = json.loads(admin.policy_info(POLICY_NAME))
            assert canonical_policy(existing_policy) == canonical_policy(POLICY)
        except MinioAdminException as error:
            if not safe_admin_missing(error):
                raise
            admin.policy_add(POLICY_NAME, policy=POLICY)
        if user_info.get('policyName') != POLICY_NAME:
            admin.attach_policy([POLICY_NAME], user=state['access_key'])
        stage = 'verify_restricted_namespace_access'
        scoped = Minio(ENDPOINT, access_key=state['access_key'], secret_key=state['secret_key'], secure=False, region='us-east-1', http_client=http)
        assert scoped.bucket_exists(BUCKET)
        stage = 'verify_runtime_put'
        key = 'asset-import-sources/component-probes/' + str(uuid.uuid4())
        payload = b'MOP-FAKE-ASSET-IMPORT-COMPONENT-PROBE'
        scoped.put_object(BUCKET, key, io.BytesIO(payload), len(payload), content_type='application/octet-stream', metadata={'mop-virtual-test-data': 'true'})
        stage = 'verify_runtime_get'
        response = scoped.get_object(BUCKET, key)
        try:
            assert response.read() == payload
        finally:
            response.close()
            response.release_conn()
        stage = 'verify_runtime_delete_denied'
        try:
            scoped.remove_object(BUCKET, key)
            raise RuntimeError('Runtime unexpectedly has delete permission')
        except S3Error as error:
            assert error.code == 'AccessDenied'
        stage = 'verify_runtime_bucket_visibility'
        visibility = 'DENIED'
        try:
            visible = scoped.list_buckets()
            assert all(bucket.name == BUCKET for bucket in visible)
            visibility = 'PROJECT_BUCKET_ONLY'
        except S3Error as error:
            assert error.code == 'AccessDenied'
        stage = 'verify_runtime_outside_prefix_denied'
        try:
            scoped.put_object(BUCKET, 'outside-asset-import-prefix/' + str(uuid.uuid4()), io.BytesIO(payload), len(payload))
            raise RuntimeError('Runtime unexpectedly writes outside asset-import prefix')
        except S3Error as error:
            assert error.code == 'AccessDenied'
        anonymous = Minio(ENDPOINT, secure=False, region='us-east-1', http_client=http)
        stage = 'verify_anonymous_access_denied'
        try:
            anonymous.stat_object(BUCKET, key)
            raise RuntimeError('Anonymous asset-import access unexpectedly allowed')
        except S3Error as error:
            assert error.code == 'AccessDenied'
        stage = 'save_project_runtime_references'
        expected = {'MOP_IMPORT_S3_ENDPOINT': 'http://' + ENDPOINT, 'MOP_IMPORT_S3_BUCKET': BUCKET,
                    'MOP_IMPORT_S3_ACCESS_KEY': state['access_key'], 'MOP_IMPORT_S3_SECRET_KEY': state['secret_key'],
                    'MOP_IMPORT_ALLOWED_FORMATS': 'CSV,XLSX'}
        for name, value in expected.items():
            if name in runtime['environment']:
                assert runtime['environment'][name] == value
        runtime['environment'].update(expected)
        temporary_runtime = RUNTIME.with_suffix('.asset-import-pending.json')
        temporary_runtime.write_text(json.dumps(runtime, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        temporary_runtime.replace(RUNTIME)
        state['status'] = 'READY'
        save_private(state)
        evidence = {'verified_at': datetime.now(timezone.utc).isoformat(), 'component': 'existing_shared_minio',
                    'version': 'RELEASE.2025-09-07T16-13-09Z', 'python_sdk': '7.2.20', 'namespace': BUCKET,
                    'only_project_namespace_created': True, 'services_installed_or_reconfigured': False,
                    'runtime_put_and_get': True, 'runtime_delete_denied': True, 'runtime_bucket_visibility': visibility,
                    'runtime_other_project_buckets_not_visible': True,
                    'runtime_outside_asset_import_prefix_write_denied': True, 'anonymous_access_denied': True,
                    'import_format_source': 'CSV_XLSX_CONTRACT_M3_6', 'production_approval': 'UNVERIFIED',
                    'scanner': 'NOT_CONFIGURED', 'retention_until': None, 'application_asset_import_integration': 'NOT_YET_VERIFIED', 'credential_output': 'none'}
        (ROOT / 'docs/evidence/asset-import-component-verification.json').write_text(json.dumps(evidence, indent=2) + '\n', encoding='utf-8')
        print(json.dumps({'status': 'ready', 'component': 'existing_shared_minio', 'namespace': BUCKET,
                          'restricted_account_verified': True, 'application_asset_import_integration': 'not_yet_verified', 'credential_output': 'none'}))
        return 0
    except Exception:
        # SDK/SSH/private JSON exceptions can contain credentials; do not echo them.
        print(json.dumps({'status': 'failed', 'stage': stage, 'details': 'redacted; no component fallback or global change'}))
        return 1
    finally:
        if client is not None:
            client.close()


if __name__ == '__main__':
    raise SystemExit(main())
