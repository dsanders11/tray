/**
 * @author Robert Casto
 *
 * Copyright (C) 2016 Tres Finocchiaro, QZ Industries, LLC
 *
 * LGPL 2.1 This is free software.  This software and source code are released under
 * the "LGPL 2.1 License".  A copy of this license should be distributed with
 * this software. http://www.gnu.org/licenses/lgpl-2.1.html
 */

package qz.ws;

import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.rolling.FixedWindowRollingPolicy;
import org.apache.log4j.rolling.RollingFileAppender;
import org.apache.log4j.rolling.SizeBasedTriggeringPolicy;
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
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.common.Constants;
import qz.common.SecurityInfo;
import qz.common.TrayManager;
import qz.deploy.DeployUtilities;
import qz.queue.QueueClient;
import qz.utils.FileUtilities;
import qz.utils.SystemUtilities;

import javax.swing.*;
import java.io.File;
import java.net.BindException;
import java.util.*;
import java.io.*;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.net.BindException;
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
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Created by robert on 9/9/2014.
 */

public class PrintSocketServer {

    private static final Logger log = LoggerFactory.getLogger(PrintSocketServer.class);

    private static final int MAX_MESSAGE_SIZE = Integer.MAX_VALUE;
    public static final List<Integer> SECURE_PORTS = Collections.unmodifiableList(Arrays.asList(8181, 8282, 8383, 8484));
    public static final List<Integer> INSECURE_PORTS = Collections.unmodifiableList(Arrays.asList(8182, 8283, 8384, 8485));


    private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    private static List<QueueClient> queueClients = null;
    private static TrayManager trayManager = null;
    private static Properties trayProperties = null;
    private static Server server = null;

    private static String keyStorePath = null;
    private static String keyStorePassword = null;
    private static String keyStoreKeyPassword = null;

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicInteger securePortIndex = new AtomicInteger(0);
    private static final AtomicInteger insecurePortIndex = new AtomicInteger(0);


    static class LetsEncryptRenewalRunnable implements Runnable {
        private String renewalURL;
        private String renewalCredentials;
        private int renewalDaysBeforeExpiration;

        private String keyStorePath;
        private String keyStorePassword;
        private String keyStoreKeyPassword;

        private String httpAuth;
        private List<String> httpCookies;

        private boolean firstRun;

        LetsEncryptRenewalRunnable(String renewalURL, String renewalCredentials, int renewalDaysBeforeExpiration, String keyStorePath, String keyStorePassword, String keyStoreKeyPassword) {
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
                    // Thread.sleep(300000);
                    Thread.sleep(30000);
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
                Signature signer = Signature.getInstance("SHA256WithRSA/PSS", "BC");
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

    public static void main(String[] args) {
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        for(String s : args) {
            // Print version information and exit
            if ("-v".equals(s) || "--version".equals(s)) {
                System.out.println(Constants.VERSION);
                System.exit(0);
            }
            // Print library list and exits
            if ("-l".equals(s) || "--libinfo".equals(s)) {
                String format = "%-40s%s%n";
                System.out.printf(format, "LIBRARY NAME:", "VERSION:");
                SortedMap<String, String> libVersions = SecurityInfo.getLibVersions();
                for (Map.Entry<String, String> entry: libVersions.entrySet()) {
                    if (entry.getValue() == null) {
                        System.out.printf(format, entry.getKey(), "(unknown)");
                    } else {
                        System.out.printf(format, entry.getKey(), entry.getValue());
                    }
                }
                System.exit(0);
            }
        }

        log.info(Constants.ABOUT_TITLE + " version: {}", Constants.VERSION);
        log.info(Constants.ABOUT_TITLE + " vendor: {}", Constants.ABOUT_COMPANY);
        log.info("Java version: {}", Constants.JAVA_VERSION.toString());
        setupFileLogging();

        trayProperties = getTrayProperties();

        if (trayProperties != null) {
            keyStorePath = trayProperties.getProperty("wss.keystore");
            keyStorePassword = trayProperties.getProperty("wss.storepass");
            keyStoreKeyPassword = trayProperties.getProperty("wss.keypass");
        }

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    trayManager = new TrayManager();
                }
            });

            queueClients = runQueueClients();

