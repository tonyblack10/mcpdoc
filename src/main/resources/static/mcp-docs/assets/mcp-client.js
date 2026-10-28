/* MCP Docs — cliente MCP no navegador (initialize/tools/call) e diálogo de conexão.
 * A renderização da documentação é feita pelo servidor e trocada pelo htmx; este arquivo não monta lista nem detalhe de tool. */
(function () {
'use strict';

// <client>
// Cliente MCP mínimo (sem DOM): streamable HTTP e SSE legado.
class AuthError extends Error {
  constructor(m, www) { super(m); this.name = 'AuthError'; this.www = www || ''; }
}
class HttpError extends Error {
  constructor(status, body) { super('HTTP ' + status + (body ? ': ' + String(body).slice(0, 300) : '')); this.name = 'HttpError'; this.status = status; }
}
class RpcError extends Error {
  constructor(e) { super((e && e.message) || 'Erro JSON-RPC'); this.name = 'RpcError'; this.code = e && e.code; this.data = e && e.data; }
}

function withTimeout(p, ms, msg) {
  let t;
  return Promise.race([p, new Promise((_, rej) => { t = setTimeout(() => rej(new Error(msg)), ms); })])
    .finally(() => clearTimeout(t));
}

function parseSSEBlock(raw) {
  let event = 'message'; const data = [];
  for (const line of raw.split('\n')) {
    if (!line || line.startsWith(':')) continue;
    const i = line.indexOf(':');
    const f = i < 0 ? line : line.slice(0, i);
    let v = i < 0 ? '' : line.slice(i + 1);
    if (v.startsWith(' ')) v = v.slice(1);
    if (f === 'event') event = v; else if (f === 'data') data.push(v);
  }
  return { event, data: data.join('\n') };
}
async function readSSE(res, onEvent) {
  const reader = res.body.getReader(), dec = new TextDecoder();
  let buf = '';
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += dec.decode(value, { stream: true }).replace(/\r\n/g, '\n');
      let i;
      while ((i = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, i); buf = buf.slice(i + 2);
        if (onEvent(parseSSEBlock(raw)) === true) return;
      }
    }
    if (buf.trim()) onEvent(parseSSEBlock(buf));
  } finally { try { await reader.cancel(); } catch (e) { /* ignore */ } }
}

class McpClient {
  constructor(opts) { this.opts = opts; this.reset(); }
  reset() {
    this.sessionId = null; this.protocol = null; this.serverInfo = null; this.capabilities = null;
    this.instructions = null; this.connected = false; this.nextId = 1; this.events = []; this.sse = null;
  }
  _cfg() { return this.opts.getConfig(); }
  _fetch(url, init) { return (this.opts.fetch || ((u, i) => fetch(u, i)))(url, init); }
  _changed() { if (this.opts.onChange) this.opts.onChange(this); }
  _timeout() { return AbortSignal.timeout(this.opts.timeoutMs || 60000); }
  async _drain(r) { try { await r.arrayBuffer(); } catch (e) { /* ignore */ } }

  async initialize() {
    await this._closeTransport();
    this.reset();
    const cfg = this._cfg();
    if (cfg.transport === 'sse') await this._openSSE();
    const msg = await this._request('initialize', {
      protocolVersion: cfg.protocolVersion || '2025-06-18',
      capabilities: {},
      clientInfo: { name: cfg.clientName || 'mcp-docs', version: cfg.clientVersion || '1.0.0' }
    });
    if (msg.error) throw new RpcError(msg.error);
    const r = msg.result || {};
    this.protocol = r.protocolVersion || cfg.protocolVersion;
    this.serverInfo = r.serverInfo || null;
    this.capabilities = r.capabilities || null;
    this.instructions = r.instructions || null;
    await this._notify('notifications/initialized');
    this.connected = true;
    this._changed();
    return r;
  }

  async callTool(name, args, meta) {
    const p = { name, arguments: args || {} };
    if (meta && typeof meta === 'object' && Object.keys(meta).length) p._meta = meta;
    return this._call('tools/call', p);
  }
  async listTools() {
    const m = await this._call('tools/list', {});
    if (m.error) throw new RpcError(m.error);
    return m.result;
  }

