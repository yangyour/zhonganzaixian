<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { navigationEntryName, type NavigationModule } from '../utils/navigationModules'
import NavigationIcon from './NavigationIcon.vue'
defineProps<{ module: NavigationModule; collapsed?: boolean }>()
const emit = defineEmits<{ navigate: [name: string] }>()
const route = useRoute(), router = useRouter()
</script>
<template>
  <div class="sidebar-section">
    <div class="sidebar-section-heading"><span>{{ module.title }}</span><small>{{ module.items.length }} 项功能</small></div>
    <nav class="secondary-navigation" :aria-label="module.title + '功能导航'">
      <a v-for="item in module.items" :key="item.name" :href="router.resolve({ name: item.name }).href" :data-page="item.name" :aria-label="item.title" :class="{ active: navigationEntryName(route.name) === item.name }" :aria-current="navigationEntryName(route.name) === item.name ? 'page' : undefined" :title="collapsed ? item.title : undefined" @click.prevent="emit('navigate', item.name)">
        <NavigationIcon :name="item.name" /><span>{{ item.title }}</span>
      </a>
    </nav>
  </div>
</template>
