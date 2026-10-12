# Tauon metadata projection dispatch — 2026-09-30

[#244](https://github.com/averylicious/muon/issues/244), inspected main `8d056b655cdab4c3a82157ca443d7b5ea1532d73`. LibraryModel.connect runs in viewModelScope; TauonApi.json moved response reading/JSONObject construction to IO, but playlists/tracks resumed on the caller context for the full List projection, identifier validation and title/tag normalization. That schedules conversion/allocation on main for potentially large valid arrays. No phone jank, latency or OOM is claimed as reproduced.

Both public list projections now remain in Dispatchers.IO, including their existing JSON call. Schema/validation/fallback fields, response caps, partial-library policy and foreground playback networking remain unchanged. This does not bound aggregate library/DOM/DTO heap use or promise cooperative interruption during a CPU-only loop. Preserve #206's separate finite-request/socket cancellation fix when combining TauonApi changes; #209's load ownership remains independent.

Five actual public TauonApi/OkHttp/Android JSON loopback cases cover validated playlist fields, track fields/filename fallback/optional tag types, invalid returned playlist IDs, negative track IDs and invalid requested playlist IDs before network access. These establish response projection semantics, not UI responsiveness/thread timing. The dispatch placement is source/compile evidence; CI is the first Android compile/execution. No parser mocks, private music, phone or network settings were used. Fixtures close their loopback sockets/executors, including the no-request case.

Manual QA pending: connect/refresh, visible loading/error/partial state, normal playlist/track/search metadata and filename fallback; observe controls during a larger library load if available. Do not infer a measured performance improvement from passing JVM tests. Leave app PR open. Experimental checkout untouched; track-forward integration remains separate.

Attribution: GPT-6 / Codex desktop / effort not reported (Sol), author implementation/source self-check; no independent review/Claude session.
