package qz.communication;

public interface Device {

    String getVendorId();

    String getProductId();


    void open() throws DeviceException;

    boolean isOpen();

    void close() throws DeviceException;

}
