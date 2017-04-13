package qz.ws;

import jssc.SerialPortException;
import org.apache.commons.codec.binary.StringUtils;
import org.apache.commons.io.IOUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.annotations.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.auth.Certificate;
import qz.common.Constants;
import qz.common.TrayManager;
import qz.communication.*;
import qz.printer.PrintServiceMatcher;
import qz.utils.*;

import org.dswc.Webcam;

import javax.print.PrintServiceLookup;
import javax.security.cert.CertificateParsingException;
import javax.usb.util.UsbUtil;
import java.io.IOException;
import java.io.Reader;
import java.util.HashMap;
import java.util.concurrent.Semaphore;


@WebSocket
public class PrintSocketClient {

    private static final Logger log = LoggerFactory.getLogger(PrintSocketClient.class);

    private final TrayManager trayManager = PrintSocketServer.getTrayManager();
    private static final Semaphore dialogAvailable = new Semaphore(1, true);

    //websocket port -> Connection
    private static final HashMap<Integer,SocketConnection> openConnections = new HashMap<>();

    private enum Method {
        PRINTERS_GET_DEFAULT("printers.getDefault", true, "access connected printers"),
        PRINTERS_FIND("printers.find", true, "access connected printers"),
        PRINT("print", true, "print to %s"),

        SERIAL_FIND_PORTS("serial.findPorts", true, "access serial ports"),
        SERIAL_OPEN_PORT("serial.openPort", true, "open a serial port"),
        SERIAL_SEND_DATA("serial.sendData", true, "send data over a serial port"),
        SERIAL_CLOSE_PORT("serial.closePort", true, "close a serial port"),

        USB_LIST_DEVICES("usb.listDevices", true, "access USB devices"),
        USB_LIST_INTERFACES("usb.listInterfaces", true, "access USB devices"),
        USB_LIST_ENDPOINTS("usb.listEndpoints", true, "access USB devices"),
        USB_CLAIM_DEVICE("usb.claimDevice", true, "claim a USB device"),
        USB_CLAIMED("usb.isClaimed", false, "check USB claim status"),
        USB_SEND_DATA("usb.sendData", true, "use a USB device"),
        USB_READ_DATA("usb.readData", true, "use a USB device"),
        USB_OPEN_STREAM("usb.openStream", true, "use a USB device"),
        USB_CLOSE_STREAM("usb.closeStream", false, "use a USB device"),
        USB_RELEASE_DEVICE("usb.releaseDevice", false, "release a USB device"),

        HID_LIST_DEVICES("hid.listDevices", true, "access USB devices"),
        HID_START_LISTENING("hid.startListening", true, "listen for USB devices"),
        HID_STOP_LISTENING("hid.stopListening", false),
        HID_CLAIM_DEVICE("hid.claimDevice", true, "claim a USB device"),
        HID_CLAIMED("hid.isClaimed", false, "check USB claim status"),
        HID_SEND_DATA("hid.sendData", true, "use a USB device"),
        HID_READ_DATA("hid.readData", true, "use a USB device"),
        HID_OPEN_STREAM("hid.openStream", true, "use a USB device"),
        HID_CLOSE_STREAM("hid.closeStream", false, "use a USB device"),
        HID_RELEASE_DEVICE("hid.releaseDevice", false, "release a USB device"),

        DSWC_IS_SUPPORTED("dswc.isSupported", true, "check DSWC support"),
        DSWC_LIST_WEBCAMS("dswc.listWebcams", true, "access DSWC webcams"),
        DSWC_LIST_CONTROLS("dswc.listControls", true, "access DSWC webcam"),
        DSWC_GET_CONTROL("dswc.getControl", true, "get DSWC control"),
        DSWC_SET_CONTROL("dswc.setControl", true, "set DSWC control"),

        UVC_IS_SUPPORTED("uvc.isSupported", true, "check UVC support"),
        UVC_LIST_DEVICES("uvc.listDevices", true, "access UVC devices"),
        UVC_LIST_CONTROLS("uvc.listControls", true, "access UVC devices"),
        UVC_OPEN_DEVICE("uvc.openDevice", true, "open a UVC device"),
        UVC_OPENED("uvc.isOpen", true, "check UVC device open status"),
        UVC_CLOSE_DEVICE("uvc.closeDevice", true, "close a UVC device"),
        UVC_GET_CONTROL("uvc.getControl", true, "get UVC control"),
        UVC_SET_CONTROL("uvc.setControl", true, "set UVC control"),

