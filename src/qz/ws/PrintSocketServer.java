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
import org.bouncycastle.openssl.PEMWriter;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.PKCS10CertificationRequestBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
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
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.security.auth.x500.X500Principal;
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


    private static List<QueueClient> queueClients = null;
    private static TrayManager trayManager = null;
    private static Properties trayProperties = null;
    private static Server server = null;

    private static final AtomicBoolean running = new AtomicBoolean(true);
    private static final AtomicInteger securePortIndex = new AtomicInteger(0);
    private static final AtomicInteger insecurePortIndex = new AtomicInteger(0);


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

        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    trayManager = new TrayManager();
                }
            });
            queueClients = runQueueClients();
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

    public static void generateCSR(String keyStorePath, String keyStorePass, String keyStoreKeyPassword) {
        java.io.FileInputStream fis = null;
        String subject = null;
        PrivateKey key = null;

        try {
            fis = new java.io.FileInputStream(keyStorePath);

            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(fis, keyStorePass.toCharArray());

            java.security.cert.X509Certificate cert = (java.security.cert.X509Certificate) ks.getCertificate("qz-tray");

            X500Name x500name = new JcaX509CertificateHolder(cert).getSubject();
            RDN cn = x500name.getRDNs(BCStyle.CN)[0];

            subject = IETFUtils.valueToString(cn.getFirst().getValue());
            key = (PrivateKey) ks.getKey("qz-tray", keyStoreKeyPassword.toCharArray());

            Signature signer = Signature.getInstance("SHA256WithRSA/PSS", "BC");
            signer.initSign(key);
            String temp = "foobar";
            signer.update(temp.getBytes());
            Base64.Encoder encoder = Base64.getUrlEncoder();
            String signature = encoder.encodeToString(signer.sign());
            System.out.println(signature);
        } catch(Exception e) {
            // TODO
            e.printStackTrace();
        } finally {
            if (fis != null) {
                try {
                    fis.close();
                } catch(IOException e) {
                    // Ignore
                }
            }
        }

        try {
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
            ContentSigner signer = csBuilder.build(pair.getPrivate());

            PKCS10CertificationRequest csr = p10Builder.build(signer);

            PemObject pemObject = new PemObject("CERTIFICATE REQUEST", csr.getEncoded());
            StringWriter str = new StringWriter();
            PEMWriter pemWriter = new PEMWriter(str);
            pemWriter.writeObject(pemObject);
            pemWriter.close();
            System.out.println(str);
        } catch(Exception e) {
            // TODO
        }
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
                String keyStorePath = trayProperties.getProperty("wss.keystore");
                String keyStorePassword = trayProperties.getProperty("wss.storepass");
                String keyStoreKeyPassword = trayProperties.getProperty("wss.keypass");

                // Bind the secure socket on the proper port number (i.e. 9341), add it as an additional connector
                SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setKeyStorePath(keyStorePath);
                sslContextFactory.setKeyStorePassword(keyStorePassword);
                sslContextFactory.setKeyManagerPassword(keyStoreKeyPassword);

                generateCSR(keyStorePath, keyStorePassword, keyStoreKeyPassword);

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

                // HACK
                //Thread.sleep(20000);
                //server.stop();
                // END HACK

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
