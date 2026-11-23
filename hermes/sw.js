/* Hermes Mobile service worker: caches the app shell only. API calls (any other origin) are never touched. */
const VERSION = "hm-v1";
const SHELL = ["./", "index.html", "app.css", "app.js", "manifest.webmanifest", "icons/icon.svg", "icons/icon-192.png", "icons/icon-180.png"];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(VERSION).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener("activate", (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== VERSION).map((k) => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener("fetch", (e) => {
  const req = e.request;
  const url = new URL(req.url);
  const scope = new URL(self.registration.scope);
  // Only same-origin GETs inside this app's folder. Everything else goes straight to the network.
  if (req.method !== "GET" || url.origin !== scope.origin || !url.pathname.startsWith(scope.pathname)) return;
  // Network first so updates land quickly; fall back to the cached shell offline.
  e.respondWith(
    fetch(req).then((res) => {
      if (res.ok) { const copy = res.clone(); caches.open(VERSION).then((c) => c.put(req, copy)); }
      return res;
    }).catch(() => caches.match(req, { ignoreSearch: true }).then((hit) => hit || caches.match("index.html")))
  );
});
