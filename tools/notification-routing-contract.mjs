export function addNotificationRoutingContract(contract) {
  const object = properties => ({ type: 'object', properties, required: Object.keys(properties), additionalProperties: false });
  const nullable = schema => ({ anyOf: [schema, { type: 'null' }] });
  const ref = name => ({ $ref: `#/components/schemas/${name}` });
  const uuid = { type: 'string', format: 'uuid' }, time = { type: 'string', format: 'date-time' };
  const version = { type: 'integer', minimum: -1, maximum: 99999 };
  const channel = { enum: ['EMAIL', 'WECHAT_WORK', 'SMS', 'DINGTALK', 'WEBHOOK', 'VOICE'] };
  const channels = { type: 'array', items: channel, uniqueItems: true, maxItems: 6 };
  const policy = object({ published: { type: 'boolean' }, resource_pool_id: nullable(uuid),
    matrix: nullable(object({ P1: channels, P2: channels, P3: channels, P4: channels })),
    aggregate_seconds: nullable({ type: 'integer', minimum: 0, maximum: 86400 }),
    precedence: nullable({ const: 'MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS' }),
    retry_budget: nullable({ type: 'integer', minimum: 0, maximum: 100 }),
    retry_delay_seconds: nullable({ type: 'integer', minimum: 1, maximum: 86400 }) });
  policy.allOf = [{ if: { properties: { published: { const: true } }, required: ['published'] }, then: { properties: {
    resource_pool_id: uuid, matrix: object({ P1: channels, P2: channels, P3: channels, P4: channels }),
    aggregate_seconds: { type:'integer',minimum:0,maximum:86400 }, precedence: { const:'MATRIX_THEN_PREFERENCE_WITH_P1_QUIET_BYPASS' },
    retry_budget: { type:'integer',minimum:0,maximum:100 }, retry_delay_seconds: { type:'integer',minimum:1,maximum:86400 }
  } } }];
  const state = { type: 'object', properties: { external_dispatch_enabled: { const: false }, customer_production_approved: { const: false } },
    description: 'Versioned internal routing state. No secrets, raw provider payloads or external-send permission. REQUEST/ROUTE states retain source identity, current decision and original attempt metadata.' };
  const entry = object({ kind: { enum: ['POLICY', 'PREFERENCE', 'BINDING', 'REQUEST', 'ROUTE', 'RECEIPT'] }, id: uuid,
    version: { type: 'integer', minimum: 0, maximum: 100000 }, command_id: uuid, state, recorded_at: time });
  const envelope = data => object({ data, trace_id: { type: 'string' } });
  Object.assign(contract.components.schemas, {
    NotificationRoutingPolicy: policy,
    NotificationControlEntry: entry,
    NotificationControlResult: envelope(entry),
    NotificationPolicyWrite: object({ expected_version: version, policy }),
    NotificationPreferencePublicationWrite: object({ expected_version: version, draft_id: uuid,
      draft_version: { type: 'integer', minimum: 1, maximum: 9007199254740990 }, published: { type: 'boolean' } }),
    NotificationRoutingBindingWrite: object({ expected_version: version, binding: object({ receiver_id: uuid, channel,
      binding_id: uuid, valid_until: time, active: { type: 'boolean' } }) }),
    NotificationControlPageResult: envelope(object({ items: { type: 'array', items: entry, maxItems: 100 }, next_id: nullable(uuid), next_time: nullable(time), source: { const: 'postgresql' } }))
  });
  const errors = Object.fromEntries([400,401,403,404,409,503].map(code => [code, { description: 'Current scope, configuration, command, version or dependency rejection; unknown outcomes retain the original command.', content: { 'application/json': { schema: ref('Error') } } }]));
  const commandHeaders = [{ name: 'Idempotency-Key', in: 'header', required: true, schema: uuid }, { name: 'X-CSRF-Token', in: 'header', required: true, schema: { type: 'string' } }];
  const path = name => ({ name, in: 'path', required: true, schema: uuid });
  const base = (operationId, summary, response, capability, extra={}) => ({ operationId, summary,
    'x-mop-capability': capability, 'x-mop-current-membership-recheck': true, 'x-mop-external-dispatch': 'DISABLED',
    'x-mop-feature-gate': 'MOP_NOTIFICATION_ROUTING_ENABLED=true plus explicit V51 migration',
    responses: { 200: { description: summary, content: { 'application/json': { schema: response } } }, ...errors }, ...extra });
  const write = (operationId, summary, request, capability, parameters=[]) => base(operationId, summary, ref('NotificationControlResult'), capability,
    { parameters: [...parameters,...commandHeaders], requestBody: { required: true, content: { 'application/json': { schema: request } } } });
  contract.paths['/notification-routing/policy'] = {
    get: base('readNotificationRoutingPolicy', 'Read the current tenant routing policy; missing publication remains null.', envelope(object({ record: nullable(entry), external_dispatch_enabled: { const: false }, customer_production_approved: { const: false } })), 'ticket_notes:read_internal'),
    post: write('saveNotificationRoutingPolicy', 'Save or explicitly publish internal routing values; incomplete drafts cannot be published.', ref('NotificationPolicyWrite'), 'configurations:write')
  };
  contract.paths['/my/notification-preferences/publication'] = {
    get: base('readOwnNotificationPublication', 'Read the current owner preference publication.', envelope(object({ record: nullable(entry), external_dispatch_enabled: { const: false } })), 'notification_preferences:read_own'),
    post: write('publishOwnNotificationPreference', 'Publish or withdraw the exact current V26 owner revision without rewriting its draft history.', ref('NotificationPreferencePublicationWrite'), 'notification_preferences:write_own')
  };
  contract.paths['/notification-routing/bindings'] = { post: write('saveNotificationRoutingBinding', 'Reference an existing verified scoped channel binding; disabled demo bindings cannot authorize routing.', ref('NotificationRoutingBindingWrite'), 'configurations:write') };
  contract.paths['/notification-routing/bindings/{receiver}/{channel}'] = { get: base('readNotificationRoutingBinding', 'Read the scoped receiver/channel routing reference.', envelope(object({ record: nullable(entry) })), 'ticket_notes:read_internal', { parameters: [path('receiver'),{ name:'channel',in:'path',required:true,schema:channel }] }) };
  const kind = { enum: ['REQUEST','ROUTE','BINDING'] };
  contract.paths['/notification-routing/entries'] = { get: base('listNotificationControlEntries', 'Read a bounded page of current durable control states in root creation order.', ref('NotificationControlPageResult'), 'ticket_notes:read_internal', { parameters: [{name:'kind',in:'query',required:true,schema:kind},{name:'page_size',in:'query',schema:{type:'integer',minimum:1,maximum:100,default:25}},{name:'before_time',in:'query',schema:time},{name:'before_id',in:'query',schema:uuid}] }) };
  contract.paths['/notification-routing/entries/{kind}/{id}'] = { get: base('readNotificationControlEntry', 'Read a current tenant control record; foreign objects return 404.', ref('NotificationControlResult'), 'ticket_notes:read_internal', { parameters: [{name:'kind',in:'path',required:true,schema:kind},path('id')] }) };
  contract.paths['/notification-routing/requests/{id}/plan'] = { post: write('planNotificationRoutingRequest', 'Recheck the saved source against current publications, bindings and exact receipt history. No change is an explicit 409.', object({expected_version:{type:'integer',minimum:0,maximum:99999}}), 'configurations:write', [path('id')]) };
  contract.paths['/notification-routing/commands/{id}'] = { get: base('recoverNotificationControlCommand', 'Recover this actor original command receipt under current authority; missing does not prove cancellation.', ref('NotificationControlResult'), 'notification_preferences:read_own', { parameters:[path('id')] }) };
  contract.info.version = '0.85.0';
}
