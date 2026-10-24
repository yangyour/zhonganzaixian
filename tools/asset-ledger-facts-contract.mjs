/** Public data allowlist is separate from internal metadata editing and monitoring observations. */
export function addAssetLedgerFactsContract(contract) {
  const uuid = { type: 'string', format: 'uuid' };
  const integer = { type: 'integer', minimum: 1, maximum: 9007199254740991 };
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const fact = object({
    asset_id: uuid, asset_version: integer, site_id: { type: 'string', minLength: 1, maxLength: 64 }, site_name: { type: 'string', minLength: 1, maxLength: 100 },
    metadata_version: { anyOf: [{ ...integer, minimum: 2 }, { type: 'null' }] }, metadata_recorded_at: { type: ['string', 'null'], format: 'date-time' },
    metadata_source: { enum: ['NOT_RECORDED', 'MANUAL_DECLARATION'] }, business_description: { type: ['string', 'null'], maxLength: 200 },
    owner: { anyOf: [{ type: 'null' }, object({ id: uuid, display_name: { type: 'string', minLength: 1, maxLength: 100 }, status: { enum: ['ACTIVE', 'INACTIVE'] } })] },
  });
  const result = object({ tenant_id: uuid, observed_at: { type: 'string', format: 'date-time' }, items: { type: 'array', minItems: 1, maxItems: 100, items: fact },
    source: { const: 'postgresql' }, scope: { const: 'CURRENT_TENANT_PUBLIC_ASSET_FIELDS' }, business_relationship_verified: { const: false }, proxy_verified: { const: false } });
  contract.paths['/assets/ledger-facts'] = { get: {
    operationId: 'readAssetLedgerPublicFacts', summary: 'Read bounded authorized public ledger declarations and their exact asset/revision identities',
    description: 'One read-only repeatable snapshot. Up to 100 explicit unique IDs; any foreign or missing asset returns 404 for the entire batch. Excludes internal notes, full snapshots, credentials, pricing and contact addresses. Business text remains an unverified declaration. Clients must match asset versions before attaching facts to a separately read ledger.',
    'x-mop-capabilities': ['assets:read'], 'x-mop-read-only': true,
    parameters: [{ name: 'ids', in: 'query', required: true, schema: { type: 'string', minLength: 36, maxLength: 3699 }, description: 'Comma-separated canonical lowercase UUIDs, no tenant/field override.' }],
    responses: { 200: { description: 'Bound public field facts; null and NOT_RECORDED remain explicit.', content: { 'application/json': { schema: object({ data: result, trace_id: { type: 'string' } }) } } },
      ...Object.fromEntries([400, 401, 403, 404, 503].map(status => [status, { description: 'Invalid, unauthorized, hidden or unavailable; no partial/old substitute data.', content: { 'application/json': { schema: { $ref: '#/components/schemas/Error' } } } }])) },
  } };
}
