/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import org.eclipse.jgit.transport.JschConfigSessionFactory;
import org.eclipse.jgit.transport.OpenSshConfig;
import org.eclipse.jgit.util.FS;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import com.jcraft.jsch.HostKey;
import com.jcraft.jsch.HostKeyRepository;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UserInfo;

/**
 * JGit {@link org.eclipse.jgit.transport.SshSessionFactory} for the Git tab, on JSch
 * (doc/adr/0002-ssh-on-android.md). One instance serves <b>one</b> operation: it is handed the
 * identity the key store selected for the repository, as bytes plus an optional passphrase, and the
 * path of the app-private {@code known_hosts}. Nothing is read from {@code ~/.ssh}, which does not
 * exist on Android anyway.
 * <p>
 * Attach it per operation rather than through {@code SshSessionFactory.setInstance}:
 * <pre>
 * command.setTransportConfigCallback(transport -&gt; {
 *     if (transport instanceof SshTransport) {
 *         ((SshTransport) transport).setSshSessionFactory(factory);
 *     }
 * });
 * </pre>
 * <p>
 * Host keys are trust-on-first-use. An unknown host is offered to {@link HostKeyPrompt} with its
 * SHA256 fingerprint and is written to {@code known_hosts} only if that returns {@code true};
 * declining fails the operation with {@code JSchUnknownHostKeyException}. A host key that does not
 * match the stored one is <b>never</b> offered and always fails with
 * {@code JSchChangedHostKeyException}, which the UI can name separately from an ordinary auth
 * failure.
 */
public class GitSshSessionFactory extends JschConfigSessionFactory {

    /** The private key the key store selected, as bytes; a later lane decrypts it just before this. */
    public static final class Identity {
        private final String _name;
        private final byte[] _privateKey;
        private final byte[] _publicKey;
        private final byte[] _passphrase;

        /**
         * @param name       a label for JSch's error messages; never a secret
         * @param privateKey OpenSSH, PKCS#1 or PKCS#8 bytes, encrypted or not
         * @param publicKey  the matching {@code ssh-… AAAA…} line, or {@code null}
         * @param passphrase UTF-8 bytes of the passphrase, or {@code null} for an unencrypted key
         */
        public Identity(final String name, final byte[] privateKey, final byte[] publicKey, final byte[] passphrase) {
            _name = name == null ? "markor" : name;
            _privateKey = privateKey;
            _publicKey = publicKey;
            _passphrase = passphrase;
        }

        /** Overwrites the key and passphrase bytes; call it in the operation's {@code finally}. */
        public void wipe() {
            fill(_privateKey);
            fill(_passphrase);
        }

        private static void fill(final byte[] b) {
            if (b != null) {
                java.util.Arrays.fill(b, (byte) 0);
            }
        }
    }

    /** Asked once per host that {@code known_hosts} does not know yet; runs on the git worker thread. */
    public interface HostKeyPrompt {
        /**
         * @param host        the host as it was typed in the remote URL
         * @param keyType     {@code ssh-ed25519}, {@code ecdsa-sha2-nistp256}, {@code ssh-rsa}, …
         * @param fingerprint {@code SHA256:…}, the spelling GitHub and OpenSSH show
         * @return {@code true} to store the key and continue
         */
        boolean acceptNewHostKey(String host, String keyType, String fingerprint);
    }

    private final Identity _identity;
    private final File _knownHosts;
    private final HostKeyPrompt _prompt;

    public GitSshSessionFactory(final Identity identity, final File knownHosts, final HostKeyPrompt prompt) {
        _identity = identity;
        _knownHosts = knownHosts;
        _prompt = prompt;
    }

    /**
     * A fresh JSch per call, so no identity and no decrypted key is cached between operations. The
     * base class would otherwise keep one in {@code defaultJSch}.
     */
    @Override
    protected JSch getJSch(final OpenSshConfig.Host hc, final FS fs) throws JSchException {
        final JSch jsch = new JSch();
        jsch.setKnownHosts(ensureKnownHostsFile().getPath());
        jsch.setHostKeyRepository(new RecordingHostKeys(jsch, jsch.getHostKeyRepository()));
        if (_identity != null && _identity._privateKey != null) {
            jsch.addIdentity(_identity._name, _identity._privateKey, _identity._publicKey, _identity._passphrase);
        }
        return jsch;
    }

    @Override
    protected void configure(final OpenSshConfig.Host hc, final Session session) {
        // "ask" is what routes an unknown host through UserInfo.promptYesNo below; "yes" would refuse
        // it outright and "no" would accept anything, neither of which is trust-on-first-use.
        session.setConfig("StrictHostKeyChecking", "ask");
        // No password or keyboard-interactive fallback: there is a key or there is nothing.
        session.setConfig("PreferredAuthentications", "publickey");
        session.setUserInfo(new Prompts((RecordingHostKeys) session.getHostKeyRepository()));
    }

