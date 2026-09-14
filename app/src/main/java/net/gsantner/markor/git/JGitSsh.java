/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git;

import net.gsantner.markor.git.ssh.GitSshSessionFactory;

import org.eclipse.jgit.api.TransportConfigCallback;
import org.eclipse.jgit.transport.SshTransport;

import java.io.Closeable;
import java.io.File;

/**
 * Attaches the SSH session factory to one remote operation, and takes the key away again when it
 * ends (roadmap task 8.1c, item 1 of "what 8.1c must implement" in
 * {@code doc/adr/0002-ssh-on-android.md}).
 * <p>
 * <b>Per operation, never globally.</b> JGit's {@code SshSessionFactory.setInstance} is
 * process-wide, which would mean one repository's key serving another repository's operation and two
 * concurrent operations racing over which key is installed. The ADR rejected it for exactly that;
 * this class is the alternative it names: {@code TransportCommand.setTransportConfigCallback} plus
 * {@code SshTransport.setSshSessionFactory}, with a fresh factory and a freshly decrypted key each
 * time. {@code PullCommand} in JGit 5.13 has the callback too, which the Phase 1 spike verified.
 * <p>
 * <b>The key only ever reaches SSH.</b> {@link #forOperation} resolves nothing at all unless
 * {@link GitRemoteUrlPolicy} classified the URL as {@link GitRemoteUrlPolicy.Transport#SSH}, so an
 * https or {@code file://} operation neither decrypts a key nor asks the user for a passphrase; and
 * the callback narrows it once more by only configuring an {@link SshTransport}, which JGit opens for
 * no other scheme. The token is kept to https by the mirror image of this,
 * {@link JGitCredentials#isTlsUri}.
 * <p>
 * {@link Closeable} so an operation can wrap it in try-with-resources: closing wipes the decrypted
 * private key and the passphrase.
 */
final class JGitSsh implements Closeable {

    /** Nothing to do: the operation does not run over SSH. */
    private static final JGitSsh NOT_SSH = new JGitSsh(null, null);

    private final GitSshAuthSource.Resolution _resolution;
    private final TransportConfigCallback _callback;

    private JGitSsh(final GitSshAuthSource.Resolution resolution, final TransportConfigCallback callback) {
        _resolution = resolution;
        _callback = callback;
    }

    /**
     * @param credentials the operation's credential source; {@code null} means anonymous
     * @param transport   what {@link GitRemoteUrlPolicy} said about the URL; anything but
     *                    {@link GitRemoteUrlPolicy.Transport#SSH} produces a no-op
     * @param repoDir     the repository, or {@code null} for a clone or an ls-remote
     * @param url         the URL the operation is about to connect to
     * @return never {@code null}; check {@link #getRefusal()} before using {@link #getCallback()}
     */
    static JGitSsh forOperation(final GitCredentialsSource credentials, final GitRemoteUrlPolicy.Transport transport,
                                final File repoDir, final String url) {
        if (transport != GitRemoteUrlPolicy.Transport.SSH) {
            return NOT_SSH;
        }
        final GitSshAuthSource source = credentials == null || credentials.ssh() == null
                ? GitSshAuthSource.NONE : credentials.ssh();
        final GitSshAuthSource.Resolution resolution = source.resolve(repoDir, url);
        if (resolution == null) {
            return new JGitSsh(GitSshAuthSource.Resolution.refused("No SSH key is available for this repository"), null);
        }
        if (!resolution.isOk()) {
            return new JGitSsh(resolution, null);
        }
        final GitSshSessionFactory factory = new GitSshSessionFactory(
                resolution.getIdentity(), resolution.getKnownHosts(), source);
        return new JGitSsh(resolution, t -> {
            if (t instanceof SshTransport) {
                ((SshTransport) t).setSshSessionFactory(factory);
            }
        });
    }

    /** @return {@code null} when the operation may proceed, otherwise the sentence refusing it */
    String getRefusal() {
        return _resolution == null ? null : _resolution.getRefusal();
    }

    /**
     * @return what to hand to {@code TransportCommand.setTransportConfigCallback}, or {@code null}
     * for a non-SSH operation — which the command accepts and ignores
     */
    TransportConfigCallback getCallback() {
        return _callback;
    }

    /** Wipes the decrypted private key and the passphrase. */
    @Override
    public void close() {
        if (_resolution != null) {
            _resolution.close();
        }
    }
}
