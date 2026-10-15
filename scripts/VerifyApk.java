import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;

public final class VerifyApk {
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 3);
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) value.append(':');
            value.append(String.format("%02X", bytes[i]));
        }
        return value.toString();
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            throw new IllegalArgumentException("Pass one or more APK paths.");
        }
        boolean allVerified = true;
        for (String path : args) {
            ApkVerifier.Result result = new ApkVerifier.Builder(new File(path)).build().verify();
            System.out.println("FILE=" + new File(path).getName());
            System.out.println("VERIFIED=" + result.isVerified());
            System.out.println("SCHEMES=v1:" + result.isVerifiedUsingV1Scheme()
                    + ",v2:" + result.isVerifiedUsingV2Scheme()
                    + ",v3:" + result.isVerifiedUsingV3Scheme()
                    + ",v3.1:" + result.isVerifiedUsingV31Scheme()
                    + ",v4:" + result.isVerifiedUsingV4Scheme());
            for (X509Certificate certificate : result.getSignerCertificates()) {
                System.out.println("SUBJECT=" + certificate.getSubjectX500Principal());
                System.out.println("CERT_SHA256=" + hex(
                        MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
            }
            if (!result.getErrors().isEmpty()) {
                System.out.println("ERRORS=" + result.getErrors());
            }
            allVerified &= result.isVerified();
        }
        if (!allVerified) System.exit(1);
    }
}
