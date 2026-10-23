<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { navigationEntryName, type NavigationModule } from '../utils/navigationModules'
import NavigationIcon from './NavigationIcon.vue'
import { navigateFromLink } from '../utils/linkNavigation'
defineProps<{ module: NavigationModule; collapsed?: boolean }>()
const emit = defineEmits<{ navigate: [name: string] }>()
const route = useRoute(), router = useRouter()
</script>
<template>
  <div class="sidebar-section">
    <div class="sidebar-section-heading"><span>{{ module.title }}</span></div>
    <nav class="secondary-navigation" :aria-label="module.title + '功能导航'">
      <template v-for="(item,index) in module.items" :key="item.name"><div v-if="item.section && item.section !== module.items[index-1]?.section && !collapsed" class="sidebar-group-label">{{ item.section }}</div>
      <a :href="router.resolve({ name: item.name }).href" :data-page="item.name" :aria-label="item.title" :class="{ active: navigationEntryName(route.name) === item.name }" :aria-current="navigationEntryName(route.name) === item.name ? 'page' : undefined" :title="collapsed ? item.title : undefined" @click="navigateFromLink($event, () => emit('navigate', item.name))">
        <NavigationIcon :name="item.name" /><span>{{ item.title }}</span>
      </a></template>
    </nav>
  </div>
</template>

<style scoped>
.sidebar-group-label{font-size:12px;color:#526276;padding:10px 12px 2px;line-height:20px}
</style>
