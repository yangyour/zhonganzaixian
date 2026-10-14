"""Run the built Java device client once on the designated test server.

SYSTEM is the default. TEST_INSECURE is an explicit, per-device TLS exception.
Secrets are read from ignored private files and passed via pinned SSH stdin.
No application restart, resource mutation, component installation or polling.
"""
import argparse
import datetime
import hashlib
import json
import logging
from pathlib import Path
import shlex
import uuid
from lan_monitoring_zabbix import LanZabbix, ROOT


def payload(device, mode):
    file = 'scp-password-credentials.json' if device == 'scp' else 'firewall-credentials.json'
    credentials = json.loads((ROOT / 'deploy/private' / file).read_text(encoding='utf-8'))
    target = '192.168.1.179' if device == 'scp' else '192.169.11.1'
    if credentials.get('target') != target:
        raise ValueError('Credential target mismatch')
    if mode == 'TEST_INSECURE' and credentials.get('temporary_certificate_bypass_authorized') is not True:
        raise ValueError('Explicit TLS test authorization missing')
    if device == 'scp':
        config = json.loads((ROOT / 'deploy/private/scp-connection.json').read_text(encoding='utf-8'))
        if config['base_url'] != 'https://' + target:
            raise ValueError('SCP target mismatch')
        config.update(enabled=True, tls_mode=mode, auth_mode='PASSWORD', public_key_api='V1', legacy_cookie_required=False,
                      username_ref='MOP_SCP_USERNAME', password_ref='MOP_SCP_PASSWORD')
        secrets = {'MOP_SCP_USERNAME': credentials['username'], 'MOP_SCP_PASSWORD': credentials['password']}
    else:
        config = {'namespace': 'public', 'tls_mode': mode}
        secrets = {'MOP_AF_USERNAME': credentials['username'], 'MOP_AF_PASSWORD': credentials['password']}
    return {'device': device, 'configuration': config, 'secrets': secrets}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True, choices=['scp', 'firewall'])
    parser.add_argument('--tls-mode', default='SYSTEM', choices=['SYSTEM', 'TEST_INSECURE'])
    parser.add_argument('--verify-tls-isolation', action='store_true')
    parser.add_argument('--artifact', type=Path, default=ROOT / 'server/api/target/mop-api-0.1.0-SNAPSHOT.jar')
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    private = payload(args.device, args.tls_mode)
    digest = hashlib.sha256(args.artifact.read_bytes()).hexdigest()
    release = '/opt/mop-lan-test/releases/device-tls-' + digest[:12]
    name = 'mop-device-api-check-' + uuid.uuid4().hex[:12]
    connection = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
    client = LanZabbix()

    def remote(command, sudo=False, data=None):
        text = shlex.join(command)
        if sudo:
            text = 'sudo -S -p ' + shlex.quote('') + ' -- ' + text
        stdin, stdout, stderr = client.ssh.exec_command(text, timeout=90)
        if sudo:
            stdin.write(connection['password'] + '\n')
            stdin.flush()
        if data is not None:
            stdin.write(data)
            stdin.flush()
        stdin.channel.shutdown_write()
        raw = stdout.read(2097153)
        stderr.read(16384)
        code = stdout.channel.recv_exit_status()
        if len(raw) > 2097152:
            raise RuntimeError('Verification output too large')
        return code, raw.decode('utf-8')

    def checked(command, sudo=False):
        code, out = remote(command, sudo)
        if code:
            raise RuntimeError('Project verification setup failed; details omitted')
        return out

    try:
        owner = json.loads(checked(['cat', '/opt/mop-lan-test/owner.json']))
        if owner.get('project') != 'mop-lan-test' or owner.get('environment') != 'lan-test-20261008':
            raise ValueError('Project owner mismatch')
        labels = json.loads(checked(['docker', 'inspect', '--format', '{{json .Config.Labels}}', 'mop-lan-api'], True))
        if labels.get('mop.project') != 'mop-lan-test':
            raise ValueError('Runtime owner mismatch')
        runtime_image = checked(['docker', 'inspect', '--format', '{{.Config.Image}}', 'mop-lan-api'], True).strip()
        checked(['mkdir', '-p', release])
        exists, _ = remote(['test', '-f', release + '/api.jar'])
        if exists:
            sftp = client.ssh.open_sftp()
            try:
                sftp.put(str(args.artifact), release + '/' + name + '.upload')
                sftp.rename(release + '/' + name + '.upload', release + '/api.jar')
            finally:
                sftp.close()
        if checked(['sha256sum', release + '/api.jar']).split()[0] != digest:
            raise ValueError('Immutable artifact mismatch')
        command = ['docker', 'run', '--rm', '-i', '--pull', 'never', '--name', name,
                   '--label', 'mop.project=mop-lan-test', '--label', 'mop.check=' + name,
                   '--network', 'host', '--read-only', '--user', '10001:10001', '--cap-drop', 'ALL',
                   '--security-opt', 'no-new-privileges', '--memory', '384m', '--pids-limit', '96',
                   '--tmpfs', '/tmp:rw,nosuid,size=64m', '-v', release + ':/app:ro', runtime_image,
                   'java', '-Xmx192m', '-Dloader.main=cn.mop.adapters.monitoring.DeviceConnectionCheck',
                   '-cp', '/app/api.jar', 'org.springframework.boot.loader.launch.PropertiesLauncher', '--stdin']
        if args.verify_tls_isolation:
            command.append('--verify-tls-isolation')
        code, out = remote(command, True, json.dumps(private))
        if code not in (0, 2):
            raise RuntimeError('Device verification execution failed; do not replay authentication automatically')
        report = json.loads(out)
        report.update(environment='lan-test-20261008', source='designated_test_server', artifact_sha256=digest,
                      artifact_release=release, checker_exit_code=code, application_restarted=False,
                      database_migrations=0, verified_at=datetime.datetime.now(datetime.timezone.utc).isoformat())
        args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        print(json.dumps(report, ensure_ascii=True))
        return code
    finally:
        # --rm normally removes the job; after an unknown outcome clean only this invocation's owned job.
        try:
            code, raw = remote(['docker', 'inspect', '--format', '{{json .Config.Labels}}', name], True)
            if code == 0 and json.loads(raw).get('mop.check') == name:
                remote(['docker', 'rm', '--force', name], True)
        finally:
            client.close()


if __name__ == '__main__':
    logging.getLogger('paramiko').setLevel(logging.CRITICAL)
    try:
        raise SystemExit(main())
    except Exception as failure:
        print(json.dumps({'state': 'CHECK_FAILED', 'error_class': type(failure).__name__, 'details': 'omitted', 'integration_ready': False}))
        raise SystemExit(1)
