#!/usr/bin/env node
// Kratis patch for google-gemini/gemini-cli#27738.
//
// gemini-cli's ACP tool runner (packages/cli/src/acp/acpSession.ts, Session.runTool) builds the
// model-facing function response straight from the raw tool result, bypassing
// CoreToolScheduler.truncateOutputIfNeeded and therefore tools.truncateToolOutputThreshold. A
// single large run_shell_command result then reaches the model in full and can exceed the model
// input window, aborting the turn. This patch caps shell output inside the ACP runner and keeps
// the full text on disk. It is idempotent and fails loudly when the anchor disappears (i.e. when
// a gemini-cli upgrade changes the ACP runner).

import { existsSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import { join } from 'node:path'

const MARKER = '__KRATIS_ACP_TOOL_OUTPUT_CAP__'
const ANCHOR = 'const content = toToolCallContent(toolResult);'
const DEFAULT_THRESHOLD = 40000

const SNIPPET = `/* ${MARKER} */
      await (async () => {
        try {
          const output = toolResult && toolResult.llmContent;
          if (typeof output === "string" && fc && fc.name === "run_shell_command") {
            let threshold = ${DEFAULT_THRESHOLD};
            try {
              const getter = this.context && this.context.config && this.context.config.getTruncateToolOutputThreshold;
              const configured = typeof getter === "function" ? getter.call(this.context.config) : void 0;
              if (typeof configured === "number" && configured > 0) threshold = configured;
            } catch (error) {}
            if (output.length > threshold) {
              const headLength = Math.max(1, Math.floor(threshold * 0.2));
              const tailLength = Math.max(1, threshold - headLength);
              let location = "";
              try {
                const fs = await import("node:fs");
                const path = await import("node:path");
                const dir = path.join(process.cwd(), ".kratis", "tool-outputs");
                fs.mkdirSync(dir, { recursive: true });
                const file = path.join(dir, "run_shell_command-" + Date.now() + ".txt");
                fs.writeFileSync(file, output);
                location = " Full output saved to: " + file + ".";
              } catch (error) {}
              const newline = String.fromCharCode(10) + String.fromCharCode(10);
              toolResult.llmContent = output.slice(0, headLength) + newline +
                "[Tool output truncated by Kratis: " + output.length + " characters exceeded the " +
                threshold + " character limit. Showing first " + headLength + " and last " + tailLength +
                " characters." + location + "]" + newline +
                output.slice(output.length - tailLength);
            }
          }
        } catch (error) {}
      })();
      `

export function patchSource(source) {
  if (source.includes(MARKER)) {
    return { status: 'already-patched' }
  }
  if (!source.includes(ANCHOR)) {
    return { status: 'anchor-missing' }
  }
  return { status: 'patched', source: source.replace(ANCHOR, SNIPPET + ANCHOR) }
}

function main() {
  const root = process.argv[2] || join(homedir(), 'gemini', 'node_modules', '@google', 'gemini-cli')
  const bundleDir =
    statSync(root).isDirectory() && existsSync(join(root, 'bundle')) ? join(root, 'bundle') : root

  let patched = 0
  let alreadyPatched = 0
  for (const name of readdirSync(bundleDir).sort()) {
    if (!name.endsWith('.js') && !name.endsWith('.mjs')) continue
    const file = join(bundleDir, name)
    const result = patchSource(readFileSync(file, 'utf8'))
    if (result.status === 'patched') {
      writeFileSync(file, result.source)
      patched += 1
      console.log(`[kratis-gemini-patch] patched ${name}`)
    } else if (result.status === 'already-patched') {
      alreadyPatched += 1
    }
  }

  if (patched === 0 && alreadyPatched === 0) {
    console.error(
      `[kratis-gemini-patch] no ACP tool runner anchor found under ${bundleDir}; gemini-cli changed - update the Kratis patch`,
    )
    process.exit(1)
  }
  console.log(`[kratis-gemini-patch] patched=${patched} already=${alreadyPatched}`)
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main()
}
