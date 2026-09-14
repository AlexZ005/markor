# ADR 0001 — JGit on Android: version, minSdk, APK cost

Date: 2026-09-13 · Status: accepted · Scope: roadmap Phase 1 (tasks 1.1–1.4), decisions D2/D3 in `doc/2026-09-13-git-tab-roadmap.md`.
Evidence: the flavorAtest-only `GitSpikeActivity` (`app/src/flavorAtest/java/net/gsantner/markor/git/spike/`) run on the `api26` and `api21` emulator images (google_apis, x86_64) in debug and R8 release builds, AGP 8.13.2 / Gradle 8.13 / JDK 21.

## Decision

1. **JGit `org.eclipse.jgit:org.eclipse.jgit:5.13.5.202508271544-r`** (Java 8 bytecode, the maintained security-fix branch) with `org.slf4j:slf4j-nop:1.7.36`. Not 7.x, not 6.x (see below).
2. **minSdk 18 → 26.** JGit does not work on the desugared `java.nio.file` below API 26; from API 26 the platform implementation is used and everything passes. Done in this branch (`app/build.gradle`).
3. **Core-library desugaring removed** (`coreLibraryDesugaringEnabled`, `desugar_jdk_libs_nio` 2.1.5). With minSdk 26 nothing in the app needs it; the spike table passes without it in debug and release.
4. **R8:** no JGit-specific keep rule is needed for HTTPS operations; one defensive rule pins JGit's reflective message bundle. `-ignorewarnings` stays.
5. **Code written against JGit 6.x must compile against 5.13:** the only hit was `ProgressMonitor.showDuration(boolean)` (6.x only), removed from `JGitProgressMonitor`. A list of public APIs present in 6.10.1 and absent in 5.13.5 is in the git-spike handover; the commonly used ones are shallow clone/fetch (`setDepth`, `setShallowSince`), `CommitCommand.setCredentialsProvider`, `AddCommand.setRenormalize`, `DiffCommand.setShowNameOnly`, `Config.removeSection`, `BranchConfig.getPushRemote`, and the `Duration` variants of `BatchingProgressMonitor`.
6. Known gaps the service layer must handle are listed at the end; the first one (auto-GC → JMX) is mandatory.

## Why 5.13.5

| Version | Bytecode | D8 (AGP 8.13.2) | Runtime on API 26 | Verdict |
|---|---|---|---|---|
| 7.8.0.202609011348-r | Java 17, uses records | **Fails to dex**: `Attempt to create a global synthetic for 'Record desugaring' without a global-synthetics consumer`. D8 cannot desugar records inside a dependency; `android.useFullClasspathForDexingTransform=true` does not help. | — | rejected |
| 6.10.1.202505221210-r | Java 11 (class major 55) | dexes; debug APK builds | **Only `init` passes.** Every later operation dies with `java.lang.NoSuchMethodError: No virtual method readNBytes(I)[B in class Lorg/eclipse/jgit/util/io/SilentFileInputStream;` at `org.eclipse.jgit.util.IO.readFully(IO.java:90)` ← `FileBasedConfig.load` (reading `.git/config`). The same call is reached from `RepositoryCache$FileKey.isValidHead` (reading `.git/HEAD`), so merely detecting an existing repository fails too. | rejected |
| 5.13.5.202508271544-r | Java 8 (class major 52) | dexes | **All rows pass**, debug and release, internal and external storage. | **chosen** |

Why 6.x cannot be rescued: `InputStream.readNBytes(int)` is a Java 11 library method that Android added in API 33. Core-library desugaring backfills whole packages (`java.nio.file`, `java.time`, `java.util.stream`, …) and D8 backports some static helpers, but it cannot add an instance method to `java.io.InputStream`, and here the receiver is JGit's own subclass `SilentFileInputStream`, so no retargeting is possible at any minSdk. A scan of the 6.10.1 jar against the SDK's `platforms/android-35/data/api-versions.xml` (every JDK method reference resolved up the class hierarchy) finds **25 distinct JDK methods with an Android API level above 26**, 13 of them at API 33: `InputStream.readNBytes(int)`, `readNBytes(byte[],int,int)`, `readAllBytes()`, `transferTo(OutputStream)`, `nullInputStream()`, `OutputStream.nullOutputStream()`, `Collection.toArray(IntFunction)`, `Arrays.equals(byte[],int,int,byte[],int,int)`, `IndexOutOfBoundsException(int)`, `String.strip()`, `stripTrailing()`, `Optional.isEmpty()`, `Collectors.toUnmodifiableList()`; plus `Duration.toMillisPart/toMinutesPart/toSecondsPart` and `Byte.compareUnsigned` (API 31), `List.of`/`Set.of`/`Objects.checkFromIndexSize` (API 30). The same scan of 5.13.5 finds **none above API 26**; everything above API 21 is `java.nio.file.*`, `java.time.*`, `java.util.stream/function/Optional`, `UncheckedIOException`, `LongAdder`, `StringJoiner`, i.e. the desugared library plus a few API 24–26 methods.

