# Pure validation only: no private configuration, process, network or database access.
function Get-MopMigrationMode {
    param([switch]$ApplyPhotos, [switch]$ApplyAssetImports, [string]$ApplyFeature)
    $features = @{
        'monitoring-event-center' = '--apply-monitoring-event-center'; 'oncall-escalation' = '--apply-oncall-escalation'; 'service-tiers' = '--apply-service-tiers'; 'notification-intents' = '--apply-notification-intents'; 'ticket-workflow' = '--apply-ticket-workflow'; 'export-inbox' = '--apply-export-inbox'; 'runtime-configuration' = '--apply-runtime-configuration'
        'asset-dependencies' = '--apply-asset-dependencies'; 'worklogs' = '--apply-worklogs'
        'asset-metadata' = '--apply-asset-metadata'; 'internal-notes' = '--apply-internal-notes'
        'ticket-comments' = '--apply-ticket-comments'; 'working-calendars' = '--apply-working-calendars'
        'manual-incidents' = '--apply-manual-incidents'; 'manual-recoveries' = '--apply-manual-recoveries'
        'manual-warranty' = '--apply-manual-warranty'; 'capacity-scenarios' = '--apply-capacity-scenarios'; 'customer-contacts' = '--apply-customer-contacts'; 'contact-imports' = '--apply-contact-imports'; 'contact-import-fix' = '--apply-contact-import-fix'; 'asset-exports' = '--apply-asset-exports'; 'inspection-templates' = '--apply-inspection-templates'; 'inspection-worksheets' = '--apply-inspection-worksheets'; 'inspection-source-collections' = '--apply-inspection-source-collections'; 'backup-plan-drafts' = '--apply-backup-plan-drafts'; 'change-notice-drafts' = '--apply-change-notice-drafts'; 'notification-preference-drafts' = '--apply-notification-preference-drafts'; 'project-identity' = '--apply-project-identity'; 'identity-compatibility' = '--apply-identity-compatibility'; 'raw-monitoring-events' = '--apply-raw-monitoring-events'; 'monitoring-quarantine' = '--apply-monitoring-quarantine'
    }
    $requested = [int][bool]$ApplyPhotos + [int][bool]$ApplyAssetImports + [int](-not [string]::IsNullOrEmpty($ApplyFeature))
    if ($requested -gt 1) { throw '每次只能显式执行一个功能迁移，不能组合 Apply 参数。' }
    if ($ApplyFeature) {
        if (-not $features.ContainsKey($ApplyFeature)) { throw '未知功能迁移；拒绝访问数据库。' }
        return $features[$ApplyFeature]
    }
    if ($ApplyPhotos) { return '--apply-photos' }
    if ($ApplyAssetImports) { return '--apply-asset-imports' }
    return '--check'
}

function Assert-MopMigrationResources {
    param([Parameter(Mandatory)][string]$ProjectRoot, [Parameter(Mandatory)][string]$PersistenceClasses)
    # Reuse the already reviewed, committed hashes; source and compiled SQL must both match.
    $manifests = @('frozen-migrations.json', 'frozen-dependency-migration.json', 'frozen-worklog-migration.json',
        'frozen-asset-metadata-migration.json', 'frozen-internal-note-migration.json', 'frozen-ticket-comment-migration.json',
        'frozen-working-calendar-migration.json', 'frozen-manual-incident-migration.json',
        'frozen-manual-recovery-migration.json', 'frozen-manual-warranty-migration.json', 'frozen-capacity-scenario-migration.json', 'frozen-customer-contact-migration.json', 'frozen-contact-import-migration.json', 'frozen-contact-import-fix-migration.json', 'frozen-asset-export-migration.json', 'frozen-inspection-template-migration.json', 'frozen-inspection-worksheet-migration.json', 'frozen-inspection-source-collection-migration.json', 'frozen-backup-plan-migration.json', 'frozen-change-notice-migration.json', 'frozen-notification-preference-migration.json', 'frozen-project-identity-migration.json', 'frozen-identity-compatibility-migration.json', 'frozen-raw-monitoring-event-migration.json', 'frozen-monitoring-quarantine-migration.json', 'frozen-export-inbox-migration.json', 'frozen-runtime-configuration-migration.json', 'frozen-ticket-workflow-migration.json', 'frozen-ticket-notification-migration.json', 'frozen-service-tier-migration.json', 'frozen-oncall-escalation-migration.json', 'frozen-monitoring-event-center-migration.json')
    $expected = @{}
    $versions = @{}
    foreach ($file in $manifests) {
        $manifest = Get-Content -Raw -LiteralPath (Join-Path $ProjectRoot "tools/niko-test/$file") -ErrorAction Stop | ConvertFrom-Json -ErrorAction Stop
        foreach ($entry in $manifest.sha256.PSObject.Properties) {
            if ($entry.Name -cnotmatch '^V([1-9][0-9]?)__[a-z0-9_]+\.sql$' -or $entry.Value -cnotmatch '^[a-f0-9]{64}$') { throw '冻结清单条目无效。' }
            $version = [int]([regex]::Match($entry.Name, '^V([0-9]+)__').Groups[1].Value)
            if ($version -gt 37 -or $versions.ContainsKey($version) -or $expected.ContainsKey($entry.Name)) { throw '冻结迁移版本重复或超出已审核范围。' }
            $versions[$version] = $true
            $expected[$entry.Name] = $entry.Value
        }
    }
    if ($expected.Count -ne 37) { throw '冻结迁移必须完整覆盖 V1–V37。' }
    foreach ($directory in @((Join-Path $ProjectRoot 'server/persistence/src/main/resources/db/migration'), (Join-Path $PersistenceClasses 'db/migration'))) {
        $files = @(Get-ChildItem -LiteralPath $directory -File -ErrorAction Stop)
        if ($files.Count -ne $expected.Count) { throw '源码或构建迁移集合与冻结清单不一致。' }
        foreach ($file in $files) {
            if (-not $expected.ContainsKey($file.Name) -or (Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash -ne $expected[$file.Name]) {
                throw '源码或构建迁移字节与已审核冻结摘要不一致；拒绝操作。'
            }
        }
    }
}
