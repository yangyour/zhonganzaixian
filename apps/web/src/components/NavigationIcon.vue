<script setup lang="ts">
import { computed } from 'vue'
import { Monitor, Tickets, Tools, OfficeBuilding, TrendCharts, Setting, DataAnalysis, Box, Connection, Bell, Document, Download, Message, Clock, User, Calendar, Files, List, Key, Search } from '@element-plus/icons-vue'
const props = defineProps<{ name: string }>()
const icons = { resources: Monitor, service: Tickets, maintenance: Tools, customers: OfficeBuilding, operations: TrendCharts, settings: Setting, dashboard: DataAnalysis, assets: Box, 'customer-topology': Connection, 'monitoring-onboarding': Tools, 'monitoring-events': Bell, 'network-monitoring': Connection, 'threshold-diagnostics': Bell, 'observation-window-diagnostics': Bell, tickets: Tickets, 'manual-incidents': Document, 'sla-scenarios': Clock, 'asset-export-jobs': Download, 'my-export-messages': Message, 'my-worklogs': Clock, 'my-notification-preferences': Bell, 'backup-plan-drafts': Files, 'change-notice-drafts': Calendar, 'inspection-templates': List, 'inspection-worksheets': Document, 'inspection-source-collections': Files, 'tenant-inventory': OfficeBuilding, 'customer-contacts': User, 'contact-imports': Download, 'operational-comparison': TrendCharts, 'capacity-estimates': DataAnalysis, 'identity-users': User, 'identity-roles': Key, 'identity-settings': Setting, configurations: Files, 'capability-catalog': Search, audit: List }
const icon = computed(() => icons[props.name as keyof typeof icons] || Document)
</script>
<template><component :is="icon" class="navigation-icon" aria-hidden="true" /></template>
