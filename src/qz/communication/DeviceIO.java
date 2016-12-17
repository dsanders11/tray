package qz.communication;

public interface DeviceIO extends Device {

    void setStreaming(boolean streaming);

    boolean isStreaming();


    byte[] readData(int responseSize, Byte exchangeConfig) throws DeviceException;

    void sendData(byte[] data, Byte exchangeConfig) throws DeviceException;

}
