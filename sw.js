/* Offline cache for the tricorder.
 *
 * This was cache-first with a fixed cache name, which is a trap: once the
 * first build was cached it was served forever, so a new build of the app
 * never appeared — and inside the Android app, where WebView storage
 * survives an app update, installing a newer APK still rendered the old
 * interface.
 *
 * Now: network-first for the app's own files, so a fresh copy always wins
 * when one is reachable (inside the APK "the network" is the asset
 * interceptor, which is always reachable), and the cache is only the
 * fallback for genuinely being offline. The cache name carries the build,
 * so upgrading discards everything from the previous one.
 */
const BUILD = "2026-08-06.3";
const CACHE = "tricorder-" + BUILD;
const FILES = ["./", "./tricorder.html", "./manifest.webmanifest"];

self.addEventListener("install", e => {
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(FILES)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", e => {
  e.waitUntil(caches.keys()
    .then(keys => Promise.all(keys.filter(k => k !== CACHE).map(k => caches.delete(k))))
    .then(() => self.clients.claim()));
});

self.addEventListener("fetch", e => {
  if (e.request.method !== "GET") return;
  e.respondWith(
    fetch(e.request)
      .then(res => {
        const copy = res.clone();
        caches.open(CACHE).then(c => c.put(e.request, copy)).catch(() => {});
        return res;
      })
      .catch(() => caches.match(e.request).then(hit => hit || caches.match("./tricorder.html")))
  );
});

/* lets the page ask this worker to stand down entirely */
self.addEventListener("message", e => {
  if (e.data === "unregister") {
    self.registration.unregister()
      .then(() => caches.keys())
      .then(keys => Promise.all(keys.map(k => caches.delete(k))));
  }
});
