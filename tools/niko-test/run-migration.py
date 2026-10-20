from pathlib import Path
import json,os,subprocess,sys,hashlib
MODES=('--initialize-new-niko-database','--check','--apply-photos','--apply-asset-imports','--apply-asset-dependencies','--apply-worklogs','--apply-asset-metadata','--apply-internal-notes','--apply-ticket-comments','--apply-working-calendars','--apply-manual-incidents','--apply-manual-recoveries','--apply-manual-warranty','--apply-capacity-scenarios','--apply-customer-contacts','--apply-contact-imports','--apply-contact-import-fix','--apply-asset-exports','--apply-inspection-templates','--apply-inspection-worksheets','--apply-inspection-source-collections','--apply-backup-plan-drafts','--apply-change-notice-drafts','--apply-notification-preference-drafts','--apply-project-identity','--apply-identity-compatibility','--apply-raw-monitoring-events','--apply-monitoring-quarantine','--apply-export-inbox','--apply-runtime-configuration','--apply-ticket-lifecycle')
if len(sys.argv)!=2 or sys.argv[1] not in MODES:raise SystemExit('Explicit migration mode required')
root=Path.cwd()
manifest=json.loads((root/'tools/niko-test/frozen-migrations.json').read_text())['sha256']
manifest.update(json.loads((root/'tools/niko-test/frozen-dependency-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-worklog-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-asset-metadata-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-internal-note-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-ticket-comment-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-working-calendar-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-manual-incident-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-manual-recovery-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-manual-warranty-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-capacity-scenario-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-customer-contact-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-contact-import-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-contact-import-fix-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-asset-export-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-inspection-template-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-inspection-worksheet-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-inspection-source-collection-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-backup-plan-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-change-notice-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-notification-preference-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-project-identity-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-identity-compatibility-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-raw-monitoring-event-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-monitoring-quarantine-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-export-inbox-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-runtime-configuration-migration.json').read_text())['sha256'])
manifest.update(json.loads((root/'tools/niko-test/frozen-ticket-lifecycle-migration.json').read_text())['sha256'])
for name,expected in manifest.items():
 source=root/'server/persistence/src/main/resources/db/migration'/name
 compiled=root/'server/persistence/target/classes/db/migration'/name
 if hashlib.sha256(source.read_bytes()).hexdigest()!=expected or source.read_bytes()!=compiled.read_bytes():raise SystemExit('Frozen migration source/build mismatch; no database action')
runtime=json.loads((root/'.local/niko-test/private/runtime.json').read_text());env=os.environ.copy()
for name in ['SPRING_DATASOURCE_URL','MOP_MIGRATION_USERNAME','MOP_MIGRATION_PASSWORD']:env[name]=runtime['environment'][name]
java=root.parent/'mop-toolchain/jdk-17.0.20.1+1/bin';cp=os.pathsep.join([str(root/'.local/niko-test'),str(root/'server/persistence/target/classes'),str(root/'server/domain/target/classes'),str(root/'.local/niko-test/java-libs/*')])
if sys.argv[1:] == ['--initialize-new-niko-database']:
 subprocess.run([str(java/'javac'),'-cp',cp,'-d',str(root/'.local/niko-test'),str(root/'tools/niko-test/NikoSchemaBootstrap.java')],check=True,env=env)
 args=[str(java/'java'),'-cp',cp,'NikoSchemaBootstrap']+sys.argv[1:]
elif sys.argv[1:] in [['--apply-ticket-lifecycle'],['--apply-export-inbox'],['--apply-runtime-configuration'],['--apply-monitoring-quarantine'],['--apply-raw-monitoring-events'],['--check'],['--apply-photos'],['--apply-asset-imports'],['--apply-asset-dependencies'],['--apply-worklogs'],['--apply-asset-metadata'],['--apply-internal-notes'],['--apply-ticket-comments'],['--apply-working-calendars'],['--apply-manual-incidents'],['--apply-manual-recoveries'],['--apply-manual-warranty'],['--apply-capacity-scenarios'],['--apply-customer-contacts'],['--apply-contact-imports'],['--apply-contact-import-fix'],['--apply-asset-exports'],['--apply-inspection-templates'],['--apply-inspection-worksheets'],['--apply-inspection-source-collections'],['--apply-backup-plan-drafts'],['--apply-change-notice-drafts'],['--apply-notification-preference-drafts'],['--apply-project-identity'],['--apply-identity-compatibility']]:args=[str(java/'java'),'-cp',cp,'cn.mop.persistence.TestSchemaMigration']+sys.argv[1:]
else:raise SystemExit('Explicit migration mode required')
raise SystemExit(subprocess.run(args,env=env).returncode)

