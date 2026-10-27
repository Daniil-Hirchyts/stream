// Deliberately untrusted, test-only certificate. No trust-store changes are ever made by the launcher.
import { createServer } from 'node:https';
import { readFileSync } from 'node:fs';
const [key, cert] = process.argv.slice(2);
createServer({ key: readFileSync(key), cert: readFileSync(cert) }, (req, res) => {
  res.writeHead(200, { 'Content-Type': 'application/json' }).end('{"shouldNotBeAccepted":true}');
}).listen(58135, '127.0.0.1');