        WEBSOCKET_GET_NETWORK_INFO("websocket.getNetworkInfo", true),
        GET_VERSION("getVersion", false),

        INVALID("", false);


        private String callName;
        private String dialogPrompt;
        private boolean dialogShown;

        Method(String callName, boolean dialogShown) {
            this(callName, dialogShown, "access local resources");
        }

        Method(String callName, boolean dialogShown, String dialogPrompt) {
            this.callName = callName;

            this.dialogShown = dialogShown;
            this.dialogPrompt = dialogPrompt;
        }

        public boolean isDialogShown() {
            return dialogShown;
        }

        public String getDialogPrompt() {
            return dialogPrompt;
        }

        public static Method findFromCall(String call) {
            for(Method m : Method.values()) {
                if (m.callName.equals(call)) {
                    return m;
                }
            }

            return INVALID;
        }
    }


    @OnWebSocketConnect
    public void onConnect(Session session) {
        log.info("Connection opened from {} on socket port {}", session.getRemoteAddress(), session.getLocalAddress().getPort());
        trayManager.displayInfoMessage("Client connected");

        //new connections are unknown until they send a proper certificate
        openConnections.put(session.getRemoteAddress().getPort(), new SocketConnection(Certificate.UNKNOWN));
    }

    @OnWebSocketClose
    public void onClose(Session session, int closeCode, String reason) {
        log.info("Connection closed: {} - {}", closeCode, reason);
        trayManager.displayInfoMessage("Client disconnected");

        Integer port = session.getRemoteAddress().getPort();
        SocketConnection closed = openConnections.remove(port);
        if (closed != null) {
            try {
                closed.disconnect();
            }
            catch(Exception e) {
                log.error("Failed to close communication channel", e);
            }
        }
    }

    @OnWebSocketError
    public void onError(Session session, Throwable error) {
        log.error("Connection error", error);
        trayManager.displayErrorMessage(error.getMessage());
    }

    @OnWebSocketMessage
    public void onMessage(Session session, Reader reader) throws IOException {
        String message = IOUtils.toString(reader);

        if (message == null || message.isEmpty()) {
            sendError(session, null, "Message is empty");
            return;
        }
        if (Constants.PROBE_REQUEST.equals(message)) {
            try { session.getRemote().sendString(Constants.PROBE_RESPONSE); } catch(Exception ignore) {}
            log.warn("Second instance of {} likely detected, asking it to close", Constants.ABOUT_TITLE);
            return;
        }
        if ("ping".equals(message)) { return; } //keep-alive call / no need to process

        String UID = null;
        try {
            log.debug("Message: {}", message);
            JSONObject json = new JSONObject(message);
            UID = json.optString("uid");

            Integer connectionPort = session.getRemoteAddress().getPort();
            SocketConnection connection = openConnections.get(connectionPort);
            Certificate certificate = connection.getCertificate();

            //if sent a certificate use that instead for this connection
            if (json.has("certificate")) {
                try {
                    certificate = new Certificate(json.optString("certificate"));

                    connection.setCertificate(certificate);
                    log.debug("Received new certificate from connection through {}", connectionPort);
                }
                catch(CertificateParsingException ignore) {}

                if (allowedFromDialog(certificate, "connect to QZ")) {
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Connection blocked by client");
                    session.disconnect();
                }

                return; //this is a setup call, so no further processing is needed
            }

            //check request signature
            if (certificate != Certificate.UNKNOWN) {
                if (json.optLong("timestamp") + Constants.VALID_SIGNING_PERIOD < System.currentTimeMillis()
                        || json.optLong("timestamp") - Constants.VALID_SIGNING_PERIOD > System.currentTimeMillis()) {
                    //bad timestamps use the expired certificate
                    log.warn("Expired signature on request");
                    Certificate.EXPIRED.adjustStaticCertificate(certificate);
                    certificate = Certificate.EXPIRED;
                } else if (json.isNull("signature") || !validSignature(certificate, json)) {
                    //bad signatures use the unsigned certificate
                    log.warn("Bad signature on request");
                    Certificate.UNSIGNED.adjustStaticCertificate(certificate);
                    certificate = Certificate.UNSIGNED;
                } else {
                    log.trace("Valid signature from {}", certificate.getCommonName());
                }
            }

            processMessage(session, json, connection, certificate);
        }
        catch(JSONException e) {
            log.error("Bad JSON: {}", e.getMessage());
            sendError(session, UID, e);
        }
        catch(Exception e) {
            log.error("Problem processing message", e);
            sendError(session, UID, e);
        }
    }

