/** Detach plain business snapshots; only known top-level diagnostic errors retain class/status identity. */
export function detachedReadState<T extends object>(state: T): T {
  const data = { ...state } as Record<string, unknown>, errors: Record<string, unknown> = {}
  for (const key of ['error', 'proofError']) if (Object.hasOwn(data, key)) { errors[key] = data[key]; delete data[key] }
  return Object.assign(structuredClone(data), errors) as T
}
export function detachedWorkspaceState<T extends { error: unknown }>(state: T): T { return detachedReadState(state) }