  async disconnect() {
    await this._closeTransport();
    this.reset();
    this._changed();
  }

  async _call(method, params) {
    if (!this.connected) await this.initialize();
    try { return await this._request(method, params); }
    catch (e) {
      if (e.sessionExpired || e.streamClosed) { await this.initialize(); return await this._request(method, params); }
      throw e;
    }
  }
  async _request(method, params) {
    const body = { jsonrpc: '2.0', id: this.nextId++, method, params };
    return this._cfg().transport === 'sse' ? this._sseRequest(body) : this._streamRequest(body);
  }
  async _notify(method, params) {
    const body = { jsonrpc: '2.0', method };
    if (params) body.params = params;
    if (this._cfg().transport === 'sse') await this._ssePost(body);
    else await this._drain(await this._http(body));
  }

  async _check(res, body) {
    if (res.ok) return;
    if (res.status === 401) {
      const w = res.headers.get('www-authenticate');
      await this._drain(res);
      throw new AuthError('HTTP 401 — não autorizado', w);
    }
    const text = await res.text().catch(() => '');
    const e = new HttpError(res.status, text);
    if (res.status === 404 && this.sessionId && body && body.method !== 'initialize') e.sessionExpired = true;
    throw e;
  }

  // ---- Streamable HTTP ----
  async _http(body) {
    const cfg = this._cfg();
    const headers = Object.assign({ 'Content-Type': 'application/json', 'Accept': 'application/json, text/event-stream' }, await this.opts.getHeaders());
    if (this.sessionId) headers['Mcp-Session-Id'] = this.sessionId;
    if (this.protocol && body.method !== 'initialize') headers['MCP-Protocol-Version'] = this.protocol;
    const res = await this._fetch(cfg.url, { method: 'POST', headers, body: JSON.stringify(body), signal: this._timeout() });
    const sid = res.headers.get('mcp-session-id');
    if (sid) this.sessionId = sid;
    await this._check(res, body);
    return res;
  }
  async _streamRequest(body) {
    const res = await this._http(body);
    const ct = (res.headers.get('content-type') || '').toLowerCase();
    if (ct.includes('text/event-stream')) {
      let found = null;
      await readSSE(res, ev => {
        if (!ev.data) return false;
        let m; try { m = JSON.parse(ev.data); } catch (e) { return false; }
        if (this._dispatch(m, body.id)) { found = m; return true; }
        return false;
      });
      if (!found) throw new Error('Stream encerrado sem resposta para ' + body.method);
      return found;
    }
    const text = await res.text();
    let m; try { m = JSON.parse(text); } catch (e) { throw new Error('Resposta inválida do servidor: ' + text.slice(0, 200)); }
    let found = null;
    for (const x of (Array.isArray(m) ? m : [m])) if (this._dispatch(x, body.id)) found = x;
    if (!found) throw new Error('Resposta sem id correspondente');
    return found;
  }
  _dispatch(m, id) {
    if (m && m.id === id && ('result' in m || 'error' in m)) return true;
    if (m && m.method && m.id !== undefined) this._rejectServerRequest(m);
    else if (m && m.method) { this.events.push(m); if (this.opts.onEvent) this.opts.onEvent(m); }
    return false;
  }
  _rejectServerRequest(m) {
    this.events.push({ method: '(requisição do servidor recusada) ' + m.method, params: m.params });
    const body = { jsonrpc: '2.0', id: m.id, error: { code: -32601, message: 'Método não suportado pelo cliente MCP Docs: ' + m.method } };
    const p = this._cfg().transport === 'sse' ? this._ssePost(body) : this._http(body).then(r => this._drain(r));
    Promise.resolve(p).catch(() => {});
  }
  async _closeTransport() {
    if (this.sse) { const s = this.sse; this.sse = null; try { s.ac.abort(); } catch (e) { /* ignore */ } return; }
    if (this.sessionId) {
      try {
        const headers = Object.assign({}, await this.opts.getHeaders(), { 'Mcp-Session-Id': this.sessionId });
        if (this.protocol) headers['MCP-Protocol-Version'] = this.protocol;
        await this._drain(await this._fetch(this._cfg().url, { method: 'DELETE', headers, signal: this._timeout() }));
      } catch (e) { /* o servidor pode responder 405; ignorado */ }
    }
  }

