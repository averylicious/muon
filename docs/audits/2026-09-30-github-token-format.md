# GitHub installation-token format check — 2026-09-30

## Result and scope

The [GitHub announcement](https://github.blog/changelog/2026-05-15-github-app-installation-tokens-per-request-override-header/) includes Actions `GITHUB_TOKEN` in its longer stateless installation-token rollout. Muon's workflow therefore uses an affected token category, but no incompatible handling was found in the inspected repository code and checkout source. No token-handling patch is proposed from this review.

Inspected main `f9f27f75b568c0d2a29e7358d692751f8636c9d4`; publication/selector workflow sources are unchanged from the earlier `fbfeaa21f751b30884205e210865bcb0ee4ffed4` baseline.

## Evidence

- `.github/workflows/android.yml` passes `${{ github.token }}` as `GH_TOKEN` to the selector and publisher. It does not construct, split or cap the token. Checkout uses `persist-credentials: false`.
- `tools/ci_scope.py:published_canaries` and `tools/publish-release.py:gh` invoke the GitHub CLI with the inherited environment. Their regular expressions validate release names, repository names and commit metadata, not credentials. No custom token validator, fixed-length token storage or installation-token minting request was found in these paths.
- Pinned checkout `d23441a48e516b6c34aea4fa41551a30e30af803`: `src/input-helper.ts` reads the token string; `src/git-auth-helper.ts` encodes the entire `x-access-token:<token>` value for the HTTP authorization header. The inspected code has no prefix parser, fixed-length substring or token-specific length cap. Sources were read from the pinned official `actions/checkout` repository via GitHub API.
- The Android client does not call GitHub APIs or store these credentials. The workflow does not supply an installation token to its artifact Action inputs; those use the Actions runtime artifact authentication. This observation is not a complete review of each Action's bundled dependency implementation.

## What remains unverified

A successful existing CI run does not establish which token format GitHub issued. No real token was printed, inspected for its shape, minted or stored as an audit artifact. A forced new-format end-to-end test was **not performed**; this is qualified source compatibility evidence, not complete upstream/runtime certification.

GitHub's temporary `X-GitHub-Stateless-S2S-Token: enabled` override applies to `POST /app/installations/:installation_id/access_tokens` while minting a token. Adding it to our normal `gh` requests cannot transform an already supplied Actions token. The repository has no custom minting flow; introducing App keys or a second credential solely for this check is unnecessary at this boundary. If a future owned GitHub App integration mints tokens, test both formats there without logging credentials.

Separate installed GitHub integrations are maintained by their providers and were not audited here. Personal access tokens, SSH keys and APK signing material are not this announcement's installation-token format. Recheck compatibility if custom token validation/storage or a new App integration is introduced.
