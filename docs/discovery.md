# Discovery contract (first slice of #39)

`ServerDiscovery` performs one 10-second DNS-SD scan for `_tauon-remote._tcp.`.
The existing manual Connect screen continues to use its name/address and message
callbacks. The optional fourth callback supplies `DiscoverySnapshot` for the
future Connect screen and connection policy. Call start/stop from the main thread;
all callbacks are delivered there. `stop()` invalidates the scan without invoking
UI callbacks, so disposing the screen is safe.

- `SEARCHING`: candidates can still change; never auto-select the first answer.
- `COMPLETE`: scan window ended; `servers` contains distinct validated origins.
- `UNAVAILABLE`: discovery failed, distinct from finding no servers.
- `IDLE`: initial/cancelled bookkeeping state (stop deliberately does not emit it).
- `unresolvedCount`: advertised names that did not resolve successfully by the
  deadline, plus one marker if the 64-name scan cap was exceeded. Even one visible
  server is not an unambiguous result when this is nonzero.

Found services are queued and resolved serially instead of discarding discoveries
while another resolve is running. Each scan and each occurrence of a service name
has an identity, so callbacks from stopped scans or lost/reappearing services
cannot add stale results. Lost services are removed from snapshots. The legacy
append-only `found` callback cannot retract rows; the future Connect screen should
render the snapshot list instead. Manual selection still requires a real Tauon
connection and remains available even if discovery fails.

Every resolved numeric address passes `ServerEndpoint.parse` before publication.
A service name or matching address is not authentication, and a name alone does
not establish that a server moved addresses. No subnet sweep, public endpoint,
network configuration change, extra permission or persistent background scan is
introduced. This slice does not implement auto-connect or modify saved addresses.

The min-SDK-compatible [`resolveService` API](https://developer.android.com/reference/android/net/nsd/NsdManager#resolveService(android.net.nsd.NsdServiceInfo,android.net.nsd.NsdManager.ResolveListener))
is retained. Its pending operation is not cancellable on our oldest supported
Android releases. A stopped scan's completion is ignored, but its resolution slot
is kept until its callback arrives so a rapid restart does not overlap operations.
A five-second per-request deadline releases the application slot even if no
platform callback arrives; late callbacks are ignored, so later candidates/scans
can proceed. On older releases the underlying platform request cannot be cancelled;
it may still consume an OS resolver slot and make another request fail. Such
failures stay unresolved, and manual connection remains available. This is not a
claim that the OS operation was cancelled. The deadline survives scan disposal for
at most five seconds; NSD is obtained from application context, not Activity context. Newer callback-based NSD APIs can be evaluated
separately, including IPv6 multi-address support and platform/network permissions.

Service types normalize case and optional root/local-domain suffixes; unrelated
service types/domains are rejected.

JVM tests cover queueing, duplicate/lost services, restart/cancellation races,
deadline/failure states and bounded results. They do not execute Android's NSD
service. Real-device discovery and network lifecycle checks remain user QA;
no phone access is part of this change. Automatic connection, moved-server identity
rules and complete platform permission validation remain later #39 work.
