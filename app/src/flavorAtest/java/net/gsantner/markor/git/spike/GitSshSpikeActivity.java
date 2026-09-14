/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.spike;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.util.TypedValue;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import net.gsantner.markor.BuildConfig;
import net.gsantner.markor.git.GitResult;
import net.gsantner.markor.git.GitSshSpikeErrorBridge;
import net.gsantner.markor.git.ssh.GitSshSessionFactory;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.SshTransport;
import org.eclipse.jgit.transport.Transport;
import org.eclipse.jgit.transport.URIish;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.KeyPair;

/**
 * Throwaway feasibility screen for SSH in the Git tab (roadmap task 8.1a): which SSH stack works
 * with JGit 5.13.5 on Android, which key types and formats it can generate and import, whether
 * trust-on-first-use host keys behave, and whether clone/fetch/pull/push work over SSH. Rows print
 * PASS/FAIL to the screen and to logcat (tag {@code GitSshSpike}). Exists in flavorAtest only.
 * <p>
 * The private key is generated in the app on the first run and kept in the app's private files dir;
 * its <b>public</b> half is copied to the external files dir so it can be pulled with adb and
 * registered as a GitHub deploy key. Private keys and passphrases are never printed and never
 * logged. Desktop-made keys for the import rows are read from
 * {@code /sdcard/Download/gitsshspike/} (pushed with adb) and are optional: their rows report SKIP.
 */
public class GitSshSpikeActivity extends AppCompatActivity {
    private static final String TAG = "GitSshSpike";

    private static final String SCP_URL = "git@github.com:AlexZ005/markor-gittab-testrepo.git";
    private static final String SSH_URL = "ssh://git@github.com/AlexZ005/markor-gittab-testrepo.git";
    private static final String BAD_HOST_URL = "git@spike-no-such-host.invalid:AlexZ005/markor-gittab-testrepo.git";
    private static final String REMOTE_BRANCH = "main";
    private static final String IMPORT_DIR = "/sdcard/Download/gitsshspike";
    private static final int NET_TIMEOUT_SEC = 90;

    /** Only ever used on keys this screen made itself, and never printed. */
    private static final byte[] SPIKE_PASSPHRASE = "spike-passphrase".getBytes(StandardCharsets.UTF_8);
    /** Matches the -N argument used for the pushed desktop fixtures. */
    private static final byte[] IMPORT_PASSPHRASE = "spike-passphrase".getBytes(StandardCharsets.UTF_8);

    /** GitHub's published host keys, to check what trust-on-first-use actually stored. */
    private static final Map<String, String> GITHUB_FINGERPRINTS = new LinkedHashMap<>();

    static {
        GITHUB_FINGERPRINTS.put("ssh-ed25519", "SHA256:+DiY3wvvV6TuJJhbpZisF/zLDA0zPMSvHdkr4UvCOqU");
        GITHUB_FINGERPRINTS.put("ecdsa-sha2-nistp256", "SHA256:p2QAMXNIC1TJYWeIOttrVc98/R1BUFWu3/LiyKgUfQM");
        GITHUB_FINGERPRINTS.put("ssh-rsa", "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s");
        GITHUB_FINGERPRINTS.put("rsa-sha2-256", "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s");
        GITHUB_FINGERPRINTS.put("rsa-sha2-512", "SHA256:uNiVztksCsDhcc0u9e8BujQXVUpKZIDTMczCvj3tD2s");
    }

    private final Handler _main = new Handler(Looper.getMainLooper());
    private final StringBuilder _screen = new StringBuilder();
    private final List<String> _verdicts = new ArrayList<>();
    private TextView _text;

    private JSch _jsch;
    private File _base;
    private File _knownHosts;
    private PersonIdent _ident;

    /** The identity registered as a deploy key; generated on the first run and kept afterwards. */
    private byte[] _deployPrivate;
    private byte[] _deployPublic;
    /** A second, never-registered identity, for the "wrong key" row. */
    private byte[] _strangerPrivate;
    private byte[] _strangerPublic;