            boolean hasLetsEncryptRenewalURL = trayProperties.containsKey("letsencrypt.renewal.url");

            if (hasLetsEncryptRenewalURL) {
                String renewalURL = trayProperties.getProperty("letsencrypt.renewal.url");
                String renewalCredentials = trayProperties.getProperty("letsencrypt.renewal.credentials");
                int renewalBeforeExpirationDays = Integer.parseUnsignedInt(trayProperties.getProperty("letsencrypt.renewal.daysBeforeExpiration", "5"));

                ScheduledFuture<?> letsEncryptRenewalService = scheduler.scheduleWithFixedDelay(
                    new LetsEncryptRenewalRunnable(renewalURL, renewalCredentials, renewalBeforeExpirationDays,
                        keyStorePath, keyStorePassword, keyStoreKeyPassword),
                    0, 1, TimeUnit.DAYS);
            }

            runServer();
        }
        catch(Exception e) {
            log.error("Could not start tray manager", e);
        }

        if (queueClients != null) {
            // Try to release any claimed print jobs, we're not going to get to them
            for (QueueClient client : queueClients) {
                client.releaseClaimedQueue();
            }
        }

        log.warn("The web socket server is no longer running");
    }

    public static void setupFileLogging() {
        FixedWindowRollingPolicy rollingPolicy = new FixedWindowRollingPolicy();
        rollingPolicy.setFileNamePattern(SystemUtilities.getDataDirectory() + File.separator + Constants.LOG_FILE + ".log.%i");
        rollingPolicy.setMaxIndex(Constants.LOG_ROTATIONS);

        SizeBasedTriggeringPolicy triggeringPolicy = new SizeBasedTriggeringPolicy(Constants.LOG_SIZE);

        RollingFileAppender fileAppender = new RollingFileAppender();
        fileAppender.setLayout(new PatternLayout("%d{ISO8601} [%p] %m%n"));
        fileAppender.setThreshold(Level.DEBUG);
        fileAppender.setFile(SystemUtilities.getDataDirectory() + File.separator + Constants.LOG_FILE + ".log");
        fileAppender.setRollingPolicy(rollingPolicy);
        fileAppender.setTriggeringPolicy(triggeringPolicy);
        fileAppender.setEncoding("UTF-8");

        fileAppender.setImmediateFlush(true);
        fileAppender.activateOptions();

        org.apache.log4j.Logger.getRootLogger().addAppender(fileAppender);
    }

    public static List<QueueClient> runQueueClients() throws InvocationTargetException, InterruptedException {
        boolean hasHttpQueueURL = trayProperties.containsKey("http.url");

        if (hasHttpQueueURL && !trayProperties.containsKey("http.printerIDs")) {
            throw new IllegalArgumentException("http.printerIDs required when using http.url");
        } else if (!hasHttpQueueURL) {
            String[] httpQueueKeys = new String[]{
                "http.credentials", "http.maxClaim", "http.sleepSecs", "http.printerIDs"
            };

            for (String key : httpQueueKeys) {
                if (trayProperties.containsKey(key)) {
                    throw new IllegalArgumentException("Can only use key '" + key + "' if 'http.url' set");
                }
            }
        }

        if (hasHttpQueueURL) {
            // Grab the required configuration keys
            final String httpQueueURL = trayProperties.getProperty("http.url");
            final ArrayList<String> httpQueuePrinterIDs = new ArrayList<String>();

            try {
                JSONArray printerIDs = new JSONArray(trayProperties.getProperty("http.printerIDs"));

                for (int i=0; i < printerIDs.length(); i++) {
                    httpQueuePrinterIDs.add(printerIDs.getString(i));
                }
            } catch(JSONException e) {
                throw new IllegalArgumentException("'http.printerIDs' must be a JSON array of strings");
            }

            // Optional configuration keys
            final String httpQueueCredentials = trayProperties.getProperty("http.credentials", null);
            final int httpQueueMaxClaim;
            final int httpQueueSleepSecs;

            try {
                httpQueueMaxClaim = Integer.parseUnsignedInt(trayProperties.getProperty("http.maxClaim", "5"));
            } catch(NumberFormatException e) {
                throw new IllegalArgumentException("'http.maxClaim' must be a positive integer");
            }

            try {
                httpQueueSleepSecs = Integer.parseUnsignedInt(trayProperties.getProperty("http.sleepSecs", "30"));
            } catch(NumberFormatException e) {
                throw new IllegalArgumentException("'http.sleepSecs' must be a positive integer");
            }

            final ArrayList<QueueClient> queueClients = new ArrayList<QueueClient>();

            for (String printerID : httpQueuePrinterIDs) {
                final QueueClient client = new QueueClient(printerID, httpQueueURL, httpQueueCredentials);
                queueClients.add(client);

                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean running = true;

                        // There may be already claimed jobs if we were killed or
                        // lost connection previously, so try to resume them
                        client.processClaimedQueue();

                        while (running) {
                            client.claimAndProcessJobs(httpQueueMaxClaim);

                            try {
                                Thread.sleep(httpQueueSleepSecs*1000);
                            } catch(InterruptedException e) {
                                running = false;
                            }
                        }
                    }
                }).start();
            }

            return queueClients;
        }

        return null;
    }

    public static void reloadServer() throws Exception {
        // Force reload of tray properties
        trayProperties = null;

        securePortIndex.set(0);
        insecurePortIndex.set(0);

        server.stop();
    }

    public static void runServer() {
        while(running.get() && securePortIndex.get() < SECURE_PORTS.size() && insecurePortIndex.get() < INSECURE_PORTS.size()) {
            trayProperties = getTrayProperties();
            server = new Server(INSECURE_PORTS.get(insecurePortIndex.get()));

            if (trayProperties != null) {
                // Bind the secure socket on the proper port number (i.e. 9341), add it as an additional connector
                SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setKeyStorePath(keyStorePath);
                sslContextFactory.setKeyStorePassword(keyStorePassword);
                sslContextFactory.setKeyManagerPassword(keyStoreKeyPassword);

                SslConnectionFactory sslConnection = new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString());
                HttpConnectionFactory httpConnection = new HttpConnectionFactory(new HttpConfiguration());

                ServerConnector connector = new ServerConnector(server, sslConnection, httpConnection);
                connector.setHost(trayProperties.getProperty("wss.host"));
                connector.setPort(SECURE_PORTS.get(securePortIndex.get()));
                server.addConnector(connector);
            } else {
                log.warn("Could not start secure WebSocket");
            }

            try {
                final WebSocketHandler wsHandler = new WebSocketHandler() {
                    @Override
                    public void configure(WebSocketServletFactory factory) {
                        factory.register(PrintSocketClient.class);
                        factory.getPolicy().setMaxTextMessageSize(MAX_MESSAGE_SIZE);
                    }
                };
                StatisticsHandler statsHandler = new StatisticsHandler();
                statsHandler.setHandler(wsHandler);
                server.setHandler(statsHandler);
                server.setStopTimeout(1000);
                server.setStopAtShutdown(true);
                server.start();

                running.set(true);
                trayManager.setServer(server, running, securePortIndex, insecurePortIndex);
                log.info("Server started on port(s) " + TrayManager.getPorts(server));

                server.join();
                while (!server.isStopped()) {
                    Thread.sleep(100);
                }
                server.destroy();
            }
            catch(BindException | MultiException e) {
                //order of getConnectors is the order we added them -> insecure first
                if (server.getConnectors()[0].isFailed()) {
                    insecurePortIndex.incrementAndGet();
                }
                if (server.getConnectors().length > 1 && server.getConnectors()[1].isFailed()) {
                    securePortIndex.incrementAndGet();
                }

                //explicitly stop the server, because if only 1 port has an exception the other will still be opened
                try { server.stop(); }catch(Exception ignore) { ignore.printStackTrace(); }
            }
            catch(Exception e) {
                e.printStackTrace();
                trayManager.displayErrorMessage(e.getLocalizedMessage());
            }
        }
    }

    /**
     * Get the TrayManager instance for this SocketServer
     *
     * @return The TrayManager instance
     */
    public static TrayManager getTrayManager() {
        return trayManager;
    }

    public static Properties getTrayProperties() {
        if (trayProperties == null) {
            trayProperties = DeployUtilities.loadTrayProperties();
        }
        return trayProperties;
    }
}
