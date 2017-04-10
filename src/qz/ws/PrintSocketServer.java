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

import org.apache.commons.io.IOUtils;
import org.apache.log4j.Level;
import org.apache.log4j.PatternLayout;
import org.apache.log4j.rolling.FixedWindowRollingPolicy;
import org.apache.log4j.rolling.RollingFileAppender;
import org.apache.log4j.rolling.SizeBasedTriggeringPolicy;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.util.MultiException;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.websocket.server.WebSocketHandler;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.common.Constants;
import qz.common.TrayManager;
import qz.deploy.DeployUtilities;
import qz.queue.QueueClient;
import qz.utils.FileUtilities;
import qz.utils.SystemUtilities;

import javax.swing.*;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.net.BindException;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import java.util.Arrays;
import java.util.ArrayList;
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
    private static TrayManager trayManager;
    private static Properties trayProperties;

    private static boolean headless;


    public static void main(String[] args) {
        List<String> sArgs = Arrays.asList(args);

        if (sArgs.contains("-a") || sArgs.contains("--whitelist")) {
            int fileIndex = Math.max(sArgs.indexOf("-a"), sArgs.indexOf("--whitelist")) + 1;
            addToList(Constants.ALLOW_FILE, new File(sArgs.get(fileIndex)));
            System.exit(0);
        }
        if (sArgs.contains("-b") || sArgs.contains("--blacklist")) {
            int fileIndex = Math.max(sArgs.indexOf("-b"), sArgs.indexOf("--blacklist")) + 1;
            addToList(Constants.BLOCK_FILE, new File(sArgs.get(fileIndex)));
            System.exit(0);
        }
        if (sArgs.contains("-h") || sArgs.contains("--headless")) {
            headless = true;
        }
        if (sArgs.contains("-v") || sArgs.contains("--version")) {
            System.out.println(Constants.VERSION);
            System.exit(0);
        }

        setupFileLogging();

        trayProperties = getTrayProperties();

        try {
            SwingUtilities.invokeAndWait(() -> trayManager = new TrayManager(headless));
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

    private static void addToList(String list, File certFile) {
        try {
            FileReader fr = new FileReader(certFile);
            Certificate cert = new Certificate(IOUtils.toString(fr));

            if (FileUtilities.printLineToFile(list, cert.data())) {
                log.info("Successfully added {} to {} list", cert.getOrganization(), list);
            } else {
                log.warn("Failed to add certificate to {} list (Insufficient user privileges)", list);
            }
        }
        catch(Exception e) {
            log.error("Failed to add certificate:", e);
        }
    }

    private static void setupFileLogging() {
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

    public static void runServer() {
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicInteger securePortIndex = new AtomicInteger(0);
        final AtomicInteger insecurePortIndex = new AtomicInteger(0);

        while(!running.get() && securePortIndex.get() < SECURE_PORTS.size() && insecurePortIndex.get() < INSECURE_PORTS.size()) {
            Server server = new Server(INSECURE_PORTS.get(insecurePortIndex.get()));

            if (trayProperties != null) {
                // Bind the secure socket on the proper port number (i.e. 9341), add it as an additional connector
                SslContextFactory sslContextFactory = new SslContextFactory();
                sslContextFactory.setKeyStorePath(trayProperties.getProperty("wss.keystore"));
                sslContextFactory.setKeyStorePassword(trayProperties.getProperty("wss.storepass"));

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
                server.setHandler(wsHandler);
                server.setStopAtShutdown(true);
                server.start();

                running.set(true);
                trayManager.setServer(server, running, securePortIndex, insecurePortIndex);
                log.info("Server started on port(s) " + TrayManager.getPorts(server));

                server.join();
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