## Why minSdk 26

With `desugar_jdk_libs_nio` 2.1.5 on API 21, JGit 5.13.5 fails at the first file-attribute call, in debug and release, on internal (`/data/data`) and external (`/storage/sdcard`, vfat) storage:

```
init / clone (FileRepository.create probes core.filemode):
java.lang.UnsupportedOperationException
    at j$.desugar.sun.nio.fs.DesugarLinuxFileSystemProvider.readAttributes(DesugarLinuxFileSystemProvider.java:309)
    at j$.nio.file.Files.readAttributes(Files.java:1768)
    at j$.nio.file.Files.getPosixFilePermissions(Files.java:2042)
    at org.eclipse.jgit.util.FS_POSIX.setExecute(FS_POSIX.java:218)
    at org.eclipse.jgit.internal.storage.file.FileRepository.create(FileRepository.java:253)
    at org.eclipse.jgit.api.InitCommand.call(InitCommand.java:103)

existing repository (hand-written .git skeleton, then Git.open + status):
java.lang.NullPointerException: Attempt to invoke interface method
    'j$.nio.file.attribute.PosixFileAttributes j$.nio.file.attribute.PosixFileAttributeView.readAttributes()' on a null object reference
    at org.eclipse.jgit.util.FileUtils.getFileAttributesPosix(FileUtils.java:886)
    at org.eclipse.jgit.util.FS_POSIX.getAttributes(FS_POSIX.java:292)
    at org.eclipse.jgit.treewalk.FileTreeIterator$FileEntry.<init>(FileTreeIterator.java:333)
    at org.eclipse.jgit.util.FS.list(FS.java:1898)
```

The desugared provider has no POSIX attribute view: `Files.getPosixFilePermissions` throws and `getFileAttributeView(path, PosixFileAttributeView.class)` returns null. JGit selects `FS_POSIX` on every non-Windows platform and reads POSIX attributes for every working-tree entry (status, add, checkout), so nothing usable is left below API 26. A fork-side `FS_POSIX` subclass overriding `setExecute`/`canExecute`/`getAttributes` was considered and rejected: it would replace JGit's most exercised file-system code with untested fork code, and the roadmap's fallback for exactly this outcome is minSdk 26 (Android 8.0, 2017).

## Results (JGit 5.13.5, `GitSpikeActivity`, remote = private GitHub repo over HTTPS with a token)

Each row runs in internal storage and in the app's external-storage directory; results were identical for both. API 26 was run twice: with desugaring and minSdk 18 (before the decision) and with the final configuration (minSdk 26, no desugaring), same results.

| Operation | API 26 debug | API 26 release (R8) | API 21 debug | API 21 release (R8) |
|---|---|---|---|---|
| init | PASS | PASS | FAIL `UnsupportedOperationException` (above) | FAIL (same) |
| add | PASS | PASS | not reached (no repo) | not reached |
| commit | PASS | PASS | not reached | not reached |
| log | PASS | PASS | not reached | not reached |
| status | PASS | PASS | not reached | not reached |
| diff (work tree vs index, unified text) | PASS | PASS | not reached | not reached |
| clone (HTTPS, two clones) | PASS | PASS | FAIL `UnsupportedOperationException` (same probe) | FAIL (same) |
| push (new commit → `main`, status OK) | PASS | PASS | not reached | not reached |
| fetch (tracking ref FAST_FORWARD to the pushed commit) | PASS | PASS | not reached | not reached |
| pull (merge FAST_FORWARD, pushed file present) | PASS | PASS | not reached | not reached |
| auth failure (wrong token → `TransportException: … not authorized`) | PASS | PASS | FAIL (clone probe, as above) | FAIL (same) |
| open existing repo + status + add + commit | PASS | PASS | FAIL `NullPointerException` (above) | FAIL (same) |

Emulator timings (API 26, release): local operations 7–105 ms, clone 2.2–3.8 s for two clones of a one-file repo, push 1.5–2.0 s, fetch about 1 s, pull about 0.8 s. JGit 6.10.1 on API 26 (debug, minSdk 18 + desugaring): init PASS, all other rows FAIL with the `NoSuchMethodError` above.

## APK cost (flavorAtest, measured with `dexdump -f`; release = unsigned R8 build)

