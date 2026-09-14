# ADR 0002 — SSH on Android: stack, key types, host keys, APK cost

Date: 2026-09-14 · Status: accepted · Scope: roadmap task 8.1a, and a revision of decision D4 in `doc/2026-09-13-git-tab-roadmap.md` ("HTTPS + token first, SSH is a later phase").
Evidence: the flavorAtest-only `GitSshSpikeActivity` (`app/src/flavorAtest/java/net/gsantner/markor/git/spike/`) run on the `api26` emulator image (google_apis, x86_64) in the debug build and in the signed R8 release build, against the private scratch repository `AlexZ005/markor-gittab-testrepo` over SSH. AGP 8.13.2 / Gradle 8.13 / JDK 21. Builds on ADR 0001 (JGit 5.13.5, minSdk 26).

## Decision

1. **SSH stack: `org.eclipse.jgit:org.eclipse.jgit.ssh.jsch:5.13.5.202508271544-r`** (JGit's JSch-based `SshSessionFactory`) with **`com.jcraft:jsch` substituted by `com.github.mwiede:jsch:2.28.7`** through a Gradle `resolutionStrategy.dependencySubstitution`. Apache MINA sshd (`org.eclipse.jgit.ssh.apache`) was **not tried**: JSch passed the whole matrix, in debug and under R8.
2. **Key types for v1: RSA (4096 by default) only.** ECDSA nistp256/384/521 works too and may be offered. **ed25519 is not supported without Bouncy Castle** and Bouncy Castle costs **+2.13 MB / +31,045 methods** in the release APK — seven times the whole SSH stack. Imported ed25519 keys still *parse* and show the right fingerprint, so the key store can name the type and refuse it with a real reason instead of a stack trace.
3. **Key storage format: OpenSSH v1** (`KeyPair.writeOpenSSHv1PrivateKey`). JSch's legacy PEM writer, `KeyPair.writePrivateKey(out, passphrase)`, produces a key **JSch itself cannot read back** when the source key came from an OpenSSH-v1 file (see "Defects found"). Never use it.
4. **Host keys: trust on first use**, with an unhashed OpenSSH `known_hosts` in `Context.getFilesDir()/git/known_hosts`, `StrictHostKeyChecking=ask`, an unknown host offered to the UI with its `SHA256:…` fingerprint, and a **mismatch refused without ever asking**.
5. **The session factory is attached per operation**, through `TransportCommand.setTransportConfigCallback` + `SshTransport.setSshSessionFactory`, never through the global `SshSessionFactory.setInstance`.
6. **One R8 keep rule is required**: `-keep class com.jcraft.jsch.** { <init>(); }`. Without it the release build fails at the first key exchange.
7. Decision **D4 is revised**: SSH is supported alongside HTTPS+token. HTTPS remains the default offered in the dialogs.

## Why JSch, and why the mwiede fork

The plain `com.jcraft:jsch:0.1.55` that `org.eclipse.jgit.ssh.jsch` declares was last released in 2018. It has no `rsa-sha2-256`/`rsa-sha2-512` signature algorithms, and **GitHub stopped accepting SHA-1 `ssh-rsa` signatures in March 2022**, so every RSA key would fail with `Auth fail`. It also has no ed25519, no curve25519 and no modern ciphers. `com.github.mwiede:jsch` is the maintained drop-in fork (same `com.jcraft.jsch` package names, BSD-3-Clause), which is why the substitution is a two-line Gradle change and needs no code:

```gradle
configurations.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute module('com.jcraft:jsch') using module('com.github.mwiede:jsch:2.28.7')
    }
}
```

`./gradlew :app:dependencies` confirms `com.jcraft:jsch:0.1.55 -> com.github.mwiede:jsch:2.28.7`, and the spike's `jsch` row prints `JSch 2.28.7` on the device with `rsa-sha2-256 = com.jcraft.jsch.jce.SignatureRSASHA256` resolvable.

Both `org.eclipse.jgit` and `org.eclipse.jgit.ssh.jsch` ship an OSGi bundle localisation file called `plugin.properties`; the Android resource merger refuses two inputs with the same path, so both are excluded from packaging (`packagingOptions.resources.excludes`). Neither is read at runtime.

### What "no Bouncy Castle" actually means

mwiede JSch is a multi-release jar: on Java 15+ it uses the JDK's own EdDSA, and below that it falls back to `com.jcraft.jsch.bc.*` adapters over Bouncy Castle's lightweight API. **D8 does not honour `META-INF/versions/`**, so Android always gets the Java 8 baseline, and `JavaVersion.getVersion()` returns 8 on the device. JSch therefore registers `com.jcraft.jsch.bc.KeyPairGenEdDSA`, `com.jcraft.jsch.bc.SignatureEd25519` and `com.jcraft.jsch.bc.XDH` — classes that are in the dex but whose Bouncy Castle types are not:

```
gen-ed25519: com.jcraft.jsch.JSchException:
    java.lang.NoClassDefFoundError: Failed resolution of: Lorg/bouncycastle/crypto/params/Ed25519PrivateKeyParameters;
```

JSch prunes what it cannot run: its `CheckKexes`/`CheckSignatures` self-test drops `curve25519-sha256` (needs `bc.XDH`) and the `ssh-ed25519` host key algorithm at session setup, and negotiation falls back to `ecdh-sha2-nistp256` and an `ecdsa-sha2-nistp256` host key, which GitHub offers. Nothing has to be configured for that; it is visible in the results below, where the same `hk-tofu` row stores an **ecdsa** host key without Bouncy Castle and an **ed25519** one with it.

## Results — api26, JSch stack, no Bouncy Castle

`GitSshSpikeActivity`, run twice: debug build, and the R8 release build signed with `~/.android/debug.keystore`. Identical verdicts. `NOSUP` = the stack legitimately cannot do it (not a defect in our code).

### 1. Stack

| Row | Debug | Release (R8) | Detail |
|---|---|---|---|
| `jsch` | PASS | PASS | JSch 2.28.7; `rsa-sha2-256`/`rsa-sha2-512` resolvable |
| `jsch-bc` | PASS | PASS | `org.bouncycastle.*` absent → ed25519 and curve25519 unavailable (as designed) |
| `jgit-ssh` | PASS | PASS | both URL spellings parse; `Transport.open` gives `org.eclipse.jgit.transport.TransportGitSsh` |

### 2. Key generation, in the app, no external tools

| Row | Debug | Release | Detail |
|---|---|---|---|
| `gen-rsa4096` | PASS | PASS | `KeyPair.genKeyPair(jsch, RSA, 4096)`, 353 ms on the emulator; public line and `SHA256:…` fingerprint accepted by `ssh-keygen -lf` on the host |
| `gen-rsa-jce` | PASS | PASS | `java.security.KeyPairGenerator("RSA", 4096)` → PKCS#8 PEM → `KeyPair.load`, 316–660 ms |
| `gen-ed25519` | **NOSUP** | **NOSUP** | `NoClassDefFoundError: org/bouncycastle/crypto/params/Ed25519PrivateKeyParameters` |

Both generation routes give an interchangeable `com.jcraft.jsch.KeyPair`; the key store may use either. `java.security` is the better fit if the private key is to be wrapped by an Android Keystore key, because the PKCS#8 bytes never have to leave `byte[]`.

### 3. Formats the app writes and reads back from bytes (RSA 4096)

| Row | Debug | Release | Detail |
|---|---|---|---|
| `fmt-pkcs1` (`-----BEGIN RSA PRIVATE KEY-----`, no passphrase) | PASS | PASS | 3,247 B, round-trips |
| `fmt-pkcs1-pw` (same, with passphrase) | **FAIL** | **FAIL** | JSch defect, see below — **do not use** |
| `fmt-pkcs1-pw-from-pem` | PASS | PASS | the same write succeeds if the key was loaded from PEM first; this isolates the defect to the vendor-dependent KDF |
| `fmt-openssh` (`-----BEGIN OPENSSH PRIVATE KEY-----`) | PASS | PASS | 3,381 B, round-trips |
| `fmt-openssh-pw` | PASS | PASS | 3,434 B; wrong passphrase rejected, right one accepted, retry on the same object works |
| `fmt-publine` | PASS | PASS | `ssh-rsa AAAA… comment`; base64 body equals the public key blob; fingerprint equals the `HostKey` fingerprint |

### 4. Keys made on a desktop with `ssh-keygen` (pushed to `/sdcard/Download/gitsshspike`)

All nine load from bytes, report the right type and size, and their fingerprint equals the one `ssh-keygen -lf` prints for the matching `.pub`. Passphrase-protected keys reject a wrong passphrase and accept the right one, and a retry on the same `KeyPair` after a wrong attempt works for every one of them.

| Fixture | Header `ssh-keygen` wrote | Debug | Release |
|---|---|---|---|
| `rsa` default | `-----BEGIN OPENSSH PRIVATE KEY-----` | PASS | PASS |
| `rsa` default + passphrase | `-----BEGIN OPENSSH PRIVATE KEY-----` | PASS | PASS |
| `rsa -m PEM` | `-----BEGIN RSA PRIVATE KEY-----` | PASS | PASS |
| `rsa -m PEM` + passphrase | `-----BEGIN RSA PRIVATE KEY-----` (`Proc-Type`/`DEK-Info`) | PASS | PASS |
| `rsa -m PKCS8` | `-----BEGIN PRIVATE KEY-----` | PASS | PASS |
| `rsa -m PKCS8` + passphrase | `-----BEGIN ENCRYPTED PRIVATE KEY-----` | PASS | PASS |
| `ecdsa` (nistp256) | `-----BEGIN OPENSSH PRIVATE KEY-----` | PASS | PASS |
| `ed25519` | `-----BEGIN OPENSSH PRIVATE KEY-----` | PASS (parses) | PASS (parses) |
| `ed25519` + passphrase | `-----BEGIN OPENSSH PRIVATE KEY-----` | PASS (parses) | PASS (parses) |
| `ed25519`, *signing* with it | — | **NOSUP** | **NOSUP** |

So an imported ed25519 key can be read, named and fingerprinted — it just cannot authenticate. The key store must say so at import time.

### 5. Host keys (`/data/user/0/net.gsantner.markor_test/files/git/known_hosts`)

| Row | Debug | Release | Detail |
|---|---|---|---|
| `hk-tofu` | PASS | PASS | asked exactly once; accepted `ecdsa-sha2-nistp256 SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM`, which is **GitHub's published value**; 172 B written, line starts `github.com ` (unhashed) |
| `hk-reuse` | PASS | PASS | second connection: no prompt |
| `hk-mismatch` | PASS | PASS | stored key corrupted keeping its type → `JSchChangedHostKeyException`, the prompt is **never shown**, `known_hosts` is **not** rewritten |
| `hk-decline` | PASS | PASS | prompt answers no → `JSchUnknownHostKeyException`, `known_hosts` stays empty |
| `hk-restore` | PASS | PASS | trusting again writes the same 172 B |

With Bouncy Castle the same rows pass but the stored key is `ssh-ed25519 SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU` (92 B) — also GitHub's published value.

### 6. Operations over SSH

Remote `git@github.com:AlexZ005/markor-gittab-testrepo.git` and `ssh://git@github.com/AlexZ005/markor-gittab-testrepo.git`, authenticating with the RSA 4096 key the app generated, registered as a **deploy key with write access** titled **`spike-api26`** (left in place for the following lanes).

| Row | Debug | Release | Detail (release timings) |
|---|---|---|---|
| `ls-remote-scp` | PASS | PASS | 1.5 s, scp-like spelling |
| `ls-remote-ssh` | PASS | PASS | 1.6 s, `ssh://` spelling |
| `clone` | PASS | PASS | 3.6 s for two clones (one per spelling), `README.md` checked out |
| `push` | PASS | PASS | 2.3 s, `refs/heads/main = OK` |
| `fetch` | PASS | PASS | 1.8 s, tracking ref `FAST_FORWARD` to the pushed commit |
| `pull` | PASS | PASS | 1.5 s, `Fast-forward`, pushed file present in the work tree |
| `wrong-key` (a key GitHub does not know) | PASS | PASS | `Auth fail for methods 'publickey'` → `JGitErrors.map` = **AUTH_FAILED** (after the change in this branch) |
| `unknown-host` | PASS | PASS | `unknown host` → **NETWORK** |

Summary lines: `33 pass, 1 fail, 2 nosup` in both debug and release; the single FAIL is `fmt-pkcs1-pw` below. With Bouncy Castle: `35 pass, 1 fail, 0 nosup`.

## APK cost (flavorAtest, `dexdump -f`; release = unsigned R8 build)

| Build | Branch baseline (no SSH) | + `ssh.jsch` + mwiede JSch | Final (also spike screen, session factory, keep rule) | Final **+ Bouncy Castle** |
|---|---|---|---|---|
| debug, bytes | 14,851,529 | 15,170,880 | 15,192,724 | 17,736,125 |
| debug, methods | 107,195 | 110,716 | 111,004 | 147,085 |
| release, bytes | 12,198,218 | 12,490,639 | 12,516,075 | 14,649,411 |
| release, methods | 90,645 | 93,786 | 94,152 | 125,197 |

- **The SSH stack costs +292,421 B (+2.40 %) and +3,141 methods** in the release APK; +319,351 B (+2.15 %) and +3,521 methods in debug. For comparison, JGit itself cost +937,513 B / +15,034 methods (ADR 0001).
- The keep rule below is worth **+8,784 B and +149 methods** in release (12,507,291 → 12,516,075 with everything else equal).
- **Bouncy Castle `org.bouncycastle:bcprov-jdk18on:1.86` costs +2,133,336 B (+17.0 %) and +31,045 methods** in release, +2,543,401 B and +36,081 methods in debug — and that is *after* R8 shrinking, with no extra keep rules needed.
- The shipping flavour is in the same place: `flavorDefault` release, unsigned, 12,471,039 B with SSH included.

**Why ed25519 is not worth 2.1 MB in v1.** RSA 4096 with `rsa-sha2-256/512` is accepted by GitHub, GitLab, Gitea/Forgejo and Codeberg; nobody requires ed25519. Markor's README sells a small, single-ABI APK, and 17 % is more than the entire git feature added so far. ECDSA nistp256 is available at no extra cost for users who want a small modern key. The decision is one line of `app/build.gradle` to reverse if the trade-off ever changes, and the spike screen re-measures it.

## R8 (release build) and keep rules

Added to `app/proguard-rules.pro`:

```proguard
-keep class com.jcraft.jsch.** { <init>(); }
```

**The failure it fixes.** Every cipher, MAC, key exchange, signature, hash, random and key-pair generator JSch uses is named as a *string* in JSch's config map and instantiated with `Class.forName(name).getDeclaredConstructor().newInstance()`. R8 sees no reference and removes them. Without the rule, the release build still loads and generates keys (those `jce.*` classes happen to survive), but the first connection dies in the key exchange:

```
TransportException: git@github.com:AlexZ005/markor-gittab-testrepo.git:
    java.lang.ClassNotFoundException: com.jcraft.jsch.DHEC256
```

and every row of sections 5 and 6 fails (`21 pass, 13 fail`). Keeping the no-argument constructors is enough — the interfaces (`Cipher`, `HASH`, `Signature`, `KeyExchange`, `UserAuth`, `KeyPairGen*`) are referenced from JSch's own code, so the implementations' overriding methods are kept with their classes. With the rule the release table is identical to debug.

No other rule was needed. In particular:

- `JSchText extends TranslationBundle` is already covered by ADR 0001's `-keepclassmembers class * extends org.eclipse.jgit.nls.TranslationBundle { public <fields>; }`, and `JSchText.properties` is packaged.
- `org.eclipse.jgit.ssh.jsch` ships `META-INF/services/org.eclipse.jgit.transport.SshSessionFactory`. It is irrelevant here because the factory is set per operation; `Transport.open` and `SshTransport` worked in the release build regardless.
- No `NoSuchMethodError` and no other `NoClassDefFoundError` was seen at any point.

**New missing-class warnings** that `-ignorewarnings` (already present) hides, all from code Android will never reach: `org.bouncycastle.**` (48, from the `com.jcraft.jsch.bc.*` adapters), `com.sun.jna.**` (12, from `PageantConnector` — Windows agent), `org.newsclub.net.unix.*` (3, `JUnixSocketFactory` — Unix-domain agent socket), `org.apache.logging.log4j.*` (3, `Log4j2Logger`), `org.ietf.jgss.*` (5, `jgss.GSSContextKrb5` — Kerberos). No keep rule can help and none is wanted; the session factory sets `PreferredAuthentications=publickey`, so none of the agent or GSS paths is taken.

## Defects found

1. **`KeyPair.writePrivateKey(out, passphrase)` writes a key JSch cannot read back** (row `fmt-pkcs1-pw`, debug and release). `KeyPair.genKey` derives the encryption key from the *vendor the key was loaded as*: a key that came from an OpenSSH-v1 file has `vendor == VENDOR_OPENSSH_V1` and uses the bcrypt KDF, while the legacy PEM that is written declares `DEK-Info: DES-EDE3-CBC` and is read back with OpenSSL's MD5 KDF. The two do not match, so the right passphrase is rejected. Proof: `fmt-pkcs1-pw-from-pem` does the same write on the same key re-loaded from its unencrypted PEM (`vendor == VENDOR_OPENSSH`) and round-trips. **Mitigation: the key store writes OpenSSH v1 (`writeOpenSSHv1PrivateKey`), which round-trips in every case tested.** Legacy PEM is only ever *read*, for imports.
2. **A failed passphrase attempt can leave the `KeyPair` unusable** for some formats. Every fixture tested tolerated a retry on the same object, but this is not guaranteed; the passphrase dialog must re-`KeyPair.load` the bytes for each attempt rather than retry `decrypt` on a cached object.
3. **`JGitErrors.map` did not recognise JSch's wording.** `Auth fail for methods 'publickey'` came out as `FAILED`. Fixed in this branch: `auth fail`, `auth cancel`, `userauth` and `publickey` now map to `AUTH_FAILED`, with unit tests in `JGitErrorsTest.sshPublickeyFailuresBecomeAuthFailed`. Host key problems deliberately stay `FAILED` — they are not credential problems and must not be answered with "check your key".
4. **`GitResult.Kind` has no host-key kind.** Until it does, the UI asks `GitSshSessionFactory.isHostKeyMismatch(t)` / `isUnknownHostKey(t)`, which walk the cause chain for `JSchChangedHostKeyException` / `JSchUnknownHostKeyException`. Both are exercised by the spike.

## The known_hosts model

- File: `Context.getFilesDir()/git/known_hosts`, app-private, created empty on first use. Plain OpenSSH format, **not** hashed (`HashKnownHosts=no`), so the Git tab can show which hosts are trusted and let the user forget one.
- `StrictHostKeyChecking=ask`. A `HostKeyRepository` wrapper records what the last `check()` returned, so the `UserInfo` that answers the prompt knows whether it is looking at `NOT_INCLUDED` (offer it) or `CHANGED` (refuse, never ask) without parsing JSch's English prompt text.
- The prompt callback gets `(host, keyType, "SHA256:…")` — the exact spelling GitHub, GitLab and `ssh-keygen -lf` show, so the confirmation dialog can be compared against a published value by eye.
- A mismatch fails the operation and leaves the file untouched. Replacing a stored key must be an explicit, separate user action in the Git tab, never a button inside an error dialog.

## What the next two lanes must implement

### (a) 8.1b — key store and Settings UI

**Shipped 2026-09-14.** What it ended up as, where it differs from the list below:

- `GitSshKeyStore` (plain Java, JVM-tested) over `filesDir/git/ssh`: `index.json` with names, types, sizes,
  public-key lines, fingerprints and the default key id, and `<id>/key.enc` per key — the private key
  wrapped with an AES/GCM key in the Android Keystore (`GitSshKeyStores.KeystoreVault`, alias
  `markor.git.ssh.vault`). Not `PasswordStore`: that keeps its ciphertext in `SharedPreferences`, which is
  the wrong place for a private key; only the encryption key is in the Keystore here.
- **Generated** keys are OpenSSH v1 without a passphrase, as decided. **Imported** keys are stored *exactly
  as the file had them* instead of being rewritten: a passphrase-protected file therefore stays protected,
  `GitSshKey.hasPassphrase()` is `true`, and the passphrase is never stored — 8.1c has to ask for it before
  the operation. It also means no format conversion can damage an imported key.
- `GitSshKeySelection.resolve(repo, store)` is the only place that answers "which key does this repository
  use". A repository that names a key the store no longer has resolves to **nothing**, not to the default:
  substituting another identity behind the user's back is what the 7.5 review refuses elsewhere.
  `isSelectedKeyMissing()` is how the UI says so.
- An ed25519 import is accepted, named and fingerprinted, and `Type.canAuthenticate()` is `false` for it:
  `setDefault` refuses it, the per-repository chooser leaves it out, and the list says "This build cannot
  sign with this key type". Note JSch reports its key size in **bytes** (32), so `GitSshKeyStore.bitsOf`
  converts it to the 256 that `ssh-keygen` prints.
- `getDefault()` reports what the index says even if that key cannot authenticate (only an index written by
  another build can get into that state), so **8.1c must check `canAuthenticate()`** before building an
  `Identity` rather than trusting the selection.
- The private key leaves the store only through `loadPrivateKey(id)`, as bytes the caller wipes; 8.1c builds
  `GitSshSessionFactory.Identity(name, privateKeyBytes, publicKeyLine bytes, passphraseBytes)` from it.
- `RemoteSetupDialog` shows the "SSH key for this repository" row as soon as the typed URL is SSH-shaped,
  which it asks `GitRemoteUrlValidator` for by looking at `Problem.SSH_NOT_SUPPORTED`. When 8.1c replaces
  that with a transport marker on a *valid* result, `RemoteSetupDialog.isSshUrl` reads the marker instead.
  The pick is stored immediately, not on Save, because the URL next to it is still refused on that branch.

1. Keys in app-private storage, the private key encrypted with an Android Keystore-wrapped key, exactly as `GitCredentialStore` already does for the token. Write **OpenSSH v1** bytes; never `writePrivateKey(out, passphrase)`.
2. A registry with one **default key**, and `GitRepoConfig.sshKeyId` for the per-repository override (default = the app default). Gson-serialised like the rest of `GitRepoConfig`; remember the ProGuard `-keepclassmembers` rule for new serialised fields.
3. Settings > Git, identity section, right after "Author name"/"Author e-mail": **generate** (RSA 4096 default; offer ECDSA nistp256; **do not offer ed25519**), **import from file**, **copy/share the public key**, **set default**, **delete**. Show `type · bits · SHA256:…` for each key.
4. On import: `KeyPair.load(jsch, prvBytes, pubBytes)` accepts OpenSSH, PKCS#1 PEM and PKCS#8, encrypted or not. If `getKeyType()` is `ED25519`/`ED448`, accept the import for display but refuse to select it, with the reason ("this build has no ed25519 support"). Re-load the bytes for each passphrase attempt.
5. Hand the transport lane a `GitSshSessionFactory.Identity(name, privateKeyBytes, publicKeyBytes, passphraseBytes)` and call `Identity.wipe()` in the operation's `finally`, the way `GitFixedCredentials` already does for the token.

### (b) 8.1c — transport wiring and the repository override

1. **`JGitRemoteOps`**: every remote command already takes `.setCredentialsProvider(...)`; add `.setTransportConfigCallback(t -> { if (t instanceof SshTransport) ((SshTransport) t).setSshSessionFactory(factory); })` for clone, fetch, pull, push and `lsRemote`, with a **fresh factory per operation**. `PullCommand` in JGit 5.13 has `setTransportConfigCallback`, verified by the spike. Do **not** use `SshSessionFactory.setInstance`: it is process-global, so one repository's key would leak into another repository's operation, and concurrent operations would race.
2. **`GitRemoteUrlValidator`**: `Problem.SSH_NOT_SUPPORTED` goes away and the result grows a transport marker (HTTPS vs SSH) so the dialogs know whether to ask for a token or a key.
   - Accept `ssh://[user@]host[:port]/path` and the scp-like `[user@]host:path`. `org.eclipse.jgit.transport.URIish` parses both correctly — the spike's `jgit-ssh` row asserts `user=git, host=github.com` for each.
   - Keep refusing a password in the URL (`ssh://user:pass@host/…`), and keep refusing `http://`.
   - **The scp-like form without a user must be refused or defaulted to `git`.** On Android there is no `~/.ssh/config` and no login name, so `github.com:me/notes.git` reaches JSch with a null user and fails with an unhelpful `JSchException`.
3. **Credentials**: `GitCredentialStore` keys the token by host; an SSH remote has no token. The remote-setup and clone dialogs must switch to "SSH key: <default> / choose…" when the URL is SSH, and must not store an empty token for that host.
4. **Host keys**: the first contact raises a confirmation dialog showing host, key type and `SHA256:…` fingerprint (the callback runs on the git worker thread — post to the main thread and block the operation until the user answers). On mismatch, refuse with wording of its own: `GitSshSessionFactory.isHostKeyMismatch(t)`. Consider adding a `HOST_KEY_MISMATCH` kind to `GitResult` rather than leaving it as `FAILED`.
5. **Error mapping** is already in place: `publickey` failures are `AUTH_FAILED`, an unresolvable host is `NETWORK`. `GitUiText.messageFor` should say "check the SSH key" rather than "check username and token" when the remote is SSH.
6. Passphrase: `GitSshSessionFactory` takes the passphrase with the identity and never prompts interactively — the UI must ask before the operation starts.
7. **Coordinate with the security review.** This spike was cut from `feature/git-tab` before PR #13 (roadmap task 7.5) merged, so `GitRemoteUrlPolicy` and `JGitCredentials.isTlsUri` do not exist in this branch. That review establishes that **`.git/config` is untrusted input**: repositories live on `/storage/emulated/0`, so any app with storage permission can rewrite a remote URL, and every URL read off disk is re-checked at the point of use. Allowing SSH therefore means *deliberately extending that policy*, not widening a scheme allowlist. In particular, an attacker who rewrites `.git/config` must not be able to turn an HTTPS remote into an SSH one that quietly authenticates with the user's key against a host of their choosing — trust-on-first-use does not protect the first contact, it only asks a user who has no way to judge the answer. A reasonable rule: an SSH remote is only used when the repository's stored `GitRepoConfig` says the remote is SSH and names a key, and a URL that changed underneath is refused, exactly as the HTTPS policy already does.

## How to reproduce

```bash
. ~/.config/android-dev.env
./gradlew --console=plain assembleFlavorAtestDebug assembleFlavorAtestRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android \
    --key-pass pass:android --ks-key-alias androiddebugkey --out /tmp/ssh-spike-release.apk \
    app/build/outputs/apk/flavorAtest/release/*-release-unsigned.apk

# optional: the nine desktop keys for the import rows (section 4 reports SKIP without them).
# Names must match exactly; the passphrase for the "_pw" ones is "spike-passphrase".
ssh-keygen -q -t rsa     -b 2048 -N ''                 -C spike -f import_rsa_openssh
ssh-keygen -q -t rsa     -b 2048 -N 'spike-passphrase' -C spike -f import_rsa_openssh_pw
ssh-keygen -q -t rsa     -b 2048 -N ''                 -m PEM   -C spike -f import_rsa_pem
ssh-keygen -q -t rsa     -b 2048 -N 'spike-passphrase' -m PEM   -C spike -f import_rsa_pem_pw
ssh-keygen -q -t rsa     -b 2048 -N ''                 -m PKCS8 -C spike -f import_rsa_pkcs8
ssh-keygen -q -t rsa     -b 2048 -N 'spike-passphrase' -m PKCS8 -C spike -f import_rsa_pkcs8_pw
ssh-keygen -q -t ecdsa   -b 256  -N ''                 -C spike -f import_ecdsa
ssh-keygen -q -t ed25519         -N ''                 -C spike -f import_ed25519
ssh-keygen -q -t ed25519         -N 'spike-passphrase' -C spike -f import_ed25519_pw
adb shell mkdir -p /sdcard/Download/gitsshspike && adb push import_* /sdcard/Download/gitsshspike/

adb install -r -g /tmp/ssh-spike-release.apk
adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.spike.GitSshSpikeActivity
adb logcat -d -v raw -s GitSshSpike        # PASS / FAIL / NOSUP per row, then the SUMMARY line
```

The first run generates the RSA key, prints its public line and copies it to
`/storage/emulated/0/Android/data/net.gsantner.markor_test/files/gitsshspike/deploy_rsa.pub`. Register that
as a deploy key once and every later run reuses the key:

```bash
adb pull /storage/emulated/0/Android/data/net.gsantner.markor_test/files/gitsshspike/deploy_rsa.pub
gh repo deploy-key add deploy_rsa.pub --allow-write --title "spike-api26" --repo AlexZ005/markor-gittab-testrepo
```

The private key never leaves the emulator and is never printed or logged.

## Outcome of 8.1c (2026-09-14): what was built, and the two choices this ADR left open

### The session factory is per operation, not global

The lane brief raised the alternative: JGit 5.13's `SshSessionFactory` is a process-global singleton,
so it could be set once in `ApplicationObject` with a factory that finds the current operation's
repository through a thread-local. That was **not** done, and decision 5 above stands. A thread-local
would have to be set and cleared around every remote call anyway — the same number of places as the
transport callback — and it would leave a window in which a global factory holds one repository's
key while another repository's operation is running on another thread. `JGitSsh` builds a fresh
factory, with a freshly decrypted key, for each of clone, fetch, pull, push and ls-remote, and wipes
the key in the operation's `finally`. Nothing is global and nothing is shared.

The one thing the brief's option would have bought — a factory that is installed even for a code path
nobody remembered to wire — is covered differently: an operation whose URL the policy calls SSH and
that has no key is *refused*, never attempted.

### The URL policy grew a transport, and a second gate

Item 7 of "what 8.1c must implement" asked for a rule stronger than a scheme allowlist. What shipped:
`GitRemoteUrlPolicy.decide` returns `HTTPS`, `SSH` or `LOCAL`, which decides which credential may be
used; `GitSshRemoteTrust` refuses a key unless the app's own record already says this repository is
that SSH remote. Written up in full in the addendum to
`doc/2026-09-13-git-tab-security-review.md`, including the defect that found —
`GitFragment.refresh()` was copying `.git/config`'s remote URL into that record.

### What the device run added to the tables above

Same emulator image and the same signed R8 release APK as the spike, but driving the **real app**
rather than `GitSshSpikeActivity`, against `git@github.com:AlexZ005/markor-gittab-testrepo.git` with a
key generated in the app and registered as a read-write deploy key (`transport-api26-8.1c`):

| Step | Debug | Release (R8) |
|---|---|---|
| SSH URL typed → token fields hidden, SSH key row shown | PASS | PASS |
| key generated in the app; its `SHA256:…` equals `ssh-keygen -lf` on the host | PASS | PASS |
| first contact → fingerprint dialog with GitHub's published `SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM` (ecdsa-sha2-nistp256) | PASS | PASS |
| *Cancel* → operation fails, `known_hosts` stays empty | PASS | — |
| *Trust* → clone | PASS | PASS |
| second run: no prompt | PASS | PASS |
| commit + push | PASS | PASS |
| stored host key corrupted (type kept) → "host key changed", no prompt, file untouched | PASS | — |
| Settings ▸ Git ▸ Known SSH servers → Forget → asked again on the next connection | PASS | PASS |
| key GitHub does not know → "The key is not authorised for this repository" | PASS | — |
| `.git/config` remote rewritten to another host → key not offered, named refusal | PASS | — |
| per-repository key decides: the repo's read-write key pushes, the read-only default is not used | PASS | PASS |

The ed25519 fixture the key store holds is listed by the chooser only through the key manager; it is
not offered as a choice, so an unusable key cannot be selected by accident.

**No new R8 rule was needed.** The one keep rule from decision 6 is still the only one, and
`-ignorewarnings` still hides the same missing-class warnings. The release APK reads the key store,
parses `known_hosts`, negotiates ecdsa-sha2-nistp256 and pushes.
