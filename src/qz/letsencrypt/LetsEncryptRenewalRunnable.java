package qz.letsencrypt;

import org.bouncycastle.asn1.x500.*;
import org.bouncycastle.asn1.x500.style.*;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.asn1.pkcs.*;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import qz.ws.PrintSocketServer;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.net.ProtocolException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.security.auth.x500.X500Principal;
import java.security.cert.CertificateFactory;
import java.security.spec.RSAKeyGenParameterSpec;
import java.security.InvalidAlgorithmParameterException;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class LetsEncryptRenewalRunnable implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(LetsEncryptRenewalRunnable.class);

    private String renewalURL;
    private String renewalCredentials;
    private int renewalDaysBeforeExpiration;

    private String keyStorePath;
    private String keyStorePassword;
    private String keyStoreKeyPassword;

    private String httpAuth;
    private List<String> httpCookies;

    private boolean firstRun;

    public LetsEncryptRenewalRunnable(String renewalURL, String renewalCredentials, int renewalDaysBeforeExpiration, String keyStorePath, String keyStorePassword, String keyStoreKeyPassword) {
        this.renewalURL = renewalURL;
        this.renewalCredentials = renewalCredentials;
        this.renewalDaysBeforeExpiration = renewalDaysBeforeExpiration;

        this.httpAuth = new String(Base64.getEncoder().encode(renewalCredentials.getBytes()));
        this.httpCookies = new ArrayList<String>();

        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword;
        this.keyStoreKeyPassword = keyStoreKeyPassword;

        this.firstRun = true;
    }

    public void run() {
        KeyStore ks = null;
        java.io.FileInputStream fis = null;

        try {
            // XXX - Shitty hack to prevent this from running immediately
            //       on initial start-up and trampling the regular start-up
            //       if the certificate is eligible to be renewed immediately
            if (this.firstRun) {
                Thread.sleep(300000);
                firstRun = false;
            }

            fis = new java.io.FileInputStream(keyStorePath);

            ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(fis, keyStorePassword.toCharArray());

            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate("qz-tray");
            long diff = cert.getNotAfter().getTime() - new Date().getTime();
            long daysTillExpiration = TimeUnit.DAYS.convert(diff, TimeUnit.MILLISECONDS);

            if (daysTillExpiration > renewalDaysBeforeExpiration) {
                log.info("Skipping Let's Encrypt certificate renewal, still " + daysTillExpiration + " days left");
                return;
            }
        } catch(Exception e) {
            log.error("Couldn't open keystore to check for Let's Encrypt cert renewal");
            return;
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch(IOException e) {
                    log.warn("Error closing KeyStore file");
                }
            }
        }

        String challenge = null;
        String signature = null;

        try {
            String response = httpRequest("GET", renewalURL, null);
            challenge = new JSONObject(response).getString("challenge");
        } catch(Exception e) {
            log.error("Error getting challenge for Let's Encrypt cert renewal");
            return;
        }

        // Sign the challenge with the current private key
        try {
            PrivateKey key = (PrivateKey) ks.getKey("qz-tray", keyStoreKeyPassword.toCharArray());
            Signature signer = Signature.getInstance("SHA256withRSAandMGF1", "BC");
            signer.initSign(key);
            signer.update(challenge.getBytes());
            signature = Base64.getUrlEncoder().encodeToString(signer.sign());
        } catch(Exception e) {
            log.error("Error signing challenge for Let's Encrypt cert renewal");
            return;
        }

        String pemCSR = null;
        PrivateKey privateKey = null;

        try {
            java.security.cert.X509Certificate unwrappedCert = (java.security.cert.X509Certificate) ks.getCertificate("qz-tray");

            JcaX509CertificateHolder cert = new JcaX509CertificateHolder(unwrappedCert);
            RDN cn = cert.getSubject().getRDNs(BCStyle.CN)[0];

            String subject = IETFUtils.valueToString(cn.getFirst().getValue());

            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            RSAKeyGenParameterSpec params = new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4);
            kpg.initialize(params);
            KeyPair pair = kpg.generateKeyPair();

            GeneralNames subjectAltNames = new GeneralNames(new GeneralName(GeneralName.dNSName, subject));

            ExtensionsGenerator extGen = new ExtensionsGenerator();
            extGen.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            extGen.addExtension(Extension.subjectAlternativeName, false, subjectAltNames);

            PKCS10CertificationRequestBuilder p10Builder = new JcaPKCS10CertificationRequestBuilder(
                new X500Principal("CN=" + subject), pair.getPublic());
            p10Builder.addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extGen.generate());

            JcaContentSignerBuilder csBuilder = new JcaContentSignerBuilder("SHA256withRSA");
            privateKey = pair.getPrivate();
            ContentSigner signer = csBuilder.build(privateKey);

            PKCS10CertificationRequest csr = p10Builder.build(signer);

            PemObject pemObject = new PemObject("CERTIFICATE REQUEST", csr.getEncoded());
            StringWriter str = new StringWriter();
            JcaPEMWriter pemWriter = new JcaPEMWriter(str);
            pemWriter.writeObject(pemObject);
            pemWriter.close();

            pemCSR = str.toString();
        } catch(Exception e) {
            log.error("Error generating CSR for Let's Encrypt cert renewal");
            return;
        }

        String getCertificateURL = null;

        try {
            java.security.cert.X509Certificate unwrappedCert = (java.security.cert.X509Certificate) ks.getCertificate("qz-tray");

            JcaX509CertificateHolder cert = new JcaX509CertificateHolder(unwrappedCert);
            PemObject pemObject = new PemObject("CERTIFICATE", cert.getEncoded());
            StringWriter str = new StringWriter();
            JcaPEMWriter pemWriter = new JcaPEMWriter(str);
            pemWriter.writeObject(pemObject);
            pemWriter.close();

            JSONObject data = new JSONObject();
            data.put("csr", pemCSR);
            data.put("certificate", str.toString());
            data.put("signature", signature);

            String response = httpRequest("POST", renewalURL, data.toString());
            getCertificateURL = new JSONObject(response).getString("url");
        } catch(Exception e) {
            log.error("Error sending CSR for Let's Encrypt cert renewal to server");
            return;
        }

        String certificate = null;

        try {
            // XXX - Hacky, but sleep to give it long enough to succeed.
            //       This could instead be a poll every few seconds, but
            //       I got lazy and that was going to get messy quickly
            Thread.sleep(30000);
            String response = httpRequest("GET", getCertificateURL, null);
            JSONObject jsonResponse = new JSONObject(response);
            String status = jsonResponse.getString("status");

            if (status.equals("success")) {
                certificate = jsonResponse.getString("certificate");
            } else {
                log.error("Status for Let's Encrypt cert renewal was '" + status + "'' after waiting");
                return;
            }
        } catch(Exception e) {
            log.error("Error getting certificate for Let's Encrypt cert renewal");
            return;
        }

        try {
            InputStream stream = new ByteArrayInputStream(certificate.getBytes(StandardCharsets.UTF_8.name()));
            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);

            ks.setKeyEntry("qz-tray", privateKey, keyStoreKeyPassword.toCharArray(), new java.security.cert.Certificate[]{ cert });

            java.io.FileOutputStream fos = null;
            try {
                fos = new java.io.FileOutputStream(keyStorePath);
                ks.store(fos, keyStorePassword.toCharArray());
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch(IOException e) {
                        log.warn("Error closing KeyStore file");
                    }
                }
            }
        } catch(Exception e) {
            log.error("Error saving cert from Let's Encrypt cert renewal");
            return;
        }

        try {
            PrintSocketServer.reloadServer();
        } catch (Exception e) {
            e.printStackTrace();
            log.error("Failed to reload after renewing Let's Encrypt cert");
        }
    }

    private String httpRequest(String method, String url, String data) {
        BufferedReader resultReader = null;

        try {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod(method);
            } catch (ProtocolException e) {
                throw new IllegalArgumentException("Invalid HTTP method");
            }
            if (data != null) {
                connection.setDoOutput(true);
            }
            if (httpAuth != null) {
                connection.setRequestProperty("Authorization", "Basic " + httpAuth);
            }
            for (String cookie : httpCookies) {
                connection.addRequestProperty("Cookie", cookie);
            }

            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);

            if (data != null) {
                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());

                writer.write(data);
                writer.flush();
                writer.close();
            }

            resultReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            String response = resultReader.readLine();
            int responseCode = connection.getResponseCode();
            httpCookies = connection.getHeaderFields().get("Set-Cookie");

            if (responseCode != HttpsURLConnection.HTTP_OK) {
                log.error("Got non-200 OK response from server", responseCode);
                return null;
            }

            return response;
        } catch (IOException e) {
            // Connection error
            log.warn("Error connecting to URL", e);
        } finally {
            if (resultReader != null) {
                try {
                    // If a connection was opened, try to close it
                    resultReader.close();
                } catch (IOException e) {
                    // Error closing the connection
                    log.debug("Failed to close HTTPS connection", e);
                }
            }
        }

        return null;
    }
}