| Build | v2.16.1 baseline, minSdk 18 | v2.16.1 baseline rebuilt at minSdk 26 | + JGit 5.13.5 only, minSdk 26, no desugaring | JGit cost |
|---|---|---|---|---|
| debug | 14,567,037 B, 87,731 methods | 13,310,938 B, 88,539 methods | 14,556,964 B, 104,389 methods | +1,246,026 B (+9.4 %), +15,850 methods |
| release | 11,871,941 B, 76,676 methods | 11,025,488 B, 73,865 methods | 11,963,001 B, 88,899 methods | +937,513 B (+8.5 %), +15,034 methods |

For comparison, the first configuration (minSdk 18 + `desugar_jdk_libs_nio`) cost +2,476,581 B / +30,846 methods in debug and +1,205,215 B / +20,969 methods in release; the desugared library alone was about a quarter of that. Whole branch at the time of this ADR (JGit + the Phase 2/4 code already on `feature/git-tab`, minSdk 26): debug 14,680,960 B / 105,992 methods, release 12,049,004 B / 89,784 methods. R8 removes 363 of JGit's 1,501 classes.

## R8 (release build) and keep rules

- No JGit operation failed in the release build without keep rules. `seeds.txt` shows R8 kept all 829 public `JGitText` fields by name (it recognises the `getClass().getFields()` reflection in `TranslationBundle.load()`), and the auth-failure row confirmed that error messages resolve. The JGit 5.13.5 jar contains no `META-INF/services` files, and the HTTPS and file transports are registered statically in `Transport`, so `ServiceLoader` has nothing to lose. `JGitText.properties`, `RepoText.properties` and `DfsText.properties` are packaged.
- Added to `app/proguard-rules.pro` (reason in the file): `-keepclassmembers class * extends org.eclipse.jgit.nls.TranslationBundle { public <fields>; }`. Nothing broke without it; it pins the field names so a later R8 cannot silently turn every JGit message into a `TranslationStringMissingException`. Release APK size is unchanged by it.
- `-ignorewarnings` stays (it was already there for flexmark's `java.awt`). The JGit classes it hides are genuinely absent on Android and no keep rule can help: `java.lang.management.*` and `javax.management.*` (JMX: `GC$PidLock.getPID`, `Monitoring.registerMBean`, `WindowCacheStats`) and `org.ietf.jgss.*` (Kerberos/Negotiate HTTP auth in `HttpAuthMethod$Negotiate`).

## Known gaps and requirements for Phase 2

1. **Auto-GC will crash.** `Transport` (after fetch), `MergeCommand`, `RebaseCommand` and `ReceivePack` call `Repository.autoGC`; when a repository exceeds `gc.auto` (6,700 loose objects) or `gc.autoPackLimit` (50 packs), `GC.gc()` takes a `GC$PidLock` whose `getPID()` calls `java.lang.management.ManagementFactory` → `NoClassDefFoundError` on Android. `GitService` must write `gc.auto = 0` and `gc.autoPackLimit = 0` into every repository config it opens or creates, and must never expose `git.gc()`. Large repositories will need a different housekeeping story later.
2. **HTTP connection leaks are logged** by the platform (`W/OkHttpClient: A connection to https://github.com/ was leaked. Did you forget to close a response body?`, several per clone/fetch/push): JGit's `TransportHttp` over `HttpURLConnection`. Harmless in the spike; watch for socket exhaustion in long sessions. Alternative if needed: a custom `HttpConnectionFactory`.
3. **No user-level git config on Android.** `user.home` is empty and `$HOME` unset, so JGit finds no `~/.gitconfig`; author name and e-mail must be written to the repository config (roadmap 4.1 already does this).
4. **Only GitHub was exercised** (TLS roots of the emulator image, token auth, smart HTTP). GitLab and Gitea/Forgejo are roadmap task 5.1.
5. **API 21 external storage is vfat** on the emulator (no permissions, 2 s mtime resolution). Irrelevant with minSdk 26, where `/storage/emulated/0` passed all rows.

## How to reproduce

```bash
. ~/.config/android-dev.env
./gradlew --console=plain assembleFlavorAtestDebug assembleFlavorAtestRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner sign --ks ~/.android/debug.keystore --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey --out /tmp/spike-release.apk app/build/outputs/apk/flavorAtest/release/*-release-unsigned.apk
gh auth token > gittab-token.txt && adb push gittab-token.txt /sdcard/Download/gittab-token.txt   # private scratch repo AlexZ005/markor-gittab-testrepo
adb install -r -g <apk> && adb shell pm grant net.gsantner.markor_test android.permission.READ_EXTERNAL_STORAGE
adb shell am start -n net.gsantner.markor_test/net.gsantner.markor.git.spike.GitSpikeActivity
adb logcat -d -v threadtime | grep ' GitSpike: '      # RESULT lines carry the per-operation verdicts
```
