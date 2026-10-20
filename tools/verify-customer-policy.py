"""Verify customer policy changes against the designated LAN PG via pinned SSH.

Only synthetic project records are written; no migration, service changes or external messages.
Requires py -3.11 with the existing Paramiko client and ignored LAN configuration.
"""
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import threading

ROOT=Path(__file__).resolve().parents[1]
OUTPUT=ROOT/".local/customer-policy-verification"
OUTPUT.mkdir(parents=True,exist_ok=True)
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,ROOT/path)
    mod=importlib.util.module_from_spec(spec);sys.modules[name]=mod;spec.loader.exec_module(mod);return mod

deployment=module("customer_review_deploy","tools/update-lan-test-server.py")
tunnel=module("customer_review_tunnel","tools/wecom-dev-tunnel.py")
server=None;forward=None
try:
    server=deployment.Server()
    forward=tunnel.Server(("127.0.0.1",0),tunnel.handler(server.client.get_transport(),False))
    threading.Thread(target=forward.serve_forever,daemon=True).start()
    cfg=json.loads((ROOT/"deploy/private/lan-test-runtime.json").read_text(encoding="utf-8-sig"))
    if cfg["environment"]!=deployment.ENVIRONMENT: raise ValueError("Wrong environment")
    env={k:v for k,v in os.environ.items() if not k.startswith(("MOP_","SPRING_"))}
    env.update(SPRING_DATASOURCE_URL=f"jdbc:postgresql://127.0.0.1:{forward.server_address[1]}/mop_test",
               SPRING_DATASOURCE_PASSWORD=cfg["runtime_password"],
               MOP_RUN_MONITORING_EVENT_CENTER_TESTS="true",MOP_RUN_TICKET_WORKFLOW_TESTS="true",
               MOP_RUN_ONCALL_ESCALATION_TESTS="true")
    tests="PostgresMonitoringEventCenterIntegrationTest#longProblemKeepsIdentityAndLateRecoveryClosesTheSameSignalOnly+maintenanceExclusionCannotBeSelfApprovedAndEndingStopsTheInterval,PostgresTicketWorkflowIntegrationTest,PostgresOnCallEscalationIntegrationTest#customerPolicyReportsAssetEvidenceWithoutInventingHealthySamples"
    logfile="postgresql-tests.log"
    with (OUTPUT/logfile).open("w",encoding="utf-8") as output:
        completed=subprocess.run(["mvn.cmd","-B","-ntp","-pl","server/persistence","-am",
                                  "-Dtest="+tests,"-Dsurefire.failIfNoSpecifiedTests=false","test"],
                                  cwd=ROOT,env=env,stdout=output,stderr=subprocess.STDOUT)
    evidence={"environment":deployment.ENVIRONMENT,"scope":"REAL_DESIGNATED_PG_SYNTHETIC_PROJECT_DATA",
              "test_selection":tests,"exit_code":completed.returncode,"migrations":0,"external_messages":0,
              "schema_version":max(int(row["version"]) for row in server.schema() if row["version"] and row["success"])}
    (OUTPUT/"summary.json").write_text(json.dumps(evidence,indent=2)+"\n",encoding="utf-8")
    print(json.dumps(evidence))
    raise SystemExit(completed.returncode)
except Exception as failure:
    print(json.dumps({"status":"BLOCKED","failure_type":type(failure).__name__}))
    raise SystemExit(1) from None
finally:
    if forward:forward.shutdown();forward.server_close()
    if server:server.client.close()
