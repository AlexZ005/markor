/*#######################################################
 *
 *   Maintained 2026 by the Markor fork (AlexZ005/markor), Git tab
 *   License of this file: Apache 2.0
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
#########################################################*/
package net.gsantner.markor.git.ssh;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.KeyPair;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Private-key files for the import tests, made here rather than committed: a repository is the last
 * place a private key should live, even a throwaway one. Every fixture is generated fresh in the
 * test JVM.
 * <p>
 * The formats mirror the ones ADR 0002 section 4 exercised on the device with {@code ssh-keygen}:
 * OpenSSH v1 with and without a passphrase, legacy PKCS#1 PEM, and PKCS#8. The two
 * <i>encrypted</i> desktop formats (PEM with {@code DEK-Info}, {@code ENCRYPTED PRIVATE KEY}) cannot
 * be written without OpenSSL or Bouncy Castle, so they stay covered by the on-device matrix only.
 */
final class GitSshTestKeys {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    /** Small enough to keep the test suite fast; the format is what is under test, not the size. */
    static final int TEST_RSA_BITS = 2048;

    private GitSshTestKeys() {
    }

    /** @return an RSA key pair from JSch, the object the other helpers serialize */
    static KeyPair jschRsa() throws JSchException {
        return KeyPair.genKeyPair(new JSch(), KeyPair.RSA, TEST_RSA_BITS);
    }

    /**
     * @param passphrase UTF-8 passphrase, or null for an unencrypted file
     * @return {@code -----BEGIN OPENSSH PRIVATE KEY-----}, what {@code ssh-keygen} writes by default
     */
    static byte[] openSshV1(final String passphrase) throws JSchException {
        final KeyPair pair = jschRsa();
        try {
            pair.setPublicKeyComment("fixture@markor");
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            pair.writeOpenSSHv1PrivateKey(out, passphrase == null ? null : passphrase.getBytes(UTF8));
            return out.toByteArray();
        } finally {
            pair.dispose();
        }
    }

    /** @return {@code -----BEGIN RSA PRIVATE KEY-----}, i.e. {@code ssh-keygen -m PEM} */
    static byte[] pkcs1Pem() throws JSchException {
        final KeyPair pair = jschRsa();
        try {
            pair.setPublicKeyComment("fixture@markor");
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            pair.writePrivateKey(out);
            return out.toByteArray();
        } finally {
            pair.dispose();
        }
    }

    /**
     * @param algorithm a JCA key algorithm, {@code RSA} or {@code Ed25519}
     * @param bits      key size, ignored for algorithms with a fixed one
     * @return {@code -----BEGIN PRIVATE KEY-----}, i.e. {@code ssh-keygen -m PKCS8}
     */
    static byte[] pkcs8Pem(final String algorithm, final int bits) throws GeneralSecurityException {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance(algorithm);
        if ("RSA".equalsIgnoreCase(algorithm)) {
            generator.initialize(bits);
        }
        final byte[] encoded = generator.generateKeyPair().getPrivate().getEncoded();
        final StringBuilder sb = new StringBuilder("-----BEGIN PRIVATE KEY-----\n");
        final String body = Base64.getEncoder().encodeToString(encoded);
        for (int i = 0; i < body.length(); i += 64) {
            sb.append(body, i, Math.min(body.length(), i + 64)).append('\n');
        }
        return sb.append("-----END PRIVATE KEY-----\n").toString().getBytes(UTF8);
    }

    /** @return true when {@code bytes} is a private key JSch says is encrypted */
    static boolean isEncrypted(final byte[] bytes) throws JSchException {
        final KeyPair pair = KeyPair.load(new JSch(), bytes.clone(), null);
        try {
            return pair.isEncrypted();
        } finally {
            pair.dispose();
        }
    }

    /** @return the SHA256 fingerprint JSch computes for the public half of {@code privateKeyBytes} */
    static String fingerprintOf(final byte[] privateKeyBytes, final String passphrase) throws JSchException {
        final KeyPair pair = KeyPair.load(new JSch(), privateKeyBytes.clone(), null);
        try {
            if (pair.isEncrypted() && passphrase != null) {
                pair.decrypt(passphrase.getBytes(UTF8));
            }
            return GitSshPublicKeys.fingerprintSha256(pair.getPublicKeyBlob());
        } finally {
            pair.dispose();
        }
    }

    static byte[] utf8(final String s) {
        return s.getBytes(UTF8);
    }

    static String asString(final byte[] bytes) {
        return new String(bytes, UTF8);
    }
}
