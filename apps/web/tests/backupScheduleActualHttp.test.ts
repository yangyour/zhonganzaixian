import {it,expect} from 'vitest'
import capture from '../../../fixtures/backup-calendar-http-synthetic-20261021.json'
import {readBackupSchedulePreview} from '../src/utils/backupSchedulePreview'
import type {BackupPlanDetail} from '../src/utils/backupPlanDrafts'
// Actual payloads from disposable PG/API and synthetic accounts, not browser automation.
it.each(capture.captures)('reads actual date $date at draft v$plan.version',item=>{const result=readBackupSchedulePreview(item.response,item.plan as BackupPlanDetail,item.date);expect(result.candidate_instant).toBe(item.response.candidate_instant);expect(result.resolution).toBe(item.response.resolution);expect(item.response.creates_run).toBe(false)})
