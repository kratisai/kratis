/// <reference types="node" />

import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { z } from 'zod'

import { CLIENT_METHODS, type ClientMethod } from '@/protocol/client-methods'
import { CLIENT_RESULTS } from '@/protocol/client-results'

const PROJECT_ROOT = resolve(process.cwd())
const PROTOCOL_DIR = resolve(PROJECT_ROOT, '../protocol')

interface JsonSchema {
  additionalProperties?: boolean
  enum?: unknown[]
  items?: JsonSchema
  properties?: Record<string, JsonSchema>
  required?: string[]
  type?: string
}

interface OpenRpcMethod {
  name: string
  params: Array<{ name: string; required?: boolean; schema: JsonSchema & { $ref?: string } }>
  'x-kratis-direction': string
  'x-kratis-message-kind': string
}

function compareToJsonSchema(
  methodName: string,
  zodSchema: z.ZodType<unknown>,
  jsonSchema: JsonSchema,
): void {
  const jsonProperties = new Set(Object.keys(jsonSchema.properties ?? {}))
  const zodFields = zodFieldNames(zodSchema)

  const zodFieldSet = new Set(zodFields)
  expect(
    zodFieldSet,
    `Method ${methodName}: record fields must exactly match JSON Schema properties`,
  ).toEqual(jsonProperties)

  const required = new Set(jsonSchema.required ?? [])
  for (const field of zodFields) {
    const fieldSchema = zodSchema instanceof z.ZodObject ? zodSchema.shape[field] : zodSchema
    const isOptional = isZodOptional(fieldSchema)
    if (required.has(field)) {
      expect(isOptional).toBe(false)
    }
  }

  for (const [property, propertySchema] of Object.entries(jsonSchema.properties ?? {})) {
    if (propertySchema.enum && propertySchema.enum.length > 0) {
      const fieldSchema =
        zodSchema instanceof z.ZodObject
          ? (zodSchema.shape as Record<string, z.ZodType<unknown>>)[property]
          : zodSchema
      // A schema enum must be backed by z.enum([...]) — not z.string() — so that
      // the closed set is enforced at the type level, not just as a documentation hint.
      expect(
        zodSchemaType(fieldSchema),
        `Method ${methodName}: schema enum field "${property}" must use z.enum(), not z.string()`,
      ).toBe('enum')
    }
  }
}

function getZodDef(schema: z.ZodType<unknown>): Record<string, unknown> & { typeName?: string } {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return (schema as any)._def ?? {}
}

function isZodOptional(schema: z.ZodType<unknown>): boolean {
  return getZodDef(schema).typeName === 'ZodOptional'
}

function loadSchemaFromRef($ref: string, baseDir: string): JsonSchema {
  if (!$ref) {
    throw new Error('Schema $ref is missing')
  }
  const path = resolve(baseDir, $ref)
  return readJson<JsonSchema>(path)
}

function readJson<T>(path: string): T {
  return JSON.parse(readFileSync(path, 'utf-8')) as T
}

function resolveProtocolDir(protocolName: 'client' | 'environment'): string {
  const candidates = [
    resolve(PROTOCOL_DIR, protocolName),
    resolve(PROJECT_ROOT, '../protocol', protocolName),
  ]
  for (const candidate of candidates) {
    if (existsSync(candidate)) {
      return candidate
    }
  }
  throw new Error(`Cannot locate protocol/${protocolName} directory`)
}

function unwrapZodOptional(schema: z.ZodType<unknown>): z.ZodType<unknown> {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  return isZodOptional(schema) ? (schema as any).unwrap() : schema
}

function zodFieldNames(schema: z.ZodType<unknown>): string[] {
  const def = getZodDef(schema)
  if (def.typeName === 'ZodObject') {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    return Object.keys((schema as any).shape)
  }
  if (def.typeName === 'ZodUnion') {
    // Ping is void | Record<never> - treat as no fields
    return []
  }
  return []
}

function zodSchemaType(schema: z.ZodType<unknown>): string {
  const unwrapped = unwrapZodOptional(schema)
  const def = getZodDef(unwrapped)
  switch (def.typeName) {
    case 'ZodArray':
      return 'array'
    case 'ZodBoolean':
      return 'boolean'
    case 'ZodEnum':
      return 'enum'
    case 'ZodNumber':
      return 'integer'
    case 'ZodObject':
    case 'ZodRecord':
      return 'object'
    case 'ZodString':
      return 'string'
    case 'ZodUnion':
      return 'object'
    default:
      return 'unknown'
  }
}

