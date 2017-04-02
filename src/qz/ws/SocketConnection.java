package qz.ws;

import jssc.SerialPortException;
import org.apache.commons.collections4.keyvalue.MultiKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.communication.DeviceException;
import qz.communication.Device;
import qz.communication.DeviceListener;
import qz.communication.SerialIO;
import qz.utils.UsbUtilities;

import java.util.HashMap;

public class SocketConnection {

    private static final Logger log = LoggerFactory.getLogger(SocketConnection.class);


    private Certificate certificate;

    private DeviceListener deviceListener;

    // serial port -> open SerialIO
    private final HashMap<String,SerialIO> openSerialPorts = new HashMap<>();

    //vendor id -> product id -> open Device
    private final HashMap<MultiKey,Device> openDevices = new HashMap<>();


    public SocketConnection(Certificate cert) {
        certificate = cert;
    }

    public Certificate getCertificate() {
        return certificate;
    }

    public void setCertificate(Certificate newCert) {
        certificate = newCert;
    }


    public void addSerialPort(String port, SerialIO io) {
        openSerialPorts.put(port, io);
    }

    public SerialIO getSerialPort(String port) {
        return openSerialPorts.get(port);
    }

    public void removeSerialPort(String port) {
        openSerialPorts.remove(port);
    }


    public boolean isListening() {
        return deviceListener != null;
    }

    public void startListening(DeviceListener listener) {
        deviceListener = listener;
    }

    public void stopListening() {
        if (deviceListener != null) {
            deviceListener.close();
        }
        deviceListener = null;
    }


    public void addDevice(short vendor, short product, String serial, Device io) {
        openDevices.put(new MultiKey<String>(String.valueOf(vendor), String.valueOf(product), serial), io);
    }

    public Device getDevice(String vendor, String product, String serial) {
        return getDevice(UsbUtilities.hexToShort(vendor), UsbUtilities.hexToShort(product), serial);
    }

    public Device getDevice(Short vendor, Short product, String serial) {
        if (vendor == null) {
            throw new IllegalArgumentException("Vendor ID cannot be null");
        }
        if (product == null) {
            throw new IllegalArgumentException("Product ID cannot be null");
        }

        return openDevices.get(new MultiKey<String>(String.valueOf(vendor), String.valueOf(product), serial));
    }

    public void removeDevice(Short vendor, Short product, String serial) {
        openDevices.remove(new MultiKey<String>(String.valueOf(vendor), String.valueOf(product), serial));
    }

    public synchronized void openDevice(Device device, short vendorId, short productId) throws DeviceException {
        device.open();
        addDevice(vendorId, productId, null, device);
    }

    /**
     * Explicitly closes all open serial and usb connections setup through this object
     */
    public synchronized void disconnect() throws SerialPortException, DeviceException {
        log.info("Closing all communication channels for {}", certificate.getCommonName());

        for (SerialIO port : openSerialPorts.values()) {
            port.close();
        }

        for (Device device : openDevices.values()) {
            device.close();
        }

        stopListening();
    }

}
