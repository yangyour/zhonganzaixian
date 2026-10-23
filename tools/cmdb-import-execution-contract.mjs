// Atomic source-backed draft import; preserved extensions do not imply CMDB relationships.
export function addCmdbImportExecutionContract(contract) {
  const manualCreate = contract.paths['/assets'].post;
  manualCreate.description = (manualCreate.description ?? '') + 'Asset creation requires an active published tenant.contract_device_quota. Missing, disabled or expired quota rejects with 422; zero/full quota rejects with 409 ASSET_QUOTA_EXCEEDED. All registered drafts consume a device slot. The quota publication lock and tenant admission lock are held through asset insertion and receipt commit.';
  manualCreate['x-mop-device-quota-configuration-required'] = true;
  manualCreate['x-mop-atomic-device-quota'] = 'REGISTERED_ASSET_ROWS';
  contract.paths['/assets/import-sources/{id}/cmdb-imports'] = {
  "post": {
    "summary": "Atomically register asset drafts from a trusted 33-column CMDB source",
    "description": "Creator only; current padmin/msp role and assets:write required, including replay. Rechecks active approved tenant releases for asset.import_steps, asset.ip_scope and tenant.contract_device_quota. Source lock, trusted references, duplicates, quota, assets, metadata, immutable success audit and idempotency receipt share one transaction. Any error rolls back the whole batch. A new key cannot import a consumed source again. Extension columns require explicit preservation in the immutable source. No monitoring, notifications or production approval.",
    "operationId": "executeCmdbSourceImport",
    "parameters": [
      {
        "name": "id",
        "in": "path",
        "required": true,
        "schema": {
          "type": "string",
          "format": "uuid"
        }
      },
      {
        "name": "Idempotency-Key",
        "in": "header",
        "required": true,
        "schema": {
          "type": "string",
          "pattern": "^[A-Za-z0-9._:-]{1,128}$"
        }
      },
      {
        "name": "X-CSRF-Token",
        "in": "header",
        "required": true,
        "schema": {
          "type": "string"
        }
      }
    ],
    "requestBody": {
      "required": true,
      "content": {
        "application/json": {
          "schema": {
            "type": "object",
            "additionalProperties": false,
            "required": [
              "expected_version",
              "source_parse_hash"
            ],
            "properties": {
              "expected_version": {
                "type": "integer",
                "minimum": 1,
                "maximum": 9007199254740990
              },
              "source_parse_hash": {
                "type": "string",
                "pattern": "^[0-9a-f]{64}$"
              }
            }
          }
        }
      }
    },
    "responses": {
      "200": {
        "description": "Persisted draft receipt, or the original authorized replay.",
        "content": {
          "application/json": {
            "schema": {
              "type": "object",
              "required": [
                "data",
                "trace_id"
              ],
              "properties": {
                "data": {
                  "type": "object",
                  "properties": {
                    "source_id": {
                      "type": "string",
                      "format": "uuid"
                    },
                    "source_version": {
                      "type": "integer",
                      "minimum": 0
                    },
                    "artifact_id": {
                      "type": "string",
                      "format": "uuid"
                    },
                    "parse_hash": {
                      "type": "string"
                    },
                    "tenant_id": {
                      "type": "string",
                      "format": "uuid"
                    },
                    "status": {
                      "const": "IMPORTED_DRAFTS"
                    },
                    "assets": {
                      "type": "array",
                      "minItems": 1,
                      "maxItems": 500,
                      "items": {
                        "type": "object",
                        "required": [
                          "record",
                          "source_row",
                          "asset_id",
                          "asset_version"
                        ],
                        "properties": {
                          "record": {
                            "type": "integer",
                            "minimum": 0
                          },
                          "source_row": {
                            "type": "integer",
                            "minimum": 0
                          },
                          "asset_id": {
                            "type": "string",
                            "format": "uuid"
                          },
                          "asset_version": {
                            "type": "integer",
                            "minimum": 0
                          }
                        }
                      }
                    },
                    "imported_count": {
                      "type": "integer",
                      "minimum": 1,
                      "maximum": 500
                    },
                    "configuration_releases": {
                      "type": "object",
                      "description": "Current tenant publication IDs and versions for all three rules."
                    },
                    "quota": {
                      "type": "object",
                      "required": [
                        "limit",
                        "before",
                        "after"
                      ],
                      "properties": {
                        "limit": {
                          "type": "integer",
                          "minimum": 0
                        },
                        "before": {
                          "type": "integer",
                          "minimum": 0
                        },
                        "after": {
                          "type": "integer",
                          "minimum": 0
                        }
                      }
                    },
                    "extended_attributes": {
                      "const": "RETAINED_IN_IMMUTABLE_SOURCE"
                    },
                    "monitoring_started": {
                      "const": false
                    },
                    "production_approved": {
                      "const": false
                    }
                  },
                  "required": [
                    "source_id",
                    "source_version",
                    "artifact_id",
                    "parse_hash",
                    "tenant_id",
                    "status",
                    "assets",
                    "imported_count",
                    "configuration_releases",
                    "quota",
                    "extended_attributes",
                    "monitoring_started",
                    "production_approved"
                  ]
                },
                "trace_id": {
                  "type": "string"
                }
              }
            }
          }
        }
      },
      "400": {
        "description": "Malformed, duplicate, oversized or unknown input; query overrides rejected."
      },
      "401": {
        "description": "Session or membership version expired."
      },
      "403": {
        "description": "CSRF, current capability or internal role denied."
      },
      "404": {
        "description": "Source or referenced site/member does not exist or is not visible."
      },
      "409": {
        "description": "Version/idempotency conflict, consumed source, duplicate asset/address or quota exceeded."
      },
      "422": {
        "description": "Source, row or published rule is invalid or incomplete; no inferred business defaults."
      },
      "503": {
        "description": "Database or trusted artifact unavailable; entire batch rolled back."
      }
    }
  }
};
}
