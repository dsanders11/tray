package qz.letsencrypt;

import java.util.Hashtable;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceListener;
import org.osgi.framework.ServiceEvent;
import org.osgi.framework.ServiceReference;

import qz.service.HostService;
import qz.service.PluginService;
import qz.ws.PrintSocketServer;

public class Activator implements BundleActivator {
    public void start(BundleContext context) throws Exception {
        ServiceReference ref = context.getServiceReference(HostService.class.getName());
        HostService hostService = (HostService) context.getService(ref);

        // Register ourselves as a PluginService, with a name
        Hashtable<String, String> props = new Hashtable<String, String>();
        props.put("Name", "Let's Encrypt Renewal Plugin");
        context.registerService(
            PluginService.class.getName(), new PluginImpl(hostService), props);
    }

    /**
     * Implements BundleActivator.stop(). Does nothing since
     * the framework will automatically unregister any registered services.
     * @param context the framework context for the bundle.
    **/
    public void stop(BundleContext context) {
        // NOTE: The service is automatically unregistered.
    }

    // TODO - This would probably be better in a separate file
    private static class PluginImpl implements PluginService {
        private static final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        private HostService m_hostService;

        public PluginImpl(HostService hostService) {
            m_hostService = hostService;
        }

        public void initialize(Properties trayProperties) {
            if (trayProperties != null) {
                boolean hasLetsEncryptRenewalURL = trayProperties.containsKey("letsencrypt.renewal.url");

                String keyStorePath = trayProperties.getProperty("wss.keystore");
                String keyStorePassword = trayProperties.getProperty("wss.storepass");
                String keyStoreKeyPassword = trayProperties.getProperty("wss.keypass");

                if (hasLetsEncryptRenewalURL) {
                    String renewalURL = trayProperties.getProperty("letsencrypt.renewal.url");
                    String renewalCredentials = trayProperties.getProperty("letsencrypt.renewal.credentials");
                    int renewalBeforeExpirationDays = Integer.parseUnsignedInt(trayProperties.getProperty("letsencrypt.renewal.daysBeforeExpiration", "5"));

                    ScheduledFuture<?> letsEncryptRenewalService = scheduler.scheduleWithFixedDelay(
                        new LetsEncryptRenewalRunnable(renewalURL, renewalCredentials, renewalBeforeExpirationDays,
                            keyStorePath, keyStorePassword, keyStoreKeyPassword),
                        0, 10, TimeUnit.SECONDS);
                }
            } else {
                scheduler.scheduleWithFixedDelay(
                    new Runnable() {
                        public void run() { try { m_hostService.reload(); } catch(Exception e) {} }
                    }, 0, 10, TimeUnit.SECONDS);
            }
        }
    }
}
