import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { join } from 'node:path';
const root = fileURLToPath(new URL('../', import.meta.url));
const components = [];
for (const [workspace, directory] of [['checks', root], ['web', join(root, 'apps', 'web')]]) {
  const lock = JSON.parse(readFileSync(join(directory, 'package-lock.json'), 'utf8'));
  for (const [path, item] of Object.entries(lock.packages).filter(([path]) => path)) {
    const file = join(directory, path, 'package.json');
    const installed = existsSync(file) ? JSON.parse(readFileSync(file, 'utf8')) : {};
    components.push({ workspace, package_path: path, name: installed.name ?? path.split('node_modules/').at(-1), version: item.version, dev: item.dev ?? false, license: installed.license ?? item.license ?? null, integrity: item.integrity ?? null, source: item.resolved ?? null, approval_status: 'candidate_local_validation_only' });
  }
}
const target = join(root, 'docs', 'evidence');
mkdirSync(target, { recursive: true });
writeFileSync(join(target, 'npm-components.json'), JSON.stringify({ status: 'candidate_not_customer_approved', components }, null, 2) + '\n');
console.log(`记录${components.length}个锁定包；客户许可审批尚未执行。`);