    private int _pass, _fail, _skip, _nosup;

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final ScrollView scroll = new ScrollView(this);
        _text = new TextView(this);
        _text.setTypeface(android.graphics.Typeface.MONOSPACE);
        _text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        _text.setTextIsSelectable(true);
        final int pad = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 8, getResources().getDisplayMetrics());
        _text.setPadding(pad, pad, pad, pad);
        scroll.addView(_text);
        setContentView(scroll);
        setTitle("Git SSH spike");

        final Thread worker = new Thread(this::runAll, "GitSshSpike");
        worker.setDaemon(true);
        worker.start();
    }

    // ---------------------------------------------------------------- driver

    private void runAll() {
        _jsch = new JSch();
        _ident = new PersonIdent("Markor GitSshSpike", "gitsshspike@example.invalid");
        _base = new File(getFilesDir(), "gitsshspike");
        _knownHosts = new File(new File(getFilesDir(), "git"), "known_hosts");

        line(String.format(Locale.ROOT, "GitSshSpike  sdk=%d  buildType=%s  device=%s %s",
                Build.VERSION.SDK_INT, BuildConfig.BUILD_TYPE, Build.MANUFACTURER, Build.MODEL));
        line("user.home=" + System.getProperty("user.home") + "  HOME=" + System.getenv("HOME"));

        stackRows();
        keyRows();
        formatRows();
        importRows();
        hostKeyRows();
        operationRows();

        line("");
        line(String.format(Locale.ROOT, "== SUMMARY sdk=%d build=%s: %d pass, %d fail, %d nosup, %d skip",
                Build.VERSION.SDK_INT, BuildConfig.BUILD_TYPE, _pass, _fail, _nosup, _skip));
        for (final String v : _verdicts) {
            line("VERDICT " + v);
        }
        line("== DONE");
    }

    // ---------------------------------------------------------------- 1: the stack

    private void stackRows() {
        section("1. stack");
        row("jsch", () -> {
            check(JSch.VERSION != null && !JSch.VERSION.startsWith("0.1."), "JSch " + JSch.VERSION
                    + " is the abandoned com.jcraft build; the substitution did not take");
            return "JSch " + JSch.VERSION + "  rsa-sha2-256=" + implOf("rsa-sha2-256")
                    + "  rsa-sha2-512=" + implOf("rsa-sha2-512") + "  ssh-ed25519=" + implOf("ssh-ed25519")
                    + "  xdh=" + implOf("xdh") + "  keypairgen.eddsa=" + implOf("keypairgen.eddsa");
        });
        row("jsch-bc", () -> {
            // JSch's own com.jcraft.jsch.bc.* adapters are always in the dex; what decides ed25519 and
            // curve25519 support is whether the Bouncy Castle classes they reference are there too.
            final String signer = classState("org.bouncycastle.crypto.signers.Ed25519Signer");
            final String params = classState("org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters");
            final String agree = classState("org.bouncycastle.crypto.agreement.X25519Agreement");
            final boolean bc = signer.startsWith("ok");
            return (bc ? "Bouncy Castle on the classpath" : "no Bouncy Castle")
                    + "  Ed25519Signer=" + signer + "  Ed25519PrivateKeyParameters=" + params
                    + "  X25519Agreement=" + agree
                    + "  -> ed25519 keys and curve25519 kex are " + (bc ? "available" : "unavailable");
        });
        row("jgit-ssh", () -> {
            final URIish scp = new URIish(SCP_URL);
            final URIish ssh = new URIish(SSH_URL);
            check("git".equals(scp.getUser()) && "github.com".equals(scp.getHost()), "scp form parsed as " + scp);
            check("git".equals(ssh.getUser()) && "github.com".equals(ssh.getHost()), "ssh:// form parsed as " + ssh);
            final Transport t = Transport.open(scp);
            final String kind = t.getClass().getName();
            t.close();
            check(t instanceof SshTransport, "not an SshTransport: " + kind);
            return "scp=" + scp + " ssh=" + ssh + " transport=" + kind;
        });
    }

    // ---------------------------------------------------------------- 2: key generation

    private void keyRows() {
        section("2. key generation (in the app, no external tools)");
        row("gen-rsa4096", () -> {
            final File prv = new File(_base, "keys/deploy_rsa");
            final File pub = new File(_base, "keys/deploy_rsa.pub");
            final String reused;
            if (prv.isFile() && pub.isFile()) {
                _deployPrivate = readAll(prv);
                _deployPublic = readAll(pub);
                reused = "reused the key from an earlier run";
            } else {
                final KeyPair kp = KeyPair.genKeyPair(_jsch, KeyPair.RSA, 4096);
                _deployPrivate = writeOpenSshPrivate(kp, null);
                _deployPublic = publicLine(kp, "markor-spike-api" + Build.VERSION.SDK_INT);
                write(prv, _deployPrivate);
                write(pub, _deployPublic);
                kp.dispose();
                reused = "generated";
            }
            // The public half is not a secret; put it where adb pull can reach it.
            final File exported = new File(getExternalFilesDir(null), "gitsshspike/deploy_rsa.pub");
            write(exported, _deployPublic);
            final KeyPair loaded = KeyPair.load(_jsch, _deployPrivate, _deployPublic);
            final String fp = loaded.getFingerPrint();
            final int bits = loaded.getKeySize();
            loaded.dispose();
            check(bits == 4096, "expected 4096 bits, got " + bits);
            check(new String(_deployPublic, StandardCharsets.UTF_8).startsWith("ssh-rsa "), "public line is not ssh-rsa");
            return reused + ", " + bits + " bits, " + fp + ", pub at " + exported + "\n      " + new String(_deployPublic, StandardCharsets.UTF_8).trim();
        });
        row("gen-rsa-jce", () -> {
            // The other route the key store could take: java.security, then hand the PKCS#8 bytes to JSch.
            final KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(4096);
            final java.security.KeyPair jce = gen.generateKeyPair();
            final byte[] pkcs8 = pemWrap("PRIVATE KEY", jce.getPrivate().getEncoded());
            final KeyPair kp = KeyPair.load(_jsch, pkcs8, null);
            check(kp.getKeyType() == KeyPair.RSA, "loaded key type " + kp.getKeyTypeString());
            check(kp.getKeySize() == 4096, "loaded " + kp.getKeySize() + " bits");
            _strangerPrivate = writeOpenSshPrivate(kp, null);
            _strangerPublic = publicLine(kp, "markor-spike-stranger");
            final String fp = kp.getFingerPrint();
            kp.dispose();
            return "java.security RSA 4096 -> PKCS#8 -> JSch KeyPair, " + fp
                    + " (kept as the never-registered 'wrong key')";
        });
        capability("gen-ed25519", () -> {
            final KeyPair kp = KeyPair.genKeyPair(_jsch, KeyPair.ED25519);
            final byte[] pubLine = publicLine(kp, "markor-spike-ed25519");
            final String fp = kp.getFingerPrint();
            kp.dispose();
            return "generated, " + fp + ", " + new String(pubLine, StandardCharsets.UTF_8).trim();
        });
    }

    // ---------------------------------------------------------------- 3: formats written by the app

    private void formatRows() {
        section("3. key formats written by the app and read back from bytes");
        final KeyPair[] src = new KeyPair[1];
        row("fmt-source", () -> {
            src[0] = KeyPair.load(_jsch, _deployPrivate, _deployPublic);
            return src[0].getKeyTypeString() + " " + src[0].getKeySize() + " bits " + src[0].getFingerPrint();
        });
        if (src[0] == null) {
            return;
        }
        final byte[] blob = src[0].getPublicKeyBlob();

        row("fmt-pkcs1", () -> roundTrip(writePemPrivate(src[0], null), null, blob, "-----BEGIN RSA PRIVATE KEY-----", false));
        row("fmt-pkcs1-pw", () -> roundTrip(writePemPrivate(src[0], SPIKE_PASSPHRASE), SPIKE_PASSPHRASE, blob, "-----BEGIN RSA PRIVATE KEY-----", true));
        row("fmt-pkcs1-pw-from-pem", () -> {
            // Why the row above fails: KeyPair.writePrivateKey encrypts with the KDF of the vendor the
            // key was *loaded* from (here OpenSSH v1 -> bcrypt), but the legacy PEM it writes says
            // "DEK-Info: DES-EDE3-CBC" and is read back with OpenSSL's MD5 KDF. Re-load the same key
            // from its unencrypted PEM first, so the vendor is VENDOR_OPENSSH, and it round-trips.
            final KeyPair fromPem = KeyPair.load(_jsch, writePemPrivate(src[0], null), null);
            try {
                return roundTrip(writePemPrivate(fromPem, SPIKE_PASSPHRASE), SPIKE_PASSPHRASE, blob,
                        "-----BEGIN RSA PRIVATE KEY-----", true);
            } finally {
                fromPem.dispose();
            }
        });
        row("fmt-openssh", () -> roundTrip(writeOpenSshPrivate(src[0], null), null, blob, "-----BEGIN OPENSSH PRIVATE KEY-----", false));
        row("fmt-openssh-pw", () -> roundTrip(writeOpenSshPrivate(src[0], SPIKE_PASSPHRASE), SPIKE_PASSPHRASE, blob, "-----BEGIN OPENSSH PRIVATE KEY-----", true));
        row("fmt-publine", () -> {
            final byte[] line = publicLine(src[0], "comment-here");
            final String s = new String(line, StandardCharsets.UTF_8).trim();
            final String[] parts = s.split(" ");
            check(parts.length == 3, "expected 'type base64 comment', got " + parts.length + " fields");
            check("ssh-rsa".equals(parts[0]), "type=" + parts[0]);
            check("comment-here".equals(parts[2]), "comment=" + parts[2]);
            final byte[] decoded = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT);
            check(java.util.Arrays.equals(decoded, blob), "base64 body is not the public key blob");
            final HostKey hk = new HostKey("spike", decoded);
            check(src[0].getFingerPrint().equals(hk.getFingerPrint(_jsch)), "fingerprint mismatch: "
                    + src[0].getFingerPrint() + " vs " + hk.getFingerPrint(_jsch));
            return s.substring(0, 24) + "… " + src[0].getFingerPrint() + " (the spelling GitHub shows)";
        });
    }

    /** Writes the key, reads it back from bytes and checks that the public half survived. */
    private String roundTrip(final byte[] bytes, final byte[] passphrase, final byte[] expectedBlob,
                             final String expectedHeader, final boolean expectEncrypted) throws Exception {
        final String head = new String(bytes, 0, Math.min(64, bytes.length), StandardCharsets.UTF_8).split("\n")[0];
        check(head.startsWith(expectedHeader), "header is '" + head + "', expected '" + expectedHeader + "'");
        return bytes.length + " bytes, " + unlock(bytes, null, passphrase, expectedBlob, expectEncrypted);
    }

    /**
     * Loads the key, and when it is encrypted checks both passphrases -- each on its own freshly loaded
     * KeyPair, because a failed attempt does not always leave the object usable (see "retry" below).
     *
     * @return a description for the row, including whether a second attempt on the same object works
     */
    private String unlock(final byte[] prv, final byte[] pub, final byte[] passphrase,
                          final byte[] expectedBlob, final boolean expectEncrypted) throws Exception {
        final byte[] wrong = "definitely-not-the-passphrase".getBytes(StandardCharsets.UTF_8);
        String retry = "";
        KeyPair kp = KeyPair.load(_jsch, prv, pub);
        try {
            check(kp.isEncrypted() == expectEncrypted, "isEncrypted=" + kp.isEncrypted()
                    + ", expected " + expectEncrypted);
            if (expectEncrypted) {
                check(!kp.decrypt(wrong), "a wrong passphrase was accepted");
                retry = ", retry on the same KeyPair after a wrong passphrase: "
                        + (kp.decrypt(passphrase) ? "works" : "FAILS, reload the bytes");
                kp.dispose();
                kp = KeyPair.load(_jsch, prv, pub);
                check(kp.decrypt(passphrase), "the right passphrase was refused on a freshly loaded key");
            }
            if (expectedBlob != null) {
                check(java.util.Arrays.equals(kp.getPublicKeyBlob(), expectedBlob), "public key blob changed");
            }
            return "encrypted=" + expectEncrypted + ", " + kp.getKeyTypeString() + " " + kp.getKeySize()
                    + " bits, " + kp.getFingerPrint() + retry;
        } finally {
            kp.dispose();
        }
    }

    // ---------------------------------------------------------------- 4: keys made on a desktop

    private void importRows() {
        section("4. keys made on a desktop with ssh-keygen (" + IMPORT_DIR + ")");
        final String[][] fixtures = {
                // name, header that ssh-keygen wrote, passphrase?
                {"import_rsa_openssh", "-----BEGIN OPENSSH PRIVATE KEY-----", "no"},
                {"import_rsa_openssh_pw", "-----BEGIN OPENSSH PRIVATE KEY-----", "yes"},
                {"import_rsa_pem", "-----BEGIN RSA PRIVATE KEY-----", "no"},
                {"import_rsa_pem_pw", "-----BEGIN RSA PRIVATE KEY-----", "yes"},
                {"import_rsa_pkcs8", "-----BEGIN PRIVATE KEY-----", "no"},
                {"import_rsa_pkcs8_pw", "-----BEGIN ENCRYPTED PRIVATE KEY-----", "yes"},
                {"import_ecdsa", "-----BEGIN OPENSSH PRIVATE KEY-----", "no"},
                {"import_ed25519", "-----BEGIN OPENSSH PRIVATE KEY-----", "no"},
                {"import_ed25519_pw", "-----BEGIN OPENSSH PRIVATE KEY-----", "yes"},
        };
        for (final String[] f : fixtures) {
            final File prv = new File(IMPORT_DIR, f[0]);
            final File pub = new File(IMPORT_DIR, f[0] + ".pub");
            if (!prv.isFile()) {
                skip("imp-" + f[0].replace("import_", ""), "not pushed to " + IMPORT_DIR);
                continue;
            }
            row("imp-" + f[0].replace("import_", ""), () -> {
                final byte[] prvBytes = readAll(prv);
                final byte[] pubBytes = pub.isFile() ? readAll(pub) : null;
                final String head = new String(prvBytes, 0, Math.min(64, prvBytes.length), StandardCharsets.UTF_8).split("\n")[0];
                check(head.startsWith(f[1]), "header is '" + head + "'");
                byte[] blob = null;
                if (pubBytes != null) {
                    final String[] parts = new String(pubBytes, StandardCharsets.UTF_8).trim().split(" ");
                    blob = android.util.Base64.decode(parts[1], android.util.Base64.DEFAULT);
                }
                final String described = unlock(prvBytes, pubBytes, IMPORT_PASSPHRASE, blob, "yes".equals(f[2]));
                return described + (blob == null ? "" : ", matches ssh-keygen's fingerprint "
                        + new HostKey("x", blob).getFingerPrint(_jsch));
            });
        }
        // Parsing an ed25519 key is not the same as being able to sign with it.
        final File edPrv = new File(IMPORT_DIR, "import_ed25519");
        if (!edPrv.isFile()) {
            skip("imp-ed25519-sign", "not pushed to " + IMPORT_DIR);
        } else {
            capability("imp-ed25519-sign", () -> {
                final KeyPair kp = KeyPair.load(_jsch, readAll(edPrv), null);
                try {
                    final byte[] sig = kp.getSignature("spike".getBytes(StandardCharsets.UTF_8), "ssh-ed25519");
                    check(sig != null && sig.length > 0, "no signature produced");
                    return "signed " + sig.length + " bytes with ssh-ed25519";
                } finally {
                    kp.dispose();
                }
            });
        }
    }

    // ---------------------------------------------------------------- 5: host keys

    private void hostKeyRows() {
        section("5. known_hosts, trust on first use (" + _knownHosts + ")");
        final String[] seen = new String[2];
        final AtomicInteger prompts = new AtomicInteger();

        row("hk-tofu", () -> {
            deleteRecursively(_knownHosts);
            prompts.set(0);
            final GitSshSessionFactory factory = factory(_deployPrivate, _deployPublic, (host, type, fp) -> {
                prompts.incrementAndGet();
                seen[0] = type;
                seen[1] = fp;
                return true;
            });
            lsRemote(SCP_URL, factory);
            check(prompts.get() == 1, "prompted " + prompts.get() + " times, expected once");
            check(_knownHosts.isFile() && _knownHosts.length() > 0, "known_hosts was not written");
            final String stored = new String(readAll(_knownHosts), StandardCharsets.UTF_8).trim();
            check(stored.startsWith("github.com "), "known_hosts line: " + firstWords(stored, 2));
            final String published = GITHUB_FINGERPRINTS.get(seen[0]);
            check(published != null, "unexpected host key type " + seen[0]);
            check(published.equals(seen[1]), "fingerprint " + seen[1] + " is not GitHub's published " + published);
            return "asked once, accepted " + seen[0] + " " + seen[1]
                    + " (GitHub's published value), " + _knownHosts.length() + " bytes stored";
        });
        row("hk-reuse", () -> {
            prompts.set(0);
            final GitSshSessionFactory factory = factory(_deployPrivate, _deployPublic, (host, type, fp) -> {
                prompts.incrementAndGet();
                return true;
            });
            lsRemote(SCP_URL, factory);
            check(prompts.get() == 0, "asked again on the second connection");
            return "second connection used the stored key, no prompt";
        });
        row("hk-mismatch", () -> {
            // Corrupt the key that trust-on-first-use just stored, keeping its type: JSch compares
            // per (host, key type), so a different type would only read as "not seen yet".
            final String stored = new String(readAll(_knownHosts), StandardCharsets.UTF_8).trim();
            final String[] parts = stored.split("\\s+");
            check(parts.length >= 3, "cannot read the stored line: " + firstWords(stored, 2));
            final byte[] blob = android.util.Base64.decode(parts[2], android.util.Base64.DEFAULT);
            blob[blob.length - 1] ^= 0x5a;
            final String tampered = parts[0] + " " + parts[1] + " "
                    + android.util.Base64.encodeToString(blob, android.util.Base64.NO_WRAP) + "\n";
            write(_knownHosts, tampered.getBytes(StandardCharsets.UTF_8));
            final AtomicInteger asked = new AtomicInteger();
            final GitSshSessionFactory factory = factory(_deployPrivate, _deployPublic, (host, type, fp) -> {
                asked.incrementAndGet();
                return true;
            });
            try {
                lsRemote(SCP_URL, factory);
                throw new AssertionError("the connection succeeded although the stored host key differs");
            } catch (final org.eclipse.jgit.api.errors.TransportException e) {
                final String cause = rootName(e);
                check(cause.contains("ChangedHostKey"), "unexpected cause " + cause);
                check(GitSshSessionFactory.isHostKeyMismatch(e), "isHostKeyMismatch() said no");
                check(!GitSshSessionFactory.isUnknownHostKey(e), "isUnknownHostKey() said yes");
                check(asked.get() == 0, "the user was asked to overwrite a mismatching host key");
                final String after = new String(readAll(_knownHosts), StandardCharsets.UTF_8);
                check(after.trim().equals(tampered.trim()), "known_hosts was rewritten");
                return classified(e) + " / " + cause + ": " + oneLine(e.getMessage())
                        + " -- never offered to the user, known_hosts untouched";
            }
        });
        row("hk-decline", () -> {
            deleteRecursively(_knownHosts);
            final GitSshSessionFactory factory = factory(_deployPrivate, _deployPublic, (host, type, fp) -> false);
            try {
                lsRemote(SCP_URL, factory);
                throw new AssertionError("the connection succeeded although the host key was declined");
            } catch (final org.eclipse.jgit.api.errors.TransportException e) {
                final String cause = rootName(e);
                check(cause.contains("UnknownHostKey"), "unexpected cause " + cause);
                check(GitSshSessionFactory.isUnknownHostKey(e), "isUnknownHostKey() said no");
                check(!GitSshSessionFactory.isHostKeyMismatch(e), "isHostKeyMismatch() said yes");
                check(_knownHosts.length() == 0, "known_hosts was written although the key was declined");
                return classified(e) + " / " + cause + ": " + oneLine(e.getMessage());
            }
        });
        row("hk-restore", () -> {
            // Leave a usable known_hosts behind for section 6.
            deleteRecursively(_knownHosts);
            final AtomicInteger asked = new AtomicInteger();
            lsRemote(SCP_URL, factory(_deployPrivate, _deployPublic, (host, type, fp) -> {
                asked.incrementAndGet();
                return true;
            }));
            check(asked.get() == 1, "asked " + asked.get() + " times");
            return "trusted again for section 6, " + _knownHosts.length() + " bytes";
        });
    }

    // ---------------------------------------------------------------- 6: operations

    private void operationRows() {
        section("6. operations over SSH");
        deleteRecursively(new File(_base, "work"));
        final File cloneA = new File(_base, "work/cloneA");
        final File cloneB = new File(_base, "work/cloneB");
        final Git[] a = new Git[1];
        final Git[] b = new Git[1];
        final ObjectId[] pushed = new ObjectId[1];
        final String[] pushedFile = new String[1];

        row("ls-remote-scp", () -> {
            final Collection<Ref> refs = lsRemote(SCP_URL, factory(_deployPrivate, _deployPublic, acceptAll()));
            check(!refs.isEmpty(), "no refs advertised");
            return refs.size() + " refs, " + SCP_URL;
        });
        row("ls-remote-ssh", () -> {
            final Collection<Ref> refs = lsRemote(SSH_URL, factory(_deployPrivate, _deployPublic, acceptAll()));
            check(!refs.isEmpty(), "no refs advertised");
            return refs.size() + " refs, " + SSH_URL;
        });
        row("clone", () -> {
            a[0] = Git.cloneRepository().setURI(SSH_URL).setDirectory(cloneA).setBranch(REMOTE_BRANCH)
                    .setTransportConfigCallback(callback(factory(_deployPrivate, _deployPublic, acceptAll())))
                    .setTimeout(NET_TIMEOUT_SEC).call();
            check(new File(cloneA, "README.md").isFile(), "README.md missing after clone");
            b[0] = Git.cloneRepository().setURI(SCP_URL).setDirectory(cloneB).setBranch(REMOTE_BRANCH)
                    .setTransportConfigCallback(callback(factory(_deployPrivate, _deployPublic, acceptAll())))
                    .setTimeout(NET_TIMEOUT_SEC).call();
            check(new File(cloneB, "README.md").isFile(), "README.md missing after the second clone");
            return "ssh:// and scp-like both cloned, HEAD="
                    + a[0].getRepository().exactRef("HEAD").getObjectId().abbreviate(7).name();
        });
        final boolean pushOk = row("push", () -> {
            check(a[0] != null, "no clone to push from");
            final String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
            pushedFile[0] = "devices/ssh-sdk" + Build.VERSION.SDK_INT + "-" + BuildConfig.BUILD_TYPE + "-" + stamp + ".txt";
            write(new File(cloneA, pushedFile[0]), ("ssh spike sdk=" + Build.VERSION.SDK_INT + " build="
                    + BuildConfig.BUILD_TYPE + " time=" + stamp + "\n").getBytes(StandardCharsets.UTF_8));
            a[0].add().addFilepattern("devices").call();
            pushed[0] = a[0].commit().setMessage("GitSshSpike sdk" + Build.VERSION.SDK_INT + " " + BuildConfig.BUILD_TYPE + " " + stamp)
                    .setAuthor(_ident).setCommitter(_ident).setSign(false).call().getId();
            final Iterable<PushResult> results = a[0].push().setRemote("origin")
                    .setRefSpecs(new RefSpec("refs/heads/" + REMOTE_BRANCH + ":refs/heads/" + REMOTE_BRANCH))
                    .setTransportConfigCallback(callback(factory(_deployPrivate, _deployPublic, acceptAll())))
                    .setTimeout(NET_TIMEOUT_SEC).call();
            final StringBuilder sb = new StringBuilder();
            for (final PushResult pr : results) {
                for (final RemoteRefUpdate u : pr.getRemoteUpdates()) {
                    sb.append(u.getRemoteName()).append('=').append(u.getStatus()).append(' ');
                    check(u.getStatus() == RemoteRefUpdate.Status.OK, "push status " + u.getStatus() + " " + u.getMessage());
                }
            }
            return sb + pushed[0].abbreviate(7).name() + " (deploy key has write access)";
        });
        row("fetch", () -> {
            check(b[0] != null, "no clone to fetch into");
            final FetchResult fr = b[0].fetch().setRemote("origin")
                    .setTransportConfigCallback(callback(factory(_deployPrivate, _deployPublic, acceptAll())))
                    .setTimeout(NET_TIMEOUT_SEC).call();
            final org.eclipse.jgit.transport.TrackingRefUpdate tru =
                    fr.getTrackingRefUpdate("refs/remotes/origin/" + REMOTE_BRANCH);
            if (pushOk) {
                check(tru != null, "no tracking ref update for origin/" + REMOTE_BRANCH);
                check(pushed[0].equals(tru.getNewObjectId()), "fetched " + tru.getNewObjectId().abbreviate(7).name()
                        + " but pushed " + pushed[0].abbreviate(7).name());
            }
            return "advertised=" + fr.getAdvertisedRefs().size() + " updates=" + fr.getTrackingRefUpdates().size()
                    + (tru == null ? "" : " origin/" + REMOTE_BRANCH + "=" + tru.getResult());
        });
        row("pull", () -> {
            check(b[0] != null, "no clone to pull into");
            final org.eclipse.jgit.api.PullResult pr = b[0].pull().setRemote("origin").setRemoteBranchName(REMOTE_BRANCH)
                    .setTransportConfigCallback(callback(factory(_deployPrivate, _deployPublic, acceptAll())))
                    .setTimeout(NET_TIMEOUT_SEC).call();
            check(pr.isSuccessful(), "pull not successful: " + pr);
            if (pushOk) {
                check(new File(cloneB, pushedFile[0]).isFile(), "the pushed file is not in the work tree after pull");
            }
            final RevCommit head = b[0].log().setMaxCount(1).call().iterator().next();
            return "merge=" + (pr.getMergeResult() == null ? null : pr.getMergeResult().getMergeStatus())
                    + " HEAD=" + head.abbreviate(7).name();
        });
        row("wrong-key", () -> {
            check(_strangerPrivate != null, "no second key was generated");
            try {
                lsRemote(SCP_URL, factory(_strangerPrivate, _strangerPublic, acceptAll()));
                throw new AssertionError("a key GitHub does not know was accepted");
            } catch (final org.eclipse.jgit.api.errors.TransportException e) {
                final GitResult<Void> mapped = GitSshSpikeErrorBridge.classify(e);
                check(mapped.getKind() == GitResult.Kind.AUTH_FAILED, "JGitErrors.map said " + mapped.getKind()
                        + " for: " + oneLine(e.getMessage()));
                return "AUTH_FAILED: " + oneLine(e.getMessage());
            }
        });
        row("unknown-host", () -> {
            try {
                lsRemote(BAD_HOST_URL, factory(_deployPrivate, _deployPublic, acceptAll()));
                throw new AssertionError("a host that does not exist answered");
            } catch (final org.eclipse.jgit.api.errors.TransportException e) {
                final GitResult<Void> mapped = GitSshSpikeErrorBridge.classify(e);
                check(mapped.getKind() == GitResult.Kind.NETWORK, "JGitErrors.map said " + mapped.getKind()
                        + " for: " + oneLine(e.getMessage()) + " / root " + rootName(e));
                return "NETWORK: " + oneLine(e.getMessage());
            }
        });

        for (final Git g : new Git[]{a[0], b[0]}) {
            if (g != null) {
                try {
                    g.close();
                } catch (final Throwable ignored) {
                }
            }
        }
    }

    // ---------------------------------------------------------------- ssh plumbing

    private GitSshSessionFactory.HostKeyPrompt acceptAll() {
        return (host, type, fingerprint) -> true;
    }

    private GitSshSessionFactory factory(final byte[] prv, final byte[] pub, final GitSshSessionFactory.HostKeyPrompt prompt) {
        // Copies, because a real caller hands in decrypted bytes it then wipes.
        return new GitSshSessionFactory(
                new GitSshSessionFactory.Identity("spike", prv == null ? null : prv.clone(),
                        pub == null ? null : pub.clone(), null),
                _knownHosts, prompt);
    }

    private static TransportConfigCallback callback(final GitSshSessionFactory factory) {
        return transport -> {
            if (transport instanceof SshTransport) {
                ((SshTransport) transport).setSshSessionFactory(factory);
            }
        };
    }

    private Collection<Ref> lsRemote(final String url, final GitSshSessionFactory factory) throws Exception {
        return Git.lsRemoteRepository().setRemote(url).setHeads(true)
                .setTransportConfigCallback(callback(factory)).setTimeout(NET_TIMEOUT_SEC).call();
    }

    // ---------------------------------------------------------------- key helpers

    private static byte[] writeOpenSshPrivate(final KeyPair kp, final byte[] passphrase) throws Exception {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        kp.writeOpenSSHv1PrivateKey(out, passphrase);
        return out.toByteArray();
    }

    private static byte[] writePemPrivate(final KeyPair kp, final byte[] passphrase) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        kp.writePrivateKey(out, passphrase);
        return out.toByteArray();
    }

    private static byte[] publicLine(final KeyPair kp, final String comment) {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        kp.writePublicKey(out, comment);
        return out.toByteArray();
    }

    private static byte[] pemWrap(final String label, final byte[] der) {
        final String body = android.util.Base64.encodeToString(der, android.util.Base64.NO_WRAP);
        final StringBuilder sb = new StringBuilder("-----BEGIN ").append(label).append("-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            sb.append(body, i, Math.min(body.length(), i + 64)).append('\n');
        }
        sb.append("-----END ").append(label).append("-----\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String implOf(final String key) {
        final String value = JSch.getConfig(key);
        return value == null ? "(unset)" : value + "=" + classState(value);
    }

    private static String classState(final String className) {
        try {
            Class.forName(className);
            return "ok";
        } catch (final Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    // ---------------------------------------------------------------- row plumbing

    private interface Op {
        String run() throws Throwable;
    }

    private void section(final String title) {
        line("");
        line("== " + title);
    }

    private boolean row(final String name, final Op op) {
        final long t0 = SystemClock.elapsedRealtime();
        try {
            final String detail = op.run();
            final long ms = SystemClock.elapsedRealtime() - t0;
            line(String.format(Locale.ROOT, "PASS %-18s %6d ms  %s", name, ms, detail));
            _verdicts.add(name + "=PASS");
            _pass++;
            return true;
        } catch (final Throwable t) {
            final long ms = SystemClock.elapsedRealtime() - t0;
            line(String.format(Locale.ROOT, "FAIL %-18s %6d ms  %s", name, ms, describe(t)));
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth++ < 6) {
                line("      caused by " + describe(cause));
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            final StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(6, st.length); i++) {
                line("        at " + st[i]);
            }
            Log.e(TAG, "FAIL " + name, t);
            _verdicts.add(name + "=FAIL");
            _fail++;
            return false;
        }
    }

    /**
     * Like {@link #row} but for something the stack may legitimately not support: a failure is
     * reported as NOSUP with the reason rather than counted as a defect.
     */
    private void capability(final String name, final Op op) {
        final long t0 = SystemClock.elapsedRealtime();
        try {
            final String detail = op.run();
            line(String.format(Locale.ROOT, "PASS %-18s %6d ms  %s", name, SystemClock.elapsedRealtime() - t0, detail));
            _verdicts.add(name + "=PASS");
            _pass++;
        } catch (final Throwable t) {
            line(String.format(Locale.ROOT, "NOSUP %-17s %6d ms  %s", name, SystemClock.elapsedRealtime() - t0, describe(t)));
            Throwable cause = t.getCause();
            int depth = 0;
            while (cause != null && depth++ < 4) {
                line("      caused by " + describe(cause));
                cause = cause.getCause() == cause ? null : cause.getCause();
            }
            _verdicts.add(name + "=NOSUP");
            _nosup++;
        }
    }

    private void skip(final String name, final String why) {
        line(String.format(Locale.ROOT, "SKIP %-18s           %s", name, why));
        _verdicts.add(name + "=SKIP");
        _skip++;
    }

    private static String classified(final Exception e) {
        return "JGitErrors.map -> " + GitSshSpikeErrorBridge.classify(e).getKind();
    }

    private static String describe(final Throwable t) {
        return t.getClass().getName() + ": " + oneLine(t.getMessage());
    }

    private static String rootName(final Throwable t) {
        final StringBuilder sb = new StringBuilder();
        for (Throwable c = t; c != null && sb.length() < 400; c = c.getCause() == c ? null : c.getCause()) {
            sb.append(c.getClass().getSimpleName()).append('<');
        }
        return sb.toString();
    }

    private static String oneLine(final String s) {
        return s == null ? "null" : s.replace('\n', ' ').replace('\r', ' ').trim();
    }

    private static String firstWords(final String s, final int n) {
        final String[] parts = s.split("\\s+");
        final StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(n, parts.length); i++) {
            sb.append(parts[i]).append(' ');
        }
        return sb.toString().trim();
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private void line(final String s) {
        Log.i(TAG, s);
        _main.post(() -> {
            _screen.append(s).append('\n');
            _text.setText(_screen);
        });
    }

    // ---------------------------------------------------------------- file helpers

    private static byte[] readAll(final File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            final ByteArrayOutputStream buf = new ByteArrayOutputStream();
            final byte[] tmp = new byte[4096];
            int n;
            while ((n = in.read(tmp)) > 0) {
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        }
    }

    private static void write(final File f, final byte[] content) throws IOException {
        final File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(content);
        }
    }

    private static void deleteRecursively(final File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (final File c : children) {
                deleteRecursively(c);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
