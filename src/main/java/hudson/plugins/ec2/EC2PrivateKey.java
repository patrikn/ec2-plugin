package hudson.plugins.ec2;

import hudson.util.Secret;
import org.apache.commons.codec.binary.Hex;
import org.bouncycastle.openssl.PEMReader;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.security.DigestInputStream;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Security;

import com.xerox.amazonws.ec2.KeyPairInfo;
import com.xerox.amazonws.ec2.Jec2;
import com.xerox.amazonws.ec2.EC2Exception;
import org.bouncycastle.openssl.PasswordFinder;

/**
 * RSA private key (the one that you generate with ec2-add-keypair.)
 *
 * Starts with "----- BEGIN RSA PRIVATE KEY------\n".
 *
 * @author Kohsuke Kawaguchi
 */
final class EC2PrivateKey {
    private final Secret privateKey;

    EC2PrivateKey(String privateKey) {
        this.privateKey = Secret.fromString(privateKey.trim());
    }

    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     */
    /**
     * Obtains the fingerprint of the key in the "ab:cd:ef:...:12" format.
     */
    public String getFingerprint() throws IOException {
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        Reader r = new BufferedReader(new StringReader(privateKey.toString()));
        PEMReader pem = new PEMReader(r,new PasswordFinder() {
            public char[] getPassword() {
                throw PRIVATE_KEY_WITH_PASSWORD;
            }
        });

        try {
            KeyPair pair = (KeyPair) pem.readObject();
            if(pair==null)  return null;
            PrivateKey key = pair.getPrivate();
            return digest(key);
        } catch (RuntimeException e) {
            if (e==PRIVATE_KEY_WITH_PASSWORD)
                throw new IOException("This private key is password protected, which isn't supported yet");
            throw e;
        }
    }

    /**
     * Is this file really a private key?
     */
    public boolean isPrivateKey() throws IOException {
        BufferedReader br = new BufferedReader(new StringReader(privateKey.toString()));
        String line;
        while ((line = br.readLine()) != null) {
            if (line.equals("-----BEGIN RSA PRIVATE KEY-----"))
                return true;
        }
        return false;
    }

    /**
     * Finds the {@link KeyPairInfo} that corresponds to this key in EC2.
     */
    public KeyPairInfo find(Jec2 ec2) throws IOException, EC2Exception {
        String fp = getFingerprint();
        for(KeyPairInfo kp : ec2.describeKeyPairs(new String[0])) {
            if(kp.getKeyFingerprint().equalsIgnoreCase(fp))
                return new KeyPairInfo(kp.getKeyName(),fp,privateKey.toString());
        }
        return null;
    }

    @Override
    public int hashCode() {
        return privateKey.hashCode();
    }

    @Override
    public boolean equals(Object that) {
        return that instanceof EC2PrivateKey && this.privateKey.equals(((EC2PrivateKey)that).privateKey);
    }

    @Override
    public String toString() {
        return privateKey.toString();
    }

    /*package*/ static String digest(PrivateKey k) throws IOException {
        try {
            MessageDigest md5 = MessageDigest.getInstance("SHA1");

            DigestInputStream in = new DigestInputStream(new ByteArrayInputStream(k.getEncoded()), md5);
            try {
                while (in.read(new byte[128]) > 0)
                    ; // simply discard the input
            } finally {
                in.close();
            }
            StringBuilder buf = new StringBuilder();
            char[] hex = Hex.encodeHex(md5.digest());
            for( int i=0; i<hex.length; i+=2 ) {
                if(buf.length()>0)  buf.append(':');
                buf.append(hex,i,2);
            }
            return buf.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    private static final RuntimeException PRIVATE_KEY_WITH_PASSWORD = new RuntimeException();
}
