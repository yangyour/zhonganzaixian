/** Display-only grouping of an already verified API input echo. Does not replace service calculations. */
export function operationalSampleDistribution(values: readonly (string | null)[] | null) {
  if (values === null) return null
  const known: bigint[] = [], rows = values.map((value, index) => {
    if (value !== null) { if (!/^(0|[1-9][0-9]{0,39})$/.test(value)) throw new Error('样本不是有效整数'); known.push(BigInt(value)) }
    return { index: index + 1, value }
  })
  if (!known.length) return { rows, known: 0, unknown: values.length, min: null, max: null, bins: [] as { from: string; to: string; count: number }[] }
  let min = known[0]!, max = known[0]!
  for (const value of known) { if (value < min) min = value; if (value > max) max = value }
  const step = (max - min + 5n) / 5n, bins = []
  for (let from = min; from <= max; from += step) bins.push({ from: from.toString(), to: (from + step - 1n > max ? max : from + step - 1n).toString(), count: 0 })
  for (const value of known) bins[Number((value - min) / step)]!.count++
  return { rows, known: known.length, unknown: values.length - known.length, min: min.toString(), max: max.toString(), bins }
}