describe('Protocol Conformance', () => {
  const protocolDir = resolveProtocolDir('client')
  const openrpc = readJson<{ methods: OpenRpcMethod[] }>(join(protocolDir, 'openrpc.json'))
  const protocolMethodNames = openrpc.methods.map((m) => m.name)

  it('closed collection matches protocol method names exactly', () => {
    const codeMethodNames = Object.keys(CLIENT_METHODS)
    expect(codeMethodNames.sort()).toEqual(protocolMethodNames.sort())
  })

  it('every method has correct direction and message kind', () => {
    for (const method of openrpc.methods) {
      const methodName = method.name as ClientMethod
      const def = CLIENT_METHODS[methodName]
      expect(def).toBeDefined()
      expect(def.direction).toBe(method['x-kratis-direction'])
      expect(def.messageKind).toBe(method['x-kratis-message-kind'])
    }
  })

  it('every params schema matches the JSON Schema definition', () => {
    for (const method of openrpc.methods) {
      const methodName = method.name as ClientMethod
      const def = CLIENT_METHODS[methodName]

      if (method.params.length === 0) {
        // Parameter-less methods should have no required fields (ping/void case)
        expect(zodFieldNames(def.paramsSchema).length).toBe(0)
        continue
      }

      const param = method.params[0]
      const schema = param.schema.$ref
        ? loadSchemaFromRef(param.schema.$ref, protocolDir)
        : param.schema
      compareToJsonSchema(method.name, def.paramsSchema, schema)
    }
  })

  it('valid fixtures round-trip through the typed params records', () => {
    const validDir = join(protocolDir, 'examples', 'valid')
    const files = readdirSync(validDir).filter((name) => name.endsWith('.json'))
    const coveredMethods = new Set<string>()

    for (const file of files) {
      const raw = readJson<{
        id?: number | string
        jsonrpc: string
        method: string
        params?: Record<string, unknown>
      }>(join(validDir, file))
      const methodName = raw.method as ClientMethod
      const def = CLIENT_METHODS[methodName]
      expect(def, `Fixture ${file} references unknown method ${raw.method}`).toBeDefined()

      coveredMethods.add(raw.method)
      if (zodFieldNames(def.paramsSchema).length === 0) {
        expect(raw.params).toBeUndefined()
        continue
      }

      const parsed = def.paramsSchema.parse(raw.params)
      expect(parsed).toBeDefined()
    }

    expect([...coveredMethods].sort()).toEqual(protocolMethodNames.sort())
  })

  it('invalid fixtures are rejected by the typed params records', () => {
    const invalidDir = join(protocolDir, 'examples', 'invalid')
    if (!existsSync(invalidDir)) {
      return
    }
    const files = readdirSync(invalidDir).filter((name) => name.endsWith('.json'))

    for (const file of files) {
      const raw = readJson<{
        id?: number | string
        jsonrpc: string
        method: string
        params?: Record<string, unknown>
      }>(join(invalidDir, file))
      const methodName = raw.method as ClientMethod
      const def = CLIENT_METHODS[methodName]
      if (zodFieldNames(def.paramsSchema).length === 0) {
        continue
      }
      expect(
        () => def.paramsSchema.parse(raw.params),
        `Invalid fixture ${file} must be rejected`,
      ).toThrow()
    }
  })
})

describe('Client Result Protocol Conformance', () => {
  const protocolDir = resolveProtocolDir('client')
  const resultsDir = join(protocolDir, 'schemas', 'results')

  it('closed result registry matches protocol result schemas exactly', () => {
    const schemaTypes = readdirSync(resultsDir)
      .filter((name) => name.endsWith('.schema.json'))
      .map((name) => name.replace(/\.schema\.json$/, ''))
      .sort()
    expect(Object.keys(CLIENT_RESULTS).sort()).toEqual(schemaTypes)
  })

  it('valid result fixtures parse through the closed result registry', () => {
    const validDir = join(protocolDir, 'examples', 'results', 'valid')
    const files = readdirSync(validDir).filter((name) => name.endsWith('.json'))
    const coveredTypes = new Set<string>()

    for (const file of files) {
      const raw = readJson<{ [key: string]: unknown; type: string }>(join(validDir, file))
      const resultSchema = (CLIENT_RESULTS as Record<string, unknown>)[raw.type]
      expect(
        resultSchema,
        `Fixture ${file} references unknown result type ${raw.type}`,
      ).toBeDefined()
      coveredTypes.add(raw.type)

      const parsed = (resultSchema as z.ZodType<unknown>).parse(raw)
      expect(parsed).toBeDefined()
    }

    expect([...coveredTypes].sort()).toEqual(Object.keys(CLIENT_RESULTS).sort())
  })

  it('invalid result fixtures are rejected by the closed result registry', () => {
    const invalidDir = join(protocolDir, 'examples', 'results', 'invalid')
    if (!existsSync(invalidDir)) {
      return
    }
    const files = readdirSync(invalidDir).filter((name) => name.endsWith('.json'))

    for (const file of files) {
      const raw = readJson<{ [key: string]: unknown; type: string }>(join(invalidDir, file))
      const resultSchema = (CLIENT_RESULTS as Record<string, unknown>)[raw.type]
      if (!resultSchema) {
        // Unknown type discriminators are invalid by construction (closed registry).
        expect(Object.keys(CLIENT_RESULTS)).not.toContain(raw.type)
        continue
      }
      expect(
        () => (resultSchema as z.ZodType<unknown>).parse(raw),
        `Invalid result fixture ${file} must be rejected`,
      ).toThrow()
    }
  })
})
