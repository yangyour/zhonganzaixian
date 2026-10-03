/** Reading an accepted registration to its durable ticket and resolving local photos precede voluntary context loss. */
export function reportContextUnresolved(value: { submitting: boolean; uncertain: boolean; accepted: boolean; complete: boolean; appending: boolean; appendUncertain: boolean; unresolvedPhotos: boolean }) {
  return value.submitting || value.uncertain || value.accepted && !value.complete || value.appending || value.appendUncertain || value.unresolvedPhotos
}
