import com.android.apksig.ApkSigner;
import com.android.apksig.ApkVerifier;
import java.io.File;
import java.io.FileInputStream;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Sign {
    public static void main(String[] args) throws Exception {
        String store = args[0], pass = args[1], alias = args[2];
        String in = args[3], out = args[4];
        int minSdk = Integer.parseInt(args[5]);

        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (FileInputStream fis = new FileInputStream(store)) {
            ks.load(fis, pass.toCharArray());
        }
        PrivateKey key = (PrivateKey) ks.getKey(alias, pass.toCharArray());
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : ks.getCertificateChain(alias)) {
            certs.add((X509Certificate) c);
        }

        ApkSigner.SignerConfig cfg =
                new ApkSigner.SignerConfig.Builder("CERT", key, certs).build();
        new ApkSigner.Builder(Collections.singletonList(cfg))
                .setInputApk(new File(in))
                .setOutputApk(new File(out))
                .setMinSdkVersion(minSdk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .build()
                .sign();
        System.out.println("SIGNED  " + out);

        ApkVerifier.Result r = new ApkVerifier.Builder(new File(out))
                .setMinCheckedPlatformVersion(minSdk)
                .build().verify();
        System.out.println("verified      = " + r.isVerified());
        System.out.println("v1 scheme     = " + r.isVerifiedUsingV1Scheme());
        System.out.println("v2 scheme     = " + r.isVerifiedUsingV2Scheme());
        for (Object e : r.getErrors()) System.out.println("ERROR   " + e);
        int w = 0;
        for (Object e : r.getWarnings()) { System.out.println("WARN    " + e); w++; }
        if (w == 0) System.out.println("warnings      = none");
        if (!r.isVerified() || !r.getErrors().isEmpty()) System.exit(1);
    }
}