  // ---- SSE legado ----
  async _openSSE() {
    const cfg = this._cfg();
    const ac = new AbortController();
    const headers = Object.assign({ 'Accept': 'text/event-stream' }, await this.opts.getHeaders());
    const res = await this._fetch(cfg.url, { method: 'GET', headers, signal: ac.signal });
    await this._check(res, { method: 'GET' });
    const sse = { ac, pending: new Map(), endpoint: null, closed: false };
    this.sse = sse;
    let ready; const readyP = new Promise((resolve, reject) => { ready = { resolve, reject }; });
    readyP.catch(() => {});
    (async () => {
      try {
        await readSSE(res, ev => {
          if (ev.event === 'endpoint') { sse.endpoint = new URL(ev.data.trim(), cfg.url).href; ready.resolve(); return false; }
          if (ev.event === 'message' && ev.data) {
            let m; try { m = JSON.parse(ev.data); } catch (e) { return false; }
            this._sseDispatch(sse, m);
          }
          return false;
        });
      } catch (e) { /* abortado ou erro de rede */ }
      finally {
        sse.closed = true;
        const err = new Error('Stream SSE encerrado'); err.streamClosed = true;
        for (const p of sse.pending.values()) p.reject(err);
        sse.pending.clear();
        ready.reject(err);
        if (this.sse === sse) { this.connected = false; this._changed(); }
      }
    })();
    await withTimeout(readyP, 15000, 'Timeout aguardando o evento "endpoint" do SSE');
  }
  _sseDispatch(sse, m) {
    if (m && m.id !== undefined && ('result' in m || 'error' in m)) {
      const p = sse.pending.get(m.id);
      if (p) { sse.pending.delete(m.id); p.resolve(m); }
      return;
    }
    if (m && m.method && m.id !== undefined) this._rejectServerRequest(m);
    else if (m && m.method) { this.events.push(m); if (this.opts.onEvent) this.opts.onEvent(m); }
  }
  async _ssePost(body) {
    const sse = this.sse;
    if (!sse || sse.closed) { const e = new Error('Stream SSE não está aberto'); e.streamClosed = true; throw e; }
    const headers = Object.assign({ 'Content-Type': 'application/json' }, await this.opts.getHeaders());
    const res = await this._fetch(sse.endpoint, { method: 'POST', headers, body: JSON.stringify(body), signal: this._timeout() });
    await this._check(res, body);
    await this._drain(res);
    return res;
  }
  async _sseRequest(body) {
    const sse = this.sse;
    if (!sse || sse.closed) { const e = new Error('Stream SSE não está aberto'); e.streamClosed = true; throw e; }
    let entry;
    const p = new Promise((resolve, reject) => {
      const t = setTimeout(() => { sse.pending.delete(body.id); reject(new Error('Timeout aguardando resposta de ' + body.method)); }, this.opts.timeoutMs || 60000);
      entry = { resolve: m => { clearTimeout(t); resolve(m); }, reject: e => { clearTimeout(t); reject(e); } };
    });
    p.catch(() => {});
    sse.pending.set(body.id, entry);
    try { await this._ssePost(body); }
    catch (e) { sse.pending.delete(body.id); entry.reject(e); throw e; }
    return p;
  }
}

// </client>

// ---------------- UI ----------------
// A navegação (lista, busca, detalhe da tool) é feita pelo htmx com HTML do servidor.
// Este arquivo cuida só do que o htmx não faz: cliente MCP, diálogo de conexão e resultado das chamadas.
const CONF = (function () {
  try { return JSON.parse(document.getElementById('mcp-docs-config').textContent); } catch (e) { return {}; }
})();
CONF.base = CONF.base || '/mcp-docs';
CONF.contextPath = CONF.contextPath || '';
CONF.endpoint = CONF.endpoint || '/mcp';

