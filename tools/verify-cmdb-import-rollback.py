"""Run rollback-only CMDB PostgreSQL checks against the designated server through a pinned SSH tunnel.

Reads existing private credentials; never starts components, changes configuration or persists test data.
The Java tests create fresh tenant rows inside an outer transaction which always rolls back.
"""
from __future__ import annotations
import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import select
import shlex
import socketserver
import subprocess
import threading
import uuid
import paramiko


def main() -> int:
    args = argparse.ArgumentParser()
    args.add_argument("--private-directory", type=Path, required=True)
    args.add_argument("--coordination-directory", type=Path, required=True)
    options = args.parse_args()
    root = Path(__file__).resolve().parents[1]
    cfg = json.loads((options.private_directory / "lan-test-connection.json").read_text(encoding="utf-8-sig"))
    runtime = json.loads((options.private_directory / "lan-test-runtime.json").read_text(encoding="utf-8-sig"))
    if cfg.get("environment") != "lan-test-20261008":
        raise RuntimeError("Unexpected designated environment")
    client = paramiko.SSHClient()
    client.load_host_keys(str(options.private_directory / "lan-test-known_hosts"))
    client.set_missing_host_key_policy(paramiko.RejectPolicy())
    lock = options.coordination_directory / "environment.lock"
    token = json.dumps({"session": "assets", "branch": "codex/launch-assets-20261023",
                        "purpose": "CMDB rollback tests", "started_at": datetime.now(timezone.utc).isoformat(),
                        "ownership": str(uuid.uuid4())})
    held = False
    server = None
    try:
        client.connect(cfg["host"], username=cfg["username"], password=cfg["password"],
                       look_for_keys=False, allow_agent=False, timeout=15)
        transport = client.get_transport()
        fingerprint = base64.b64encode(hashlib.sha256(transport.get_remote_server_key().asbytes()).digest()).decode().rstrip("=")
        if fingerprint != cfg["expected_host_key_sha256"]:
            raise RuntimeError("Pinned host fingerprint mismatch")

        def run(command: list[str]) -> str:
            stdin, stdout, stderr = client.exec_command("sudo -S -p '' -- " + shlex.join(command), timeout=20)
            stdin.write(cfg["password"] + "\n")
            stdin.flush()
            stdin.channel.shutdown_write()
            output = stdout.read().decode()
            stderr.read()
            if stdout.channel.recv_exit_status():
                raise RuntimeError("Read-only component inspection failed")
            return output

        item = json.loads(run(["docker", "inspect", "mop-lan-postgres"]))[0]
        if item["Config"]["Labels"].get("mop.project") != "mop-lan-test" or not item["State"]["Running"]:
            raise RuntimeError("Designated component ownership or state mismatch")
        networks = list(item["NetworkSettings"]["Networks"].values())
        if len(networks) != 1 or not networks[0]["IPAddress"]:
            raise RuntimeError("Unambiguous component route required")
        destination = (networks[0]["IPAddress"], 5432)
        version = run(["docker", "exec", "mop-lan-postgres", "psql", "-U", "postgres", "-d", "mop_test", "-At", "-c", "SHOW server_version;"]).strip()
        print(json.dumps({"environment": cfg["environment"], "postgresql": version, "component_running": True}))
        with lock.open("x", encoding="utf-8") as output:
            output.write(token)
        held = True

        class Handler(socketserver.BaseRequestHandler):
            def handle(self):
                channel = transport.open_channel("direct-tcpip", destination, self.request.getpeername())
                try:
                    while True:
                        readable, _, _ = select.select([self.request, channel], [], [], 30)
                        for source in readable:
                            data = source.recv(65536)
                            if not data:
                                return
                            (channel if source is self.request else self.request).sendall(data)
                finally:
                    channel.close()

        class Server(socketserver.ThreadingTCPServer):
            daemon_threads = True

        server = Server(("127.0.0.1", 0), Handler)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        env = os.environ.copy()
        # Do not enable any historical remote test suites or their old fixed business-tenant fixtures.
        env.pop("MOP_RUN_REMOTE_TESTS", None)
        env["MOP_RUN_CMDB_ROLLBACK_TESTS"] = "true"
        env["MOP_CMDB_TEST_JDBC"] = f"jdbc:postgresql://127.0.0.1:{server.server_address[1]}/mop_test"
        env["MOP_CMDB_TEST_ADMIN_PASSWORD"] = runtime["postgres_admin_password"]
        command = ["mvn.cmd" if os.name == "nt" else "mvn", "-pl", "server/api", "-am",
                   "-Dtest=PostgresCmdbImportExecutionIntegrationTest", "-Dsurefire.failIfNoSpecifiedTests=false", "test"]
        return subprocess.run(command, cwd=root, env=env, check=False).returncode
    except FileExistsError:
        print("BLOCKED: another session holds environment.lock; no database writes executed")
        return 3
    finally:
        if server:
            server.shutdown()
            server.server_close()
        client.close()
        if held and lock.read_text(encoding="utf-8") == token:
            lock.unlink()


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as failure:
        # Connection libraries can include destinations; credentials and raw configuration never enter logs.
        print("CMDB verification failed: " + type(failure).__name__)
        raise SystemExit(2)
