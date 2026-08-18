/*
 * Attach to a running presenter as a watcher and log the text frames.
 *
 *   node watch.mjs [--host 127.0.0.1] [--port 8899]
 *
 * Reads the key from meta-android-app/local.properties, the same place the phone reads it, so the
 * two cannot drift. Audio frames arrive as binary and are counted rather than logged — they are the
 * bulk of the traffic and none of the evidence.
 *
 * This exists so the barge-in check has a written record. The by-ear part cannot be automated, but
 * "what did the device actually report, and in what order" can be, and reading that back against
 * what a person heard is what turns "it felt late" into a diagnosis.
 */
import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import WebSocket from 'ws'

const HERE = dirname(fileURLToPath(import.meta.url))
const arg = (n) => {
  const i = process.argv.indexOf(`--${n}`)
  return i > 0 ? process.argv[i + 1] : undefined
}

const props = readFileSync(join(HERE, '../meta-android-app/local.properties'), 'utf8')
const prop = (k) => props.match(new RegExp(`^${k}=(.*)$`, 'm'))?.[1]?.trim() ?? ''

const host = arg('host') ?? '127.0.0.1'
const port = arg('port') ?? '8899'
const key = prop('presenter_key')

const url = `ws://${host}:${port}/watch${key ? `?k=${encodeURIComponent(key)}` : ''}`
const ws = new WebSocket(url)

let audioFrames = 0
const stamp = () => new Date().toISOString().slice(11, 23)

ws.on('open', () => console.log(`${stamp()}  watching ${host}:${port}`))

ws.on('message', (data, isBinary) => {
  if (isBinary) {
    audioFrames++
    if (audioFrames % 200 === 0) console.log(`${stamp()}  … ${audioFrames} audio frames`)
    return
  }
  const text = data.toString()
  let m
  try {
    m = JSON.parse(text)
  } catch {
    console.log(`${stamp()}  raw: ${text.slice(0, 200)}`)
    return
  }
  // Keep the shape loose: the presenter's frames are the device's own vocabulary and this should not
  // need editing every time a field is added.
  const kind = m.t ?? m.type ?? '?'
  const body = { ...m }
  delete body.t
  delete body.type
  for (const k of Object.keys(body)) {
    if (typeof body[k] === 'string' && body[k].length > 300) body[k] = `${body[k].slice(0, 300)}…`
  }
  const rest = Object.keys(body).length ? ` ${JSON.stringify(body)}` : ''
  console.log(`${stamp()}  ${kind}${rest}`)
})

ws.on('error', (e) => console.error(`${stamp()}  error: ${e.message}`))
ws.on('close', (c) => console.log(`${stamp()}  closed (${c}) — ${audioFrames} audio frames total`))
