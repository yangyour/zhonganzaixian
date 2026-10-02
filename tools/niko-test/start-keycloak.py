from pathlib import Path
import os,json
root=Path.cwd();d=json.loads((root/'.local/niko-test/private/bootstrap.json').read_text())['component_credentials'];env=os.environ.copy()
env.update(KC_DB='postgres',KC_DB_URL='jdbc:postgresql://127.0.0.1:25432/mop_keycloak',KC_DB_USERNAME='mop_keycloak',KC_DB_PASSWORD=d['kc_db_password'],KC_BOOTSTRAP_ADMIN_USERNAME='mop-niko-admin',KC_BOOTSTRAP_ADMIN_PASSWORD=d['kc_admin_password'],JAVA_OPTS_APPEND='-Xms128m -Xmx512m',TZ='UTC')
kc=root.parent/'mop-toolchain/components/keycloak-26.2.4/bin/kc.sh'
os.execve(kc,[str(kc),'start','--http-enabled=true','--http-host=127.0.0.1','--http-management-host=127.0.0.1','--http-port=28110','--hostname=http://127.0.0.1:28110','--hostname-strict=true','--cache=local'],env)
