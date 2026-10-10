// DeviceLink relay — optional, for access OUTSIDE the LAN.
// Pairs a phone (ws client) with N viewers by 6-digit room code via Durable Objects.
// No auth, no storage. Traffic proxies JPEG frames + JSON only.
// Deploy:  npx wrangler deploy   (needs Cloudflare account; see wrangler.toml)

export class Room {
  state; env; phone = null; viewers = new Set();
  constructor(state, env) { this.state = state; this.env = env; }

  async fetch(req) {
    const url = new URL(req.url);
    const pair = url.searchParams.get('pair'); // viewer: join code
    if (url.pathname === '/phone') {
      if (this.phone) return new Response('phone already bound', { status: 409 });
      const { 0: client, 1: server } = new WebSocketPair();
      this.phone = server;
      server.accept();
      server.addEventListener('message', (e) => { for (const v of this.viewers) { try { v.send(e.data); } catch {} } });
      server.addEventListener('close', () => { this.phone = null; });
      return new Response(null, { status: 101, webSocket: client });
    }
    if (pair) {
      const { 0: client, 1: server } = new WebSocketPair();
      this.viewers.add(server);
      server.accept();
      server.addEventListener('message', (e) => { if (this.phone) { try { this.phone.send(e.data); } catch {} } });
      server.addEventListener('close', () => { this.viewers.delete(server); });
      return new Response(null, { status: 101, webSocket: client });
    }
    return new Response('use ?pair=CODE (viewer) or /phone (device)', { status: 400 });
  }
}

export default {
  async fetch(req, env) {
    const url = new URL(req.url);
    const code = (url.searchParams.get('pair') || url.pathname.split('/').filter(Boolean)[0] || '').replace(/[^a-zA-Z0-9]/g, '');
    if (!code) return new Response('DeviceLink relay. Phone wss -> /<CODE>/phone , viewer wss -> /<CODE>/view?pair=<CODE>', { headers: { 'content-type': 'text/plain' } });
    const id = env.ROOM.idFromName(code);
    return env.ROOM.get(id).fetch(req);
  },
};
