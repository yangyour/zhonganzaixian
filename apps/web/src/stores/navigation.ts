import { ref, shallowRef } from 'vue'
export const navigating = ref(false)
export const navigationError = shallowRef<unknown>(null)
export const navigationTarget = ref('')
