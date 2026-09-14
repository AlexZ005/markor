/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package com.jcraft.jsch;

/**
 * Builds the two host-key exceptions JSch raises, for the tests that check how the Git tab maps them
 * (roadmap task 8.1c). Their constructors are package-private — only JSch's own {@code Session} is
 * meant to throw them — so this lives in JSch's package rather than mocking a class the production
 * code recognises by name.
 */
public final class JSchHostKeyExceptions {

    private JSchHostKeyExceptions() {
    }

    /** What a connection gets when {@code known_hosts} holds a different key for the host. */
    public static JSchException changed(final String message) {
        return new JSchChangedHostKeyException(message);
    }

    /** What it gets when the host is unknown and the fingerprint prompt was answered with no. */
    public static JSchException unknown(final String message) {
        return new JSchUnknownHostKeyException(message);
    }
}
