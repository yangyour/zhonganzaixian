import { ref } from 'vue'
import type { ResumePhase } from '../utils/pageResumeGuard'
export const resumePhase = ref<ResumePhase>('ready')
export const resumeSession = { retry: async (): Promise<boolean> => false }
