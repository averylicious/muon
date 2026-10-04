# Actual HTTP source cancellation controls — 2026-10-05

Base main8b02a091d969d24e04a70502fb44e79448d52245, branch codex/http-source-cancellation-control. GPT-6 / Codex desktop, effort not reported, author/source self-check (not independent review). Test/report only; no app/build/dependency changes, phone, external network or user data. Android Actions is the first Kotlin compile/Robolectric execution; device behavior is not verified.

Follow-up to [pinned HTTP body-drain sources](2026-10-05-http-body-drain-source.md), with two actual `OkHttpDataSource` controls, not copied cancellation logic:

1. A loopback-only HTTP1 peer receives a real request but sends no headers. Interrupt the caller waiting in `open`, then require its real Call to report canceled, an OPEN-type interrupted exception, source close return and actual caller-thread termination while the peer still supplied no response.
2. A real fixed-length response sends exactly one of two bytes and keeps the socket open. Media3 reads the first byte; its next read must report a READ-type native JVM socket timeout using a short test timeout. Require source close and actual caller termination while the peer remains held. No exact elapsed-time assertion, no claim of a hard total deadline.

Cleanup releases/closes each peer, cancels client calls, joins source/peer owners and requires dispatcher termination. A failed test still has bounded cleanup. There is no cache to release and no unjoined source worker is silently treated as finished. The test-owned peers bind127.0.0.1 with ephemeral ports, never wildcard/LAN/Tauon addresses.

Limits: HTTP1 and the CI Java socket runtime only, single source owner; configured test timeout differs from Transport.client. No Android/Conscrypt/TLS/HTTP2 timing, mid-body Thread.interrupt behavior, downloader/manager/task/file-close/lease retirement or full#179 preservation evidence. Buffered/in-flight read cancellation is deliberately not inferred from a timing guess. No tests/phone QA for production download behavior are replaced. Follow-up may compose actual downloader/cache writers and stronger native read-entry evidence before drawing those conclusions.

Local git diff/prose checks only at initial commit. CI outcome, full commit and exact executed case evidence will be recorded on the PR/#179. Test-only phone QA is not needed; existing application#302.604 acceptance remains pending and unrelated.
