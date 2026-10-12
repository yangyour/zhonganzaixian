"""Zabbix administration through the designated server's pinned SSH transport.

Only explicit project-scoped callers use this helper. It never logs RPC bodies.
"""
import base64
import hashlib
import http.client
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


class LanZabbix:
    def __init__(self):
        import paramiko
        cfg = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
        if cfg['host'] != '192.168.1.215' or cfg['environment'] != 'lan-test-20261008':
            raise ValueError('Unexpected test environment')
        self.ssh = paramiko.SSHClient()
        self.ssh.load_host_keys(str(ROOT / 'deploy/private/lan-test-known_hosts'))
        self.ssh.set_missing_host_key_policy(paramiko.RejectPolicy())
        keys = self.ssh.get_host_keys().lookup(cfg['host'])
        if not keys or any(base64.b64encode(hashlib.sha256(k.asbytes()).digest()).decode().rstrip('=')
                           != cfg['expected_host_key_sha256'] for k in keys.values()):
            raise ValueError('Host trust mismatch')
        self.ssh.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                         look_for_keys=False, allow_agent=False, timeout=15, auth_timeout=15)
        self.token, self.sequence = None, 0

    def rpc(self, method, params):
        self.sequence += 1
        connection = http.client.HTTPConnection('127.0.0.1', 10020, timeout=15)
        connection.sock = self.ssh.get_transport().open_channel('direct-tcpip', ('127.0.0.1', 10020), ('127.0.0.1', 0))
        connection.sock.settimeout(15)
        try:
            headers = {'Content-Type': 'application/json'}
            if self.token and method != 'apiinfo.version':
                headers['Authorization'] = 'Bearer ' + self.token
            connection.request('POST', '/api_jsonrpc.php', json.dumps({
                'jsonrpc': '2.0', 'id': self.sequence, 'method': method, 'params': params}), headers)
            response = connection.getresponse()
            raw = response.read(2097153)
            if response.status != 200 or len(raw) > 2097152:
                raise RuntimeError('Zabbix transport failed')
            value = json.loads(raw)
            if value.get('id') != self.sequence or 'error' in value or 'result' not in value:
                raise RuntimeError('Zabbix RPC failed: ' + method)
            return value['result']
        finally:
            connection.close()

    def login_admin(self):
        cfg = json.loads((ROOT / 'deploy/private/lan-test-runtime.json').read_text(encoding='utf-8-sig'))
        self.token = self.rpc('user.login', {'username': 'Admin', 'password': cfg['zabbix_admin_password']})

    def close(self):
        self.ssh.close()