    private boolean validSignature(Certificate certificate, JSONObject message) throws JSONException {
        JSONObject copy = new JSONObject(message, new String[] {"call", "params", "timestamp"});
        String signature = message.optString("signature");

        return certificate.isSignatureValid(signature, copy.toString().replaceAll("\\\\/", "/"));
    }

    /**
     * Determine which method was called from web API
     *
     * @param session WebSocket session
     * @param json    JSON received from web API
     */
    private void processMessage(Session session, JSONObject json, SocketConnection connection, Certificate shownCertificate) throws JSONException, SerialPortException, DeviceException {
        String UID = json.optString("uid");
        Method call = Method.findFromCall(json.optString("call"));
        JSONObject params = json.optJSONObject("params");
        if (params == null) { params = new JSONObject(); }

        if (call == Method.INVALID && (UID == null || UID.isEmpty())) {
            //incorrect message format, likely incompatible qz version
            session.close(4003, "Connected to incompatible QZ Tray version");
            return;
        }

        String prompt = call.getDialogPrompt();
        if (call == Method.PRINT) {
            //special formatting for print dialogs
            JSONObject pr = params.optJSONObject("printer");
            if (pr != null) {
                prompt = String.format(prompt, pr.optString("name", pr.optString("file", pr.optString("host", "an undefined location"))));
            } else {
                sendError(session, UID, "A printer must be specified before printing");
                return;
            }
        }

        if (call.isDialogShown() && !allowedFromDialog(shownCertificate, prompt)) {
            sendError(session, UID, "Request blocked");
            return;
        }

        // used in usb calls
        Short vendorId = UsbUtilities.hexToShort(params.optString("vendorId"));
        Short productId = UsbUtilities.hexToShort(params.optString("productId"));

        // used in uvc calls
        String serialNum = params.optString("serialNum");


        //call appropriate methods
        switch(call) {
            case DSWC_IS_SUPPORTED:
                sendResult(session, UID, DswcUtilities.isSupported());
                break;

            case UVC_IS_SUPPORTED:
                sendResult(session, UID, UvcUtilities.isSupported());
                break;

            case PRINTERS_GET_DEFAULT:
                sendResult(session, UID, PrintServiceLookup.lookupDefaultPrintService().getName());
                break;
            case PRINTERS_FIND:
                if (params.has("query")) {
                    String name = PrintServiceMatcher.getPrinterJSON(params.getString("query"));
                    if (name != null) {
                        sendResult(session, UID, name);
                    } else {
                        sendError(session, UID, "Specified printer could not be found.");
                    }
                } else {
                    sendResult(session, UID, PrintServiceMatcher.getPrintersJSON());
                }
                break;

            case PRINT:
                PrintingUtilities.processPrintRequest(session, UID, params);
                break;

            case SERIAL_FIND_PORTS:
                sendResult(session, UID, SerialUtilities.getSerialPortsJSON());
                break;
            case SERIAL_OPEN_PORT:
                SerialUtilities.setupSerialPort(session, UID, connection, params);
                break;
            case SERIAL_SEND_DATA: {
                SerialProperties props = new SerialProperties(params.optJSONObject("properties"));
                SerialIO serial = connection.getSerialPort(params.optString("port"));
                if (serial != null) {
                    serial.sendData(props, params.optString("data"));
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("Serial port [%s] must be opened first.", params.optString("port")));
                }
                break;
            }
            case SERIAL_CLOSE_PORT: {
                SerialIO serial = connection.getSerialPort(params.optString("port"));
                if (serial != null) {
                    serial.close();
                    connection.removeSerialPort(params.optString("port"));
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("Serial port [%s] is not open.", params.optString("port")));
                }
                break;
            }