const $ = id => document.getElementById(id);
const SKEY = 'mcpdocs.cfg.v1';
// Autenticação suportada: nenhuma ou bearer token (OAuth 2 fica para versões futuras).
const AUTH_TYPES = ['none', 'bearer'];
const DEF = {
  transport: 'streamable', url: '', protocolVersion: '2025-06-18',
  authType: 'none', bearer: '', headers: [{ name: '', value: '' }], meta: ''
};
let HAD_SAVED = false;
let CFG = loadCfg();

function loadCfg() {
  let raw = null;
  try { raw = JSON.parse(sessionStorage.getItem(SKEY) || 'null'); } catch (e) { /* sessionStorage indisponível */ }
  HAD_SAVED = !!raw;
  const c = Object.assign({}, DEF, raw || {});
  // configuração salva por versões anteriores (OAuth) volta para "nenhuma"
  if (!AUTH_TYPES.includes(c.authType)) c.authType = 'none';
  delete c.oauth; delete c.token;
  if (!Array.isArray(c.headers) || !c.headers.length) c.headers = [{ name: '', value: '' }];
  return c;
}
function saveCfg() { try { sessionStorage.setItem(SKEY, JSON.stringify(CFG)); } catch (e) { /* ignore */ } }

function resolveUrl(u) {
  u = String(u || '').trim();
  if (/^https?:\/\//i.test(u)) return u;
  return location.origin + CONF.contextPath + (u.startsWith('/') ? u : '/' + u);
}
function mcpUrl() { return resolveUrl(CFG.url || CONF.endpoint); }
async function getHeaders() {
  const hd = {};
  if (CFG.authType === 'bearer' && CFG.bearer.trim()) hd.Authorization = 'Bearer ' + CFG.bearer.trim();
  for (const r of CFG.headers) if (r.name && r.name.trim()) hd[r.name.trim()] = r.value;
  return hd;
}
const client = new McpClient({
  getConfig: () => ({ transport: CFG.transport, url: mcpUrl(), protocolVersion: CFG.protocolVersion, clientName: 'mcp-docs', clientVersion: '1.0.0' }),
  getHeaders,
  onChange: () => updateStatus()
});

function describeError(e) {
  if (e instanceof AuthError) { return '401 Não autorizado. Configure a autenticação em "Conexão e autenticação".' + (e.www ? '\nWWW-Authenticate: ' + e.www : ''); }
  if (e instanceof TypeError) return 'Falha de rede ou CORS ao acessar o servidor (' + e.message + ').';
  if (e && e.name === 'TimeoutError') return 'Timeout.';
  return (e && e.message) || String(e);
}

// ---- helpers de DOM (usados só para o resultado das chamadas) ----
function h(tag, attrs) {
  const e = document.createElement(tag);
  for (const [k, v] of Object.entries(attrs || {})) e.setAttribute(k, v);
  const kids = Array.prototype.slice.call(arguments, 2).flat();
  for (const c of kids) e.append(c instanceof Node ? c : String(c == null ? '' : c));
  return e;
}
const codeBlock = text => h('div', { class: 'code' }, h('button', { class: 'copy', type: 'button', 'data-action': 'copy' }, 'Copiar'), h('pre', {}, text));
const badge = (text, kind) => h('span', { class: 'badge ' + kind }, text);

function renderContent(c) {
  if (c.type === 'text') {
    let txt = String(c.text == null ? '' : c.text);
    try { const j = JSON.parse(txt); if (j && typeof j === 'object') txt = JSON.stringify(j, null, 2); } catch (e) { /* texto puro */ }
    return codeBlock(txt);
  }
  if (c.type === 'image' && /^image\/(png|jpe?g|gif|webp)$/i.test(c.mimeType || '')) {
    const img = h('img', { alt: 'imagem retornada pela tool' });
    img.src = 'data:' + c.mimeType + ';base64,' + String(c.data || '').replace(/[^A-Za-z0-9+/=]/g, '');
    return img;
  }
  if (c.type === 'audio') return h('p', { class: 'muted' }, 'áudio (' + (c.mimeType || '?') + ', ' + String(c.data || '').length + ' caracteres base64)');
  if (c.type === 'resource' && c.resource) {
    return h('div', {}, h('p', { class: 'muted small' }, 'resource: ' + c.resource.uri + (c.resource.mimeType ? ' (' + c.resource.mimeType + ')' : '')),
      c.resource.text !== undefined ? codeBlock(String(c.resource.text)) : '');
  }
  if (c.type === 'resource_link') return h('p', {}, 'resource_link: ', h('code', {}, c.uri || ''));
  return codeBlock(JSON.stringify(c, null, 2));
}

function renderResponse(out, msg, ms, events) {
  out.replaceChildren();
  const head = h('div', { class: 'badges' });
  if (msg.error) head.append(badge('erro JSON-RPC ' + msg.error.code, 'bad'));
  else if (msg.result && msg.result.isError) head.append(badge('isError: a tool retornou erro', 'bad'));
  else head.append(badge('sucesso', 'ok'));
  head.append(h('span', { class: 'muted small' }, Math.round(ms) + ' ms'));
  out.append(head);
  if (msg.error) {
    out.append(h('p', {}, msg.error.message || ''));
    if (msg.error.data !== undefined) out.append(codeBlock(JSON.stringify(msg.error.data, null, 2)));
  }
  const r = msg.result || {};
  for (const c of r.content || []) out.append(renderContent(c));
  if (r.structuredContent !== undefined) out.append(h('h4', {}, 'structuredContent'), codeBlock(JSON.stringify(r.structuredContent, null, 2)));
  if (events && events.length) out.append(h('details', {}, h('summary', {}, 'Eventos do servidor (' + events.length + ')'), codeBlock(JSON.stringify(events, null, 2))));
  out.append(h('details', {}, h('summary', {}, 'Resposta JSON-RPC bruta'), codeBlock(JSON.stringify(msg, null, 2))));
}

function renderFailure(out, e) {
  out.replaceChildren();
  out.append(h('div', { class: 'badges' }, badge(e instanceof AuthError ? 'não autorizado' : 'falha', 'bad')),
    h('p', { class: 'bad', style: 'white-space:pre-wrap' }, describeError(e)));
  if (e instanceof AuthError) {
    out.append(h('button', { class: 'btn', type: 'button', 'data-action': 'conn' }, 'Abrir configuração de autenticação'));
  }
}

// Rascunhos por tool: o htmx troca o <main> a cada navegação; argumentos, _meta e último resultado voltam ao reabrir a tool.
const DRAFTS = Object.create(null);
const draftOf = name => DRAFTS[name] || (DRAFTS[name] = {});

async function execute(box, btn) {
  const name = box.dataset.tool;
  const out = box.querySelector('.out');
  const parse = (txt, label) => {
    if (!txt.trim()) return {};
    let v;
    try { v = JSON.parse(txt); } catch (e) { throw new Error(label + ': JSON inválido — ' + e.message); }
    if (v === null || typeof v !== 'object' || Array.isArray(v)) throw new Error(label + ' deve ser um objeto JSON');
    return v;
  };
  let args, meta;
  try { args = parse(box.querySelector('textarea.args').value, 'Argumentos'); meta = parse(box.querySelector('textarea.meta').value, '_meta'); }
  catch (e) { out.replaceChildren(h('p', { class: 'bad' }, e.message)); return; }
  if (!client.connected) { syncRun(); return; }
  btn.dataset.busy = '1'; btn.disabled = true;
  out.replaceChildren(h('p', { class: 'muted' }, 'Executando…'));
  const n0 = client.events.length, t0 = performance.now();
  try {
    const msg = await client.callTool(name, args, meta);
    renderResponse(out, msg, performance.now() - t0, client.events.slice(n0));
  } catch (e) { renderFailure(out, e); }
  finally { delete btn.dataset.busy; draftOf(name).out = out.innerHTML; syncRun(); }
}

// ---- diálogo de conexão ----
function openConn() { const d = $('conn'); if (!d) return; fillForm(); updateStatus(); if (!d.open) d.showModal(); }
function setMsg(text, kind) { const m = $('connMsg'); m.textContent = text; m.className = 'hint ' + (kind || ''); }

// Executar só fica habilitado com uma sessão inicializada. Idempotente (evita realimentar o MutationObserver).
const GATE_TEXT = 'Conecte-se ao servidor para executar: abra "Conexão e autenticação" e clique em Inicializar.';
function syncRun() {
  const on = client.connected;
  document.querySelectorAll('.try').forEach(box => {
    const btn = box.querySelector('[data-action=run]');
    if (!btn) return;
    const busy = btn.dataset.busy === '1';
    const dis = !on || busy;
    if (btn.disabled !== dis) btn.disabled = dis;
    const title = on ? '' : GATE_TEXT;
    if (btn.title !== title) btn.title = title;
    let gate = box.querySelector('.gate');
    if (!on && !gate) {
      gate = h('p', { class: 'gate muted small', role: 'status' }, GATE_TEXT);
      box.querySelector('.out').before(gate);
    } else if (on && gate) gate.remove();
  });
}

function updateStatus() {
  syncRun();
  const dot = $('dot');
  if (!dot) return;
  const on = client.connected;
  dot.className = 'dot' + (on ? ' on' : '');
  $('statusTxt').textContent = on
    ? 'Conectado · ' + ((client.serverInfo && client.serverInfo.name) || 'servidor') + (client.sessionId ? ' · sessão ' + client.sessionId.slice(0, 8) + '…' : '')
    : 'Desconectado';
  const info = $('connInfo');
  info.replaceChildren();
  if (on) {
    info.append(codeBlock(JSON.stringify({
      protocolVersion: client.protocol, serverInfo: client.serverInfo, capabilities: client.capabilities,
      instructions: client.instructions || undefined, sessionId: client.sessionId || undefined
    }, null, 2)));
  }
}
function renderHeaders() {
  const box = $('hdrs');
  box.replaceChildren();
  CFG.headers.forEach((r, i) => {
    const n = h('input', { type: 'text', class: 'hn', placeholder: 'Nome (ex.: X-API-Key)', autocomplete: 'off' });
    n.value = r.name;
    const v = h('input', { type: /authorization|key|token|secret|password/i.test(r.name) ? 'password' : 'text', class: 'hv', placeholder: 'Valor', autocomplete: 'off' });
    v.value = r.value;
    const x = h('button', { class: 'btn', type: 'button', 'aria-label': 'Remover header' }, '×');
    x.onclick = () => { readForm(); CFG.headers.splice(i, 1); if (!CFG.headers.length) CFG.headers.push({ name: '', value: '' }); saveCfg(); renderHeaders(); };
    box.append(h('div', { class: 'hdr-row' }, n, v, x));
  });
}
function applyAuthVisibility() {
  $('p-bearer').hidden = $('f-auth').value !== 'bearer';
}
function fillForm() {
  $('f-transport').value = CFG.transport; $('f-url').value = CFG.url; $('f-proto').value = CFG.protocolVersion;
  $('f-auth').value = CFG.authType; $('f-bearer').value = CFG.bearer; $('f-meta').value = CFG.meta;
  renderHeaders(); applyAuthVisibility();
}
function readForm() {
  CFG.transport = $('f-transport').value; CFG.url = $('f-url').value; CFG.protocolVersion = $('f-proto').value;
  CFG.authType = $('f-auth').value; CFG.bearer = $('f-bearer').value;
  CFG.meta = $('f-meta').value;
  CFG.headers = Array.from(document.querySelectorAll('.hdr-row')).map(r => ({ name: r.querySelector('.hn').value, value: r.querySelector('.hv').value }));
  applyAuthVisibility(); saveCfg(); hydrate();
}

function wireDialog() {
  const d = $('conn');
  d.addEventListener('input', readForm);
  d.addEventListener('change', readForm);
  $('connClose').onclick = () => d.close();
  $('b-addhdr').onclick = () => { readForm(); CFG.headers.push({ name: '', value: '' }); saveCfg(); renderHeaders(); };
  const guard = fn => async () => { try { await fn(); } catch (e) { setMsg(describeError(e), 'bad'); } };
  $('b-init').onclick = guard(async () => { readForm(); setMsg('Inicializando…'); await client.initialize(); setMsg('Sessão inicializada.', 'ok'); updateStatus(); });
  $('b-disconnect').onclick = guard(async () => { await client.disconnect(); setMsg('Desconectado.'); });
  d.addEventListener('close', () => { updateStatus(); hydrate(); });
}

// ---- ligação com o conteúdo que o htmx troca ----
function curlText(body) {
  return "curl -X POST '" + mcpUrl() + "' \\\n" +
    "  -H 'Content-Type: application/json' \\\n" +
    "  -H 'Accept: application/json, text/event-stream' \\\n" +
    (CFG.authType !== 'none' ? "  -H 'Authorization: Bearer <TOKEN>' \\\n" : '') +
    "  -H 'Mcp-Session-Id: <SESSION_ID>' \\\n" +
    "  -d '" + String(body).replace(/'/g, "'\\''") + "'";
}

// Idempotente: só escreve quando algo muda, para não realimentar o MutationObserver.
function hydrate() {
  syncRun();
  document.querySelectorAll('.try').forEach(box => {
    if (box.dataset.hydrated === '1') return;
    box.dataset.hydrated = '1';
    const d = DRAFTS[box.dataset.tool] || {};
    const args = box.querySelector('textarea.args'), meta = box.querySelector('textarea.meta');
    if (d.args !== undefined) args.value = d.args;
    meta.value = d.meta !== undefined ? d.meta : (CFG.meta || '');
    if (d.out) box.querySelector('.out').innerHTML = d.out;
  });
  document.querySelectorAll('pre.curl').forEach(pre => {
    const text = curlText(pre.dataset.body || '{}');
    if (pre.textContent !== text) pre.textContent = text;
  });
  const ep = $('ep');
  if (ep && ep.textContent !== mcpUrl()) ep.textContent = mcpUrl();
}

function onClick(e) {
  const el = e.target.closest('[data-action]');
  if (!el) return;
  const action = el.dataset.action;
  if (action === 'copy') {
    const pre = el.parentElement.querySelector('pre');
    const text = pre ? pre.textContent : '';
    (navigator.clipboard ? navigator.clipboard.writeText(text) : Promise.reject(new Error('sem clipboard')))
      .then(() => { el.textContent = 'Copiado!'; }, () => { el.textContent = 'Falhou'; })
      .then(() => setTimeout(() => { el.textContent = 'Copiar'; }, 1500));
  } else if (action === 'conn') {
    openConn();
  } else if (action === 'run') {
    if (el.disabled) return;
    execute(el.closest('.try'), el);
  } else if (action === 'reset') {
    const box = el.closest('.try'), args = box.querySelector('textarea.args');
    args.value = args.dataset.sample;
    draftOf(box.dataset.tool).args = args.value;
  }
}
function onInput(e) {
  const t = e.target;
  if (!t.matches || !t.matches('.try textarea')) return;
  const d = draftOf(t.closest('.try').dataset.tool);
  if (t.classList.contains('args')) d.args = t.value; else d.meta = t.value;
}

// Ao abrir uma tool, volta ao topo depois de tudo: troca principal, lista fora de banda e reidratação (rAF).
// Feito aqui e não com hx-swap="show:top": o show do htmx roda antes desses passos e às vezes era desfeito.
function scrollTopAfterToolSwap(e) {
  const action = e.detail?.ctx?.request?.action || '';
  if (!action.includes('/tools/')) return;
  const top = () => window.scrollTo({ top: 0, left: 0, behavior: 'instant' });
  top();
  requestAnimationFrame(() => requestAnimationFrame(top));
}

function init() {
  document.addEventListener('click', onClick);
  document.addEventListener('input', onInput);
  document.addEventListener('htmx:after:swap', scrollTopAfterToolSwap);
  if (CONF.tryItOut === false) { hydrate(); return; }
  if (!HAD_SAVED) {
    CFG.transport = CONF.transport === 'sse' ? 'sse' : 'streamable';
    CFG.url = CONF.endpoint;
    saveCfg();
  }
  wireDialog();
  hydrate();
  updateStatus();
  // O htmx troca o conteúdo de #main (e restaura o <body> no histórico): observa o body e reidrata o que for novo.
  let raf = 0;
  new MutationObserver(() => { if (!raf) raf = requestAnimationFrame(() => { raf = 0; hydrate(); }); })
    .observe(document.body, { childList: true, subtree: true });
}
init();

})();
