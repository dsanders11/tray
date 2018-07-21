package felix;

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;

import qz.ws.PrintSocketServer;
import qz.service.HostService;


public class HostActivator implements BundleActivator {
    private BundleContext m_context = null;

    public void start(BundleContext context) throws Exception {
        m_context = context;

        context.registerService(HostService.class.getName(), new HostImpl(), null);
    }

    public void stop(BundleContext context) {
        m_context = null;
    }

    public Bundle[] getBundles() {
        if (m_context != null) {
            return m_context.getBundles();
        }
        return null;
    }

    // TODO - This would probably be better in a separate file
    private static class HostImpl implements HostService {
        public void reload() throws Exception {
            PrintSocketServer.reloadServer();
        }
    }
}