    private File ensureKnownHostsFile() throws JSchException {
        final File parent = _knownHosts.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new JSchException("Cannot create " + parent);
        }
        if (!_knownHosts.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                _knownHosts.createNewFile();
            } catch (final IOException e) {
                throw new JSchException("Cannot create " + _knownHosts, e);
            }
        }
        return _knownHosts;
    }

    /**
     * Delegates to JSch's {@code known_hosts} but remembers what the last {@link #check} said, so
     * that {@link Prompts} can tell "never seen this host" from "the key changed" without parsing
     * JSch's English prompt text.
     */
    private static final class RecordingHostKeys implements HostKeyRepository {
        private final JSch _jsch;
        private final HostKeyRepository _delegate;
        private int _lastResult = HostKeyRepository.OK;
        private String _lastHost;
        private String _lastType;
        private String _lastFingerprint;

        RecordingHostKeys(final JSch jsch, final HostKeyRepository delegate) {
            _jsch = jsch;
            _delegate = delegate;
        }

        @Override
        public int check(final String host, final byte[] key) {
            final int result = _delegate.check(host, key);
            _lastResult = result;
            _lastHost = host;
            _lastType = "?";
            _lastFingerprint = "?";
            try {
                final HostKey hk = new HostKey(host, key);
                _lastType = hk.getType();
                _lastFingerprint = hk.getFingerPrint(_jsch);
            } catch (final JSchException ignored) {
                // Leave the placeholders; the prompt still names the host.
            }
            return result;
        }

        @Override
        public void add(final HostKey hostkey, final UserInfo ui) {
            _delegate.add(hostkey, ui);
        }

        @Override
        public void remove(final String host, final String type) {
            _delegate.remove(host, type);
        }

        @Override
        public void remove(final String host, final String type, final byte[] key) {
            _delegate.remove(host, type, key);
        }

        @Override
        public String getKnownHostsRepositoryID() {
            return _delegate.getKnownHostsRepositoryID();
        }

        @Override
        public HostKey[] getHostKey() {
            return _delegate.getHostKey();
        }

        @Override
        public HostKey[] getHostKey(final String host, final String type) {
            return _delegate.getHostKey(host, type);
        }
    }

    /** The only two questions JSch may ask: the passphrase, and whether a new host key is trusted. */
    private final class Prompts implements UserInfo {
        private final RecordingHostKeys _hostKeys;

        Prompts(final RecordingHostKeys hostKeys) {
            _hostKeys = hostKeys;
        }

        @Override
        public boolean promptYesNo(final String message) {
            if (_hostKeys._lastResult != HostKeyRepository.NOT_INCLUDED) {
                // CHANGED: the stored key and the offered one differ. Never ask, never overwrite.
                return false;
            }
            return _prompt != null && _prompt.acceptNewHostKey(
                    _hostKeys._lastHost, _hostKeys._lastType, _hostKeys._lastFingerprint);
        }

        @Override
        public String getPassphrase() {
            return _identity == null || _identity._passphrase == null
                    ? null : new String(_identity._passphrase, java.nio.charset.StandardCharsets.UTF_8);
        }

        @Override
        public boolean promptPassphrase(final String message) {
            // The passphrase was handed in with the identity; there is no interactive retry.
            return _identity != null && _identity._passphrase != null;
        }

        @Override
        public String getPassword() {
            return null;
        }

        @Override
        public boolean promptPassword(final String message) {
            return false;
        }

        @Override
        public void showMessage(final String message) {
            // Nothing: the operation's result carries the outcome.
        }
    }

    /**
     * The host presented a key that is not the one in {@code known_hosts}. JGit wraps this in a
     * {@code TransportException}, where it is indistinguishable from any other transport failure, so
     * the UI has to ask here to be able to say "the server's identity changed" rather than "failed".
     *
     * @param t the exception a JGit command threw; its cause chain is walked
     */
    public static boolean isHostKeyMismatch(final Throwable t) {
        return hasCause(t, "com.jcraft.jsch.JSchChangedHostKeyException");
    }

    /** The host is not in {@code known_hosts} and {@link HostKeyPrompt} said no (or there was none). */
    public static boolean isUnknownHostKey(final Throwable t) {
        return hasCause(t, "com.jcraft.jsch.JSchUnknownHostKeyException");
    }

    private static boolean hasCause(final Throwable t, final String className) {
        for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
            if (className.equals(c.getClass().getName())) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return the fingerprint spelling used in {@link HostKeyPrompt}, lower-cased type included,
     * for a log line that must not contain the key itself
     */
    public static String describe(final String host, final String keyType, final String fingerprint) {
        return String.format(Locale.ROOT, "%s (%s) %s", host, keyType, fingerprint);
    }
}
