<script setup lang="ts">
import { computed } from 'vue'
import { can } from '../stores/session'
const props = defineProps<{ capability: string }>()
const permitted = computed(() => can(props.capability))
</script>
<template><slot v-if="permitted" /><slot v-else name="denied" /></template>