            case USB_LIST_DEVICES:
                sendResult(session, UID, UsbUtilities.getUsbDevicesJSON(params.getBoolean("includeHubs")));
                break;
            case USB_LIST_INTERFACES:
                sendResult(session, UID, UsbUtilities.getDeviceInterfacesJSON(vendorId, productId));
                break;
            case USB_LIST_ENDPOINTS:
                sendResult(session, UID, UsbUtilities.getInterfaceEndpointsJSON(vendorId, productId, UsbUtilities.hexToByte(params.getString("interface"))));
                break;
            case UVC_LIST_DEVICES:
                sendResult(session, UID, UvcUtilities.getUvcDevicesJSON());
                break;
            case UVC_LIST_CONTROLS: {
                Device device = connection.getDevice(vendorId, productId, serialNum);
                if (device != null && device.isOpen()) {
                    UvcDevice uvc = (UvcDevice) device;

                    sendResult(session, UID, uvc.listControls());
                } else {
                    if (!serialNum.isEmpty()) {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s s:%s] must be opened first.", params.opt("vendorId"), params.opt("productId"), serialNum));
                    } else {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s] must be opened first.", params.opt("vendorId"), params.opt("productId")));
                    }
                }
                break;
            }

            case HID_LIST_DEVICES:
                if (SystemUtilities.isWindows()) {
                    sendResult(session, UID, PJHA_HidUtilities.getHidDevicesJSON());
                } else {
                    sendResult(session, UID, H4J_HidUtilities.getHidDevicesJSON());
                }
                break;
            case DSWC_LIST_WEBCAMS:
                sendResult(session, UID, DswcUtilities.getDswcWebcamsJSON());
                break;
            case DSWC_LIST_CONTROLS: {
                Webcam webcam = DswcUtilities.getWebcam(params.getString("devicePath"));

                if (webcam == null) {
                    sendError(session, UID, "DSWC webcam not found");
                } else {
                    sendResult(session, UID, DswcUtilities.listDswcControls(webcam));
                }
                break;
            }
            case HID_START_LISTENING:
                if (!connection.isListening()) {
                    if (SystemUtilities.isWindows()) {
                        connection.startListening(new PJHA_HidListener(session));
                    } else {
                        connection.startListening(new H4J_HidListener(session));
                    }
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Already listening HID device events");
                }
                break;
            case HID_STOP_LISTENING:
                if (connection.isListening()) {
                    connection.stopListening();
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, "Not already listening HID device events");
                }
                break;

            case USB_CLAIM_DEVICE:
            case HID_CLAIM_DEVICE: {
                if (connection.getDevice(vendorId, productId, null) == null) {
                    DeviceIO device;
                    if (call == Method.USB_CLAIM_DEVICE) {
                        device = new UsbIO(vendorId, productId, UsbUtilities.hexToByte(params.optString("interface")));
                    } else {
                        if (SystemUtilities.isWindows()) {
                            device = new PJHA_HidIO(vendorId, productId);
                        } else {
                            device = new H4J_HidIO(vendorId, productId);
                        }
                    }

                    if (session.isOpen()) {
                        connection.openDevice(device, vendorId, productId);
                    }

                    if (device.isOpen()) {
                        connection.addDevice(vendorId, productId, null, device);
                        sendResult(session, UID, null);
                    } else {
                        sendError(session, UID, "Failed to open connection to device");
                    }
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] is already claimed.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_CLAIMED:
            case HID_CLAIMED: {
                sendResult(session, UID, connection.getDevice(vendorId, productId, null) != null);
                break;
            }
            case USB_SEND_DATA:
            case HID_SEND_DATA: {
                Device device = connection.getDevice(vendorId, productId, null);
                if (device != null) {
                    DeviceIO usb = (DeviceIO) device;
                    usb.sendData(StringUtils.getBytesUtf8(params.optString("data")), UsbUtilities.hexToByte(params.optString("endpoint")));
                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] must be claimed first.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_READ_DATA:
            case HID_READ_DATA: {
                Device device = connection.getDevice(vendorId, productId, null);
                if (device != null) {
                    DeviceIO usb = (DeviceIO) device;
                    byte[] response = usb.readData(params.optInt("responseSize"), UsbUtilities.hexToByte(params.optString("endpoint")));
                    JSONArray hex = new JSONArray();
                    for(byte b : response) {
                        hex.put(UsbUtil.toHexString(b));
                    }
                    sendResult(session, UID, hex);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] must be claimed first.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_OPEN_STREAM:
            case HID_OPEN_STREAM: {
                StreamEvent.Stream stream = (call == Method.USB_OPEN_STREAM? StreamEvent.Stream.USB:StreamEvent.Stream.HID);
                UsbUtilities.setupUsbStream(session, UID, connection, params, stream);
                break;
            }
            case USB_CLOSE_STREAM:
            case HID_CLOSE_STREAM: {
                Device device = connection.getDevice(vendorId, productId, null);
                if (device != null) {
                    DeviceIO usb = (DeviceIO) device;
                    if (usb.isStreaming()) {
                        usb.setStreaming(false);
                        sendResult(session, UID, null);
                    } else {
                        sendError(session, UID, String.format("USB Device [v:%s p:%s] is not streaming data.", params.opt("vendorId"), params.opt("productId")));
                    }
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] must be claimed first.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }
            case USB_RELEASE_DEVICE:
            case HID_RELEASE_DEVICE: {
                Device device = connection.getDevice(vendorId, productId, null);
                if (device != null) {
                    device.close();
                    connection.removeDevice(vendorId, productId, null);

                    sendResult(session, UID, null);
                } else {
                    sendError(session, UID, String.format("USB Device [v:%s p:%s] is not claimed.", params.opt("vendorId"), params.opt("productId")));
                }

                break;
            }

            case DSWC_GET_CONTROL: {
                Webcam webcam = DswcUtilities.getWebcam(params.getString("devicePath"));

                if (webcam == null) {
                    sendError(session, UID, "DSWC webcam not found");
                } else {
                    JSONObject controlJSON = new JSONObject();
                    switch (params.getString("control")) {
                        case "Zoom":
                            controlJSON.put("value", webcam.GetZoom());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Focus":
                            controlJSON.put("value", webcam.GetFocus());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Pan":
                            controlJSON.put("value", webcam.GetPan());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Tilt":
                            controlJSON.put("value", webcam.GetTilt());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Roll":
                            controlJSON.put("value", webcam.GetRoll());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Exposure":
                            controlJSON.put("value", webcam.GetExposure());
                            sendResult(session, UID, controlJSON);
                            break;
                        case "Iris":
                            controlJSON.put("value", webcam.GetIris());
                            sendResult(session, UID, controlJSON);
                            break;
                        default:
                            sendError(session, UID, "Unknown or unsupported DSWC control");
                            break;
                    }
                }
                break;
            }

            case UVC_OPEN_DEVICE: {
                if (connection.getDevice(vendorId, productId, serialNum) == null) {
                    Device device = new UvcDevice(vendorId, productId, serialNum);
                    device.open();
                    if (device.isOpen()) {
                        connection.addDevice(vendorId, productId, serialNum, device);
                        sendResult(session, UID, null);
                    } else {
                        sendError(session, UID, "Failed to open connection to device");
                    }
                } else {
                    sendError(session, UID, String.format("UVC Device [v:%s p:%s s:%s] is already open.", params.opt("vendorId"), params.opt("productId"), params.opt("serialNum")));
                }

                break;
            }

            case UVC_CLOSE_DEVICE: {
                Device device = connection.getDevice(vendorId, productId, serialNum);
                if (device != null && device.isOpen()) {
                    device.close();

                    connection.removeDevice(vendorId, productId, serialNum);
                    sendResult(session, UID, null);
                } else {
                    if (!serialNum.isEmpty()) {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s s:%s] is not open.", params.opt("vendorId"), params.opt("productId"), serialNum));
                    } else {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s] is not open.", params.opt("vendorId"), params.opt("productId")));
                    }
                }
                break;
            }

            case UVC_OPENED: {
                Device device = connection.getDevice(vendorId, productId, serialNum);

                if (device != null) {
                    sendResult(session, UID, device.isOpen());
                } else {
                    sendResult(session, UID, false);
                }
                break;
            }

            case UVC_GET_CONTROL: {
                Device device = connection.getDevice(vendorId, productId, serialNum);
                if (device != null) {
                    UvcDevice uvc = (UvcDevice) device;

                    sendResult(session, UID, uvc.getControl(params.optString("control")));
                } else {
                    if (!serialNum.isEmpty()) {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s s:%s] must be opened first.", params.opt("vendorId"), params.opt("productId"), serialNum));
                    } else {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s] must be opened first.", params.opt("vendorId"), params.opt("productId")));
                    }
                }
                break;
            }

            case DSWC_SET_CONTROL: {
                Webcam webcam = DswcUtilities.getWebcam(params.getString("devicePath"));
                String control = params.getString("control");

                if (webcam == null) {
                    sendError(session, UID, "DSWC webcam not found");
                } else {
                    try {
                        // Check for auto first, it's a special case value
                        if (params.getString("value").equals("auto")) {
                            if (control.equals("Focus")) {
                                webcam.SetFocusAuto();
                                sendResult(session, UID, null);
                                break;
                            } else if (control.equals("Exposure")) {
                                webcam.SetExposureAuto();
                                sendResult(session, UID, null);
                                break;
                            }
                        }
                    } catch (JSONException ignore) {}

                    int value = params.getInt("value");

                    switch (control) {
                        case "Zoom":
                            webcam.SetZoom(value);
                            break;
                        case "Focus":
                            webcam.SetFocus(value);
                            break;
                        case "Pan":
                            webcam.SetPan(value);
                            break;
                        case "Tilt":
                            webcam.SetTilt(value);
                            break;
                        case "Roll":
                            webcam.SetRoll(value);
                            break;
                        case "Exposure":
                            webcam.SetExposure(value);
                            break;
                        case "Iris":
                            webcam.SetIris(value);
                            break;
                        default:
                            sendError(session, UID, "Unknown or unsupported DSWC control");
                            return;
                    }

                    sendResult(session, UID, null);
                }
                break;
            }

            case UVC_SET_CONTROL: {
                Device device = connection.getDevice(vendorId, productId, serialNum);
                if (device != null) {
                    UvcDevice uvc = (UvcDevice) device;

                    uvc.setControl(params.optString("control"), params.optString("value"));
                    sendResult(session, UID, null);
                } else {
                    if (!serialNum.isEmpty()) {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s s:%s] must be opened first.", params.opt("vendorId"), params.opt("productId"), serialNum));
                    } else {
                        sendError(session, UID, String.format("UVC Device [v:%s p:%s] must be opened first.", params.opt("vendorId"), params.opt("productId")));
                    }
                }
                break;
            }

            case WEBSOCKET_GET_NETWORK_INFO:
                sendResult(session, UID, NetworkUtilities.getNetworkJSON(params.optString("hostname", "google.com"), params.optInt("port", 443)));
                break;
            case GET_VERSION:
                sendResult(session, UID, Constants.VERSION);
                break;

            case INVALID: default:
                sendError(session, UID, "Invalid function call: " + json.optString("call", "NONE"));
                break;
        }
    }

    private boolean allowedFromDialog(Certificate certificate, String prompt) {
        //wait until previous prompts are closed
        try { dialogAvailable.acquire(); } catch(InterruptedException ignore) {}

        //prompt user for access
        boolean allowed = trayManager.showGatewayDialog(certificate, prompt);

        dialogAvailable.release();

        return allowed;
    }


    /**
     * Send JSON reply to web API for call {@code messageUID}
     *
     * @param session     WebSocket session
     * @param messageUID  ID of call from web API
     * @param returnValue Return value of method call, can be {@code null}
     */
    public static void sendResult(Session session, String messageUID, Object returnValue) {
        try {
            JSONObject reply = new JSONObject();
            reply.put("uid", messageUID);
            reply.put("result", returnValue);
            send(session, reply);
        }
        catch(JSONException e) {
            log.error("Send result failed", e);
        }
    }

    /**
     * Send JSON error reply to web API for call {@code messageUID}
     *
     * @param session    WebSocket session
     * @param messageUID ID of call from web API
     * @param ex         Exception to get error message from
     */
    public static void sendError(Session session, String messageUID, Exception ex) {
        String message = ex.getMessage();
        if (message == null) { message = ex.getClass().getSimpleName(); }

        sendError(session, messageUID, message);
    }

    /**
     * Send JSON error reply to web API for call {@code messageUID}
     *
     * @param session    WebSocket session
     * @param messageUID ID of call from web API
     * @param errorMsg   Error from method call
     */
    public static void sendError(Session session, String messageUID, String errorMsg) {
        try {
            JSONObject reply = new JSONObject();
            reply.putOpt("uid", messageUID);
            reply.put("error", errorMsg);
            send(session, reply);
        }
        catch(JSONException e) {
            log.error("Send error failed", e);
        }
    }

    /**
     * Send JSON data to web API, to be retrieved by callbacks.
     * Used for data sent apart from API calls, since UID's correspond to a single response.
     *
     * @param session WebSocket session
     * @param event   StreamEvent with data to send down to web API
     */
    public static void sendStream(Session session, StreamEvent event) {
        try {
            JSONObject stream = new JSONObject();
            stream.put("type", event.getStreamType());
            stream.put("event", event.toJSON());
            send(session, stream);
        }
        catch(JSONException e) {
            log.error("Send stream failed", e);
        }
    }

    /**
     * Raw send method for replies
     *
     * @param session WebSocket session
     * @param reply   JSON Object of reply to web API
     */
    private static synchronized void send(Session session, JSONObject reply) {
        try {
            session.getRemote().sendString(reply.toString());
        }
        catch(Exception e) {
            log.error("Could not send message", e);
        }
    }

}
