/** Detach plain business snapshots from command state; keep the opaque error's class/status identity. */
export function detachedWorkspaceState<T extends { error: unknown }>(state: T): T {
  const { error, ...data } = state
  return { ...structuredClone(data), error } as T
}
