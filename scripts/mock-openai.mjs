/*
 * A stand-in OpenAI-compatible endpoint for device proofs.
 *
 * It has to be its own process. `provider-check.mjs` drives the app with blocking adb calls, and a
 * Node process that never returns to its event loop does not just fail to answer requests — the
 * `listen()` call it made is itself an event-loop job, so the socket is never bound at all. That is
 * why the endpoint inside that harness could not be reached by any build of the app: nothing was
 * ever listening, and the harness reported "no request arrived" instead of saying so.
 *
 * Requests are appended as JSON lines to the file given as the second argument, so a synchronous
 * parent can read them with readFileSync.
 *
 * Usage: node scripts/mock-openai.mjs <port> <requests.jsonl>
 */
import { createServer } from 'node:http';
import { appendFileSync, writeFileSync } from 'node:fs';

const PORT = Number(process.argv[2] || 8971);
const LOG = process.argv[3] || 'verify/provider/requests.jsonl';

const server = createServer((req, res) => {
  let raw = '';
  req.on('data', (chunk) => { raw += chunk; });
  req.on('end', () => {
    let body = null;
    try { body = JSON.parse(raw); } catch { body = null; }
    const system = body?.messages?.[0]?.content ?? '';
    const user = body?.messages?.[1]?.content ?? '';
    appendFileSync(
      LOG,
      `${JSON.stringify({
        method: req.method,
        path: req.url,
        auth: req.headers.authorization ?? '',
        agent: req.headers['user-agent'] ?? '',
        contentType: req.headers['content-type'] ?? '',
        model: body?.model ?? '',
        temperature: body?.temperature,
        messages: body?.messages?.length ?? 0,
        userCharacters: user.length,
        at: new Date().toISOString()
      })}\n`
    );

    if (body?.model === 'reject-me') {
      res.writeHead(401, { 'content-type': 'application/json' });
      res.end(JSON.stringify({
        error: {
          message: `mock refused the key ${String(req.headers.authorization ?? '').replace('Bearer ', '')}`
        }
      }));
      return;
    }

    const reply = system.includes('connectivity check')
      ? 'OK'
      : `[${body?.model ?? 'unknown'}] ${user.slice(0, 32)}`;
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({
      id: 'chatcmpl-mock',
      object: 'chat.completion',
      model: body?.model ?? 'unknown',
      choices: [{ index: 0, message: { role: 'assistant', content: reply }, finish_reason: 'stop' }]
    }));
  });
});

server.on('error', (error) => {
  console.error(`mock provider could not bind ${PORT}: ${error.message}`);
  process.exit(1);
});
// Truncating before the bind would let a second, failed start erase the first run's requests.
server.listen(PORT, '0.0.0.0', () => {
  writeFileSync(LOG, '');
  console.log(`mock provider bound ${PORT} on 0.0.0.0`);
});
