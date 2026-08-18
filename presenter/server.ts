/**
 * Presenter feed server — the laptop half of the glasses demo dashboard.
 *
 * The phone (CallService → PresenterSocket) dials OUT to `/publish` and streams the live call:
 * conversation text, log lines, state, glasses photos, and raw PCM for both voices. Browsers connect
 * to `/watch` and get the same feed fanned out, plus the dashboard page itself from `/`.
 *
 * Deliberately standalone rather than routes inside cloud-api: cloud-api IS the demo path (it serves
 * /session and the concierge WS on stage), and audio fan-out has no business sharing that process.
 * This one can crash, be restarted, or be abandoned mid-demo with zero consequence for the call.
 *
 *   cd presenter && npm install && npm run presenter -- --key <secret> --port 8899
 *
 * Nothing is persisted. One publisher at a time (newest wins), N watchers.
 */

import { createServer } from 'node:http'
import { networkInterfaces } from 'node:os'
import { readFile } from 'node:fs/promises'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { WebSocketServer, type WebSocket } from 'ws'

const HERE = dirname(fileURLToPath(import.meta.url))

function arg(name: string): string | undefined {
  const i = process.argv.indexOf(`--${name}`)
  return i > -1 ? process.argv[i + 1] : undefined
}

const PORT = Number(arg('port') ?? process.env.PRESENTER_PORT ?? 8899)
const KEY = arg('key') ?? process.env.PRESENTER_KEY ?? ''
/** Text events replayed to a browser that opens the dashboard mid-call, so it is never blank. */
const REPLAY_MAX = 200

let publisher: WebSocket | null = null
const watchers = new Set<WebSocket>()
/** `hello` is kept separately: it declares sample rates, so a late watcher needs it before any audio. */
let lastHello: string | null = null
const replay: string[] = []

function broadcast(data: string | Buffer, isBinary: boolean): void {
  for (const w of watchers) {
    // readyState 1 === OPEN. A watcher whose buffer is backing up gets frames dropped rather than
    // slowing the whole fan-out — the same "a late frame is worthless" rule the phone applies.
    if (w.readyState !== 1) continue
    if (isBinary && w.bufferedAmount > 4_000_000) continue
    w.send(data, { binary: isBinary })
  }
}

const server = createServer(async (req, res) => {
  const path = (req.url ?? '/').split('?')[0]
  if (path === '/health') {
    res.writeHead(200, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ ok: true, publisher: publisher !== null, watchers: watchers.size }))
    return
  }
  if (path === '/' || path === '/index.html') {
    try {
      const html = await readFile(join(HERE, 'public', 'index.html'))
      res.writeHead(200, { 'content-type': 'text/html; charset=utf-8' })
      res.end(html)
    } catch {
      res.writeHead(500).end('dashboard missing')
    }
    return
  }
  res.writeHead(404).end('not found')
})

const wss = new WebSocketServer({ noServer: true })

server.on('upgrade', (req, socket, head) => {
  const url = new URL(req.url ?? '/', 'http://localhost')
  const role = url.pathname
  // Shared-key gate. The laptop is on a shared network for the demo (it also hosts cloud-api), and
  // unlike cloud-api this server has no user auth — without a key, anyone on the wifi could listen to
  // a live microphone.
  if (KEY && url.searchParams.get('k') !== KEY) {
    socket.write('HTTP/1.1 401 Unauthorized\r\n\r\n')
    socket.destroy()
    return
  }
  if (role !== '/publish' && role !== '/watch') {
    socket.write('HTTP/1.1 404 Not Found\r\n\r\n')
    socket.destroy()
    return
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    if (role === '/publish') onPublisher(ws)
    else onWatcher(ws)
  })
})

function onPublisher(ws: WebSocket): void {
  // Newest wins: a phone reconnect (backoff, network blip, app restart) replaces the stale socket
  // instead of both feeding the same watchers.
  if (publisher && publisher !== ws) {
    console.log('[presenter] replacing previous publisher')
    publisher.close(1000, 'superseded')
  }
  publisher = ws
  replay.length = 0
  lastHello = null
  console.log('[presenter] phone connected')

  ws.on('message', (data: Buffer, isBinary: boolean) => {
    if (isBinary) {
      broadcast(data, true)
      return
    }
    const text = data.toString()
    // Cheap classification — we don't need to parse the whole event to know how to buffer it.
    if (text.includes('"t":"hello"')) {
      lastHello = text
      replay.length = 0
    } else {
      replay.push(text)
      if (replay.length > REPLAY_MAX) replay.shift()
    }
    broadcast(text, false)
  })

  ws.on('close', () => {
    if (publisher === ws) {
      publisher = null
      console.log('[presenter] phone disconnected')
      broadcast(JSON.stringify({ t: 'publisher-gone' }), false)
    }
  })
  ws.on('error', () => {})
}

function onWatcher(ws: WebSocket): void {
  watchers.add(ws)
  console.log(`[presenter] dashboard opened (${watchers.size} watching)`)
  ws.send(JSON.stringify({ t: 'server-hello', publisher: publisher !== null }))
  if (lastHello) ws.send(lastHello)
  for (const e of replay) ws.send(e)
  ws.on('close', () => watchers.delete(ws))
  ws.on('error', () => watchers.delete(ws))
}

function lanUrls(): string[] {
  const out: string[] = []
  for (const list of Object.values(networkInterfaces())) {
    for (const ni of list ?? []) {
      if (ni.family === 'IPv4' && !ni.internal) out.push(ni.address)
    }
  }
  return out
}

server.listen(PORT, '0.0.0.0', () => {
  const q = KEY ? `?k=${KEY}` : ''
  console.log(`\n  Presenter feed listening on :${PORT}\n`)
  console.log(`  Dashboard (this laptop):  http://localhost:${PORT}/${q}`)
  for (const ip of lanUrls()) {
    console.log(`  Dashboard (LAN):          http://${ip}:${PORT}/${q}`)
    console.log(`  → put in meta-android-app/local.properties:  presenter_url=ws://${ip}:${PORT}`)
    if (KEY) console.log(`                                              presenter_key=${KEY}`)
  }
  if (!KEY) {
    console.log(
      `\n  ⚠ No key set — anyone on this network can watch and LISTEN to the live call.\n` +
        `    Pass --key <secret> (and presenter_key=<secret> on the phone) on a shared network.`,
    )
  }
  console.log('')
})
