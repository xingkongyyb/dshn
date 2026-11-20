'use strict';

/**
 * DSHN Node.js DNS compatibility preload (`--require=/opt/dshn/dshn-dns-preload.cjs`).
 *
 * Under bxroot (LD_PRELOAD user-space container), glibc's internal getaddrinfo() reads
 * /etc/resolv.conf via non-interposable internal syscalls and sees the Android host
 * (which has no /etc/resolv.conf), failing with EAI_AGAIN.
 *
 * Node's c-ares Resolver reads /etc/resolv.conf through normal libc open(), which bxroot
 * translates cleanly. This preload hooks dns.lookup / dns.promises.lookup without modifying
 * any DSH code, falling back to c-ares + /etc/hosts whenever native lookup fails.
 */

const dns = require('node:dns');
const fs = require('node:fs');
const net = require('node:net');

if (globalThis.__DSHN_DNS_PRELOAD_INSTALLED__) {
  module.exports = {};
} else {
  globalThis.__DSHN_DNS_PRELOAD_INSTALLED__ = true;

  const FALLBACK_SERVERS = ['223.5.5.5', '119.29.29.29', '8.8.8.8', '1.1.1.1'];
  const CACHE_TTL_MS = 30_000;
  const cache = new Map();
  let resolvMtimeMs = -1;
  let cachedServers = FALLBACK_SERVERS;
  let hostsMtimeMs = -1;
  let cachedHosts = new Map();

  function loadResolvServers() {
    try {
      const st = fs.statSync('/etc/resolv.conf');
      if (st.mtimeMs === resolvMtimeMs && cachedServers.length > 0) return cachedServers;
      const text = fs.readFileSync('/etc/resolv.conf', 'utf8');
      const parsed = [];
      for (const raw of text.split(/\r?\n/)) {
        const line = raw.replace(/[#;].*$/, '').trim();
        const m = /^nameserver\s+(\S+)/i.exec(line);
        if (m && net.isIP(m[1])) parsed.push(m[1]);
      }
      resolvMtimeMs = st.mtimeMs;
      cachedServers = parsed.length > 0 ? parsed : FALLBACK_SERVERS;
      return cachedServers;
    } catch {
      return FALLBACK_SERVERS;
    }
  }

  function loadHostsMap() {
    try {
      const st = fs.statSync('/etc/hosts');
      if (st.mtimeMs === hostsMtimeMs) return cachedHosts;
      const map = new Map();
      const text = fs.readFileSync('/etc/hosts', 'utf8');
      for (const raw of text.split(/\r?\n/)) {
        const line = raw.replace(/#.*$/, '').trim();
        if (!line) continue;
        const parts = line.split(/\s+/);
        if (parts.length < 2) continue;
        const ip = parts[0];
        const fam = net.isIP(ip);
        if (!fam) continue;
        for (let i = 1; i < parts.length; i++) {
          const host = parts[i].toLowerCase();
          if (!map.has(host)) map.set(host, []);
          map.get(host).push({ address: ip, family: fam });
        }
      }
      hostsMtimeMs = st.mtimeMs;
      cachedHosts = map;
      return map;
    } catch {
      return cachedHosts;
    }
  }

  function normalizeOptions(options) {
    if (typeof options === 'number') {
      return { family: options, all: false };
    }
    if (options && typeof options === 'object') {
      return {
        family: typeof options.family === 'number' ? options.family : 0,
        all: Boolean(options.all),
      };
    }
    return { family: 0, all: false };
  }

  function resolveViaCares(hostname, family) {
    const key = `${hostname.toLowerCase()}|${family}`;
    const hit = cache.get(key);
    if (hit && hit.expiresAt > Date.now()) {
      return Promise.resolve(hit.records);
    }

    const resolver = new dns.promises.Resolver();
    const servers = loadResolvServers();
    try {
      resolver.setServers(servers);
    } catch {
      resolver.setServers(FALLBACK_SERVERS);
    }

    const tasks = [];
    if (family === 0 || family === 4) {
      tasks.push(
        resolver.resolve4(hostname).then(
          (addrs) => addrs.map((address) => ({ address, family: 4 })),
          () => []
        )
      );
    }
    if (family === 0 || family === 6) {
      tasks.push(
        resolver.resolve6(hostname).then(
          (addrs) => addrs.map((address) => ({ address, family: 6 })),
          () => []
        )
      );
    }

    return Promise.all(tasks).then((groups) => {
      const records = groups.flat();
      if (records.length > 0) {
        if (cache.size > 256) cache.clear();
        cache.set(key, { records, expiresAt: Date.now() + CACHE_TTL_MS });
      }
      return records;
    });
  }

  function resolveLocal(hostname, family) {
    const lower = hostname.toLowerCase();
    if (lower === 'localhost' || lower.endsWith('.localhost')) {
      if (family === 6) return [{ address: '::1', family: 6 }];
      if (family === 4) return [{ address: '127.0.0.1', family: 4 }];
      return [{ address: '127.0.0.1', family: 4 }];
    }
    const entries = loadHostsMap().get(lower);
    if (!entries || entries.length === 0) return null;
    const filtered = family === 0 ? entries : entries.filter((e) => e.family === family);
    return filtered.length > 0 ? filtered : null;
  }

  function shouldFallback(err) {
    if (!err) return false;
    const c = err.code;
    return c === 'EAI_AGAIN' || c === 'ENOTFOUND' || c === 'ESERVFAIL' || c === 'EAI_NODATA' || c === 'EAI_NONAME';
  }

  const origLookup = dns.lookup.bind(dns);
  const origPromisesLookup = dns.promises.lookup.bind(dns.promises);

  dns.lookup = function dshnLookup(hostname, options, callback) {
    let opts = options;
    let cb = callback;
    if (typeof opts === 'function') {
      cb = opts;
      opts = {};
    }
    if (typeof hostname !== 'string' || !hostname || net.isIP(hostname)) {
      return origLookup(hostname, opts, cb);
    }

    const norm = normalizeOptions(opts);
    const local = resolveLocal(hostname, norm.family);
    if (local) {
      process.nextTick(() => {
        if (norm.all) cb(null, local);
        else cb(null, local[0].address, local[0].family);
      });
      return;
    }

    return origLookup(hostname, opts, (err, address, family) => {
      if (!err || !shouldFallback(err)) {
        return cb(err, address, family);
      }
      resolveViaCares(hostname, norm.family).then(
        (records) => {
          if (!records || records.length === 0) {
            return cb(err);
          }
          if (norm.all) return cb(null, records);
          return cb(null, records[0].address, records[0].family);
        },
        () => cb(err)
      );
    });
  };

  dns.promises.lookup = async function dshnPromisesLookup(hostname, options) {
    if (typeof hostname !== 'string' || !hostname || net.isIP(hostname)) {
      return origPromisesLookup(hostname, options);
    }
    const norm = normalizeOptions(options);
    const local = resolveLocal(hostname, norm.family);
    if (local) {
      return norm.all ? local : { address: local[0].address, family: local[0].family };
    }
    try {
      return await origPromisesLookup(hostname, options);
    } catch (err) {
      if (!shouldFallback(err)) throw err;
      const records = await resolveViaCares(hostname, norm.family);
      if (!records || records.length === 0) throw err;
      return norm.all ? records : { address: records[0].address, family: records[0].family };
    }
  };
}
