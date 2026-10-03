import { ApiError } from '../api/client'
/** A denied replay says nothing about whether a previous unknown write committed. */
export function writeFailureUncertain(previouslyUncertain: boolean, cause: unknown): boolean {
 return previouslyUncertain || !(cause instanceof ApiError) || cause.status < 400 || cause.status >= 500 || cause.code.startsWith('INVALID_')
}
