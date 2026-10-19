"""Project-only SSH forwards: existing LAN PostgreSQL and fixed WeCom HTTPS CONNECT.

No database/cache/queue is started. TLS remains end-to-end between Java and WeCom.
Only 127.0.0.1 listeners are used, and CONNECT accepts exactly qyapi.weixin.qq.com:443.
"""
import argparse
import base64
import hashlib
import json
import select
import socket
import socketserver
import threading
from pathlib import Path
import paramiko

ROOT = Path(__file__).resolve().parents[1]


def relay(left, right):
    while True:
        readers, _, _ = select.select([left, right], [], [], 30)
        if not readers:
            continue
        for source in readers:
            data = source.recv(65536)
            if not data:
                return
            (right if source is left else left).sendall(data)


class Server(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = False


def handler(transport, proxy):
    class Handler(socketserver.BaseRequestHandler):
        def handle(self):
            channel = None
            try:
                self.request.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                self.request.settimeout(20)
                if proxy:
                    data = bytearray()
                    while b'\r\n\r\n' not in data and len(data) < 8192:
                        chunk = self.request.recv(1)
                        if not chunk:
                            return
                        data.extend(chunk)
                    line = bytes(data).split(b'\r\n', 1)[0]
                    if line not in (b'CONNECT qyapi.weixin.qq.com:443 HTTP/1.1', b'CONNECT qyapi.weixin.qq.com:443 HTTP/1.0'):
                        self.request.sendall(b'HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n')
                        return
                    target = ('qyapi.weixin.qq.com', 443)
                else:
                    target = ('127.0.0.1', 12060)
                channel = transport.open_channel('direct-tcpip', target, self.request.getpeername(), timeout=15)
                if proxy:
                    self.request.sendall(b'HTTP/1.1 200 Connection Established\r\n\r\n')
                self.request.settimeout(None)
                relay(self.request, channel)
            except Exception:
                pass  # No credentials, query strings or request bodies enter logs.
            finally:
                if channel is not None:
                    channel.close()
    return Handler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--proxy-port', type=int, default=18889)
    parser.add_argument('--pg-port', type=int, default=15438)
    args = parser.parse_args()
    cfg = json.loads((ROOT / 'deploy/private/lan-test-connection.json').read_text(encoding='utf-8-sig'))
    if cfg['environment'] != 'lan-test-20261008' or cfg['host'] != '192.168.1.215':
        raise ValueError('Unexpected designated environment')
    client = paramiko.SSHClient()
    client.load_host_keys(str(ROOT / 'deploy/private/lan-test-known_hosts'))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    servers = []
    try:
        client.connect(cfg['host'], username=cfg['username'], password=cfg['password'],
                       look_for_keys=False, allow_agent=False, timeout=15)
        transport = client.get_transport()
        actual = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip('=')
        if actual != cfg['expected_host_key_sha256']:
            raise ValueError('Pinned host key mismatch')
        transport.set_keepalive(15)
        for port, proxy in ((args.proxy_port, True), (args.pg_port, False)):
            server = Server(('127.0.0.1', port), handler(transport, proxy))
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True).start()
        print(json.dumps({'ready': True, 'environment': cfg['environment'], 'proxy_port': args.proxy_port, 'pg_port': args.pg_port}), flush=True)
        while transport.is_active():
            threading.Event().wait(5)
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        client.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as failure:
        print(json.dumps({'ready': False, 'failure_type': type(failure).__name__}), flush=True)
        raise SystemExit(1)
