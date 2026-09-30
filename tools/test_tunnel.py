"""Loopback-only SSH forwards to the existing project test environment."""
import argparse
import json
import os
import select
import socketserver
import threading
from pathlib import Path
from inspect_test_server import connect, ROOT

FORWARDS = {
    'postgresql': (15432, 12060),
    'keycloak': (18110, 12110),
    'redis': (16379, 12070),
    'rabbitmq': (15673, 5673),
    'rabbitmq_management': (16673, 15673),
    'minio': (19000, 12080),
}

class ForwardServer(socketserver.ThreadingTCPServer):
    daemon_threads = True
    allow_reuse_address = True

def handler_for(transport, target_port):
    class ForwardHandler(socketserver.BaseRequestHandler):
        def handle(self):
            channel = None
            try:
                channel = transport.open_channel('direct-tcpip', ('127.0.0.1', target_port), self.request.getpeername(), timeout=10)
                if channel is None:
                    return
                while transport.is_active():
                    readable, _, _ = select.select([self.request, channel], [], [], 2)
                    if self.request in readable:
                        data = self.request.recv(65536)
                        if not data:
                            break
                        channel.sendall(data)
                    if channel in readable:
                        data = channel.recv(65536)
                        if not data:
                            break
                        self.request.sendall(data)
            except (OSError, EOFError):
                pass
            finally:
                if channel is not None:
                    channel.close()
                self.request.close()
    return ForwardHandler

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--status-file', default=str(ROOT / '.local/tunnel-status.json'))
    args = parser.parse_args()
    client = connect()
    transport = client.get_transport()
    transport.set_keepalive(15)
    servers = []
    status = Path(args.status_file)
    status.parent.mkdir(parents=True, exist_ok=True)
    try:
        for name, (local_port, remote_port) in FORWARDS.items():
            server = ForwardServer(('127.0.0.1', local_port), handler_for(transport, remote_port))
            servers.append(server)
            threading.Thread(target=server.serve_forever, daemon=True, name='mop-tunnel-' + name).start()
        status.write_text(json.dumps({'pid': os.getpid(), 'project_root': str(ROOT), 'mode': 'remote_test', 'ssh_connected': True, 'forwards': {name: {'local_host': '127.0.0.1', 'local_port': local_port, 'remote_loopback_port': remote_port} for name, (local_port, remote_port) in FORWARDS.items()}}, indent=2) + '\n', encoding='utf-8')
        print('MOP remote test tunnel ready; existing components only', flush=True)
        while transport.is_active():
            threading.Event().wait(1)
        raise ConnectionError('Test SSH transport disconnected; no fixture fallback')
    finally:
        for server in servers:
            server.shutdown()
            server.server_close()
        client.close()
        if status.exists():
            status.unlink()

if __name__ == '__main__':
    main()
