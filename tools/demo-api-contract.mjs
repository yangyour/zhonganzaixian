/** Preserve the actual 0.84 isolated-demo API in deterministic contract generation. */
export function addDemoApiContract(contract) {
  contract.components.schemas.SessionResult.properties.data.properties.demo_examples_enabled = {
  "type": "boolean",
  "const": true,
  "description": "Only explicitly configured demo tenants; absence means no demo example loading."
};
  contract.components.schemas.DemoPageExampleResult = {
  "type": "object",
  "required": [
    "data",
    "trace_id"
  ],
  "additionalProperties": false,
  "properties": {
    "data": {
      "type": "object",
      "additionalProperties": false,
      "required": [
        "tenant_id",
        "actor_id",
        "page",
        "available",
        "source",
        "input",
        "business_data_written",
        "provider_accessed"
      ],
      "properties": {
        "tenant_id": {
          "type": "string",
          "format": "uuid"
        },
        "actor_id": {
          "type": "string",
          "format": "uuid"
        },
        "page": {
          "type": "string",
          "enum": [
            "sla-scenarios",
            "sla-reports",
            "operational-comparison",
            "capacity-estimates",
            "observation-window-diagnostics",
            "threshold-diagnostics",
            "contact-import-preview"
          ]
        },
        "available": {
          "type": "boolean"
        },
        "source": {
          "const": "EXPLICIT_DEMO_INPUT"
        },
        "input": {
          "type": [
            "object",
            "null"
          ]
        },
        "business_data_written": {
          "const": false
        },
        "provider_accessed": {
          "const": false
        }
      },
      "allOf": [
        {
          "if": {
            "properties": {
              "available": {
                "const": false
              }
            }
          },
          "then": {
            "properties": {
              "input": {
                "type": "null"
              }
            }
          }
        }
      ]
    },
    "trace_id": {
      "type": "string"
    }
  }
};
  contract.paths['/demo/examples/{page}'] = {
  "get": {
    "operationId": "readExplicitDemoPageExample",
    "summary": "读取显式授权演示租户的假设输入；实际租户无示例，未写业务或访问供应商",
    "parameters": [
      {
        "name": "page",
        "in": "path",
        "required": true,
        "schema": {
          "type": "string",
          "enum": [
            "sla-scenarios",
            "sla-reports",
            "operational-comparison",
            "capacity-estimates",
            "observation-window-diagnostics",
            "threshold-diagnostics",
            "contact-import-preview"
          ]
        }
      }
    ],
    "x-mop-read-only": true,
    "x-mop-current-membership-recheck": true,
    "x-mop-demo-namespace-allowlist": true,
    "x-mop-capability-by-page": {
      "sla-scenarios": "sla_scenarios:read",
      "sla-reports": "sla_scenarios:read",
      "operational-comparison": "operations:compare_explicit",
      "capacity-estimates": "capacity:estimate",
      "observation-window-diagnostics": "configurations:read",
      "threshold-diagnostics": "configurations:read",
      "contact-import-preview": "contacts:write"
    },
    "responses": {
      "200": {
        "description": "Explicit test inputs only",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/DemoPageExampleResult"
            }
          }
        }
      },
      "401": {
        "description": "Rejected authentication, scope or page",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/Error"
            }
          }
        }
      },
      "403": {
        "description": "Rejected authentication, scope or page",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/Error"
            }
          }
        }
      },
      "404": {
        "description": "Rejected authentication, scope or page",
        "content": {
          "application/json": {
            "schema": {
              "$ref": "#/components/schemas/Error"
            }
          }
        }
      }
    }
  }
};
  const operations = [
  [
    "/wecom/status",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/check",
    "post",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/bindings",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/bindings/{userId}",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/bindings/{userId}",
    "put",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/test-messages",
    "post",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/deliveries",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/wecom/deliveries/{id}",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/status",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/check",
    "post",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/bindings",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/bindings/{userId}",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/bindings/{userId}",
    "put",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/test-messages",
    "post",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/deliveries",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ],
  [
    "/email/deliveries/{id}",
    "get",
    "Explicit demo allowlist uses isolated binding keys; only inactive example.invalid/DEMO_ bindings; provider I/O and dispatch rejected before enqueue."
  ]
];
  for (const [path, method, scope] of operations) {
    if (!contract.paths[path]?.[method]) throw new Error('Missing channel contract for demo scope');
    contract.paths[path] = { ...contract.paths[path], [method]: { ...contract.paths[path][method], 'x-mop-demo-disabled-scope': scope } };
  }
}
