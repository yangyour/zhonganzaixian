from pathlib import Path
import time,socket,urllib.request,sys
opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for attempt in range(60):
    try:
        with socket.create_connection(('127.0.0.1',25432),timeout=2):pass
        with opener.open('http://127.0.0.1:8080/api/v1/health/live' if sys.argv[1:]==['--api'] else 'http://127.0.0.1:28110/realms/mop-test/.well-known/openid-configuration',timeout=2) as response:
            if response.status==200:print('api_live_reachable' if sys.argv[1:]==['--api'] else 'postgres_tcp_and_keycloak_discovery_reachable');sys.exit(0)
    except OSError:pass
    time.sleep(1)
raise SystemExit('Local components not ready; no fallback, credentials omitted')
