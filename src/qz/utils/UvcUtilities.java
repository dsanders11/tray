package qz.utils;

import org.apache.commons.lang3.SystemUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import qz.communication.DeviceException;
import qz.communication.DeviceIO;
import qz.communication.UvcControl;
import qz.ws.PrintSocketClient;
import qz.ws.SocketConnection;
import qz.ws.StreamEvent;

import javax.usb.*;
import javax.usb.util.UsbUtil;

import org.bridj.Pointer;

import org.openpnp.libuvc4j.UvcLibrary;
import org.openpnp.libuvc4j.UvcLibrary.uvc_context;
import org.openpnp.libuvc4j.UvcLibrary.uvc_device;
import org.openpnp.libuvc4j.UvcLibrary.uvc_error;
import org.openpnp.libuvc4j.UvcLibrary.uvc_device_handle;
import org.openpnp.libuvc4j.UvcLibrary.uvc_req_code;

import org.openpnp.libuvc4j.uvc_device_descriptor;

public class UvcUtilities {

    private static final Logger log = LoggerFactory.getLogger(UvcUtilities.class);

    public static Short hexToShort(String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }

        if (hex.startsWith("0x")) { hex = hex.substring(2); }
        return (short)Integer.parseInt(hex, 16);
    }

    public static Byte hexToByte(String hex) {
        if (hex == null || hex.isEmpty()) {
            return null;
        }

        if (hex.startsWith("0x")) { hex = hex.substring(2); }
        return (byte)Integer.parseInt(hex, 16);
    }

    public static String getString(Pointer<Byte> p) {
        if (p == null) return null;

        return p.getCString();
    }

    public static Pointer<Byte> setString(String s) {
        if (s == null) return null;

        return Pointer.pointerToCString(s);
    }

    public static Boolean isSupported() {
        return SystemUtils.IS_OS_MAC_OSX || SystemUtils.IS_OS_LINUX;
    }

    public static Pointer<Pointer<Pointer<uvc_device>>> getUvcDevices(Pointer<Pointer<uvc_context>> ctx) throws DeviceException {
        Pointer<Pointer<Pointer<uvc_device>>> deviceList = Pointer.allocatePointerPointer(uvc_device.class);

        if (UvcLibrary.uvc_get_device_list(ctx.get(), deviceList) != uvc_error.UVC_SUCCESS) {
            throw new DeviceException("Failed to get list of UVC devices");
        }

        return deviceList;
    }

    public static JSONArray getUvcDevicesJSON() throws DeviceException, JSONException {
        Pointer<Pointer<uvc_context>> ctx = Pointer.allocatePointer(uvc_context.class);

        if (UvcLibrary.uvc_init(ctx, null) != uvc_error.UVC_SUCCESS) {
            throw new DeviceException("Failed to initialize UVC library");
        }

        Pointer<Pointer<Pointer<uvc_device>>> devices = getUvcDevices(ctx);
        JSONArray deviceJSON = new JSONArray();

        for (Pointer<uvc_device> device : devices.get()) {
            if (device == null) break;

            Pointer<Pointer<uvc_device_descriptor>> desc = Pointer.allocatePointer(uvc_device_descriptor.class);

            if (UvcLibrary.uvc_get_device_descriptor(device, desc) != uvc_error.UVC_SUCCESS) {
                throw new DeviceException("Failed to get UVC device descriptor");
            }

            JSONObject descJSON = new JSONObject();
            descJSON.put("vendorId", "0x" + UsbUtil.toHexString(desc.get().get().idVendor()));
            descJSON.put("productId", "0x" + UsbUtil.toHexString(desc.get().get().idProduct()));
            descJSON.put("serialNum", getString(desc.get().get().serialNumber()));

            deviceJSON.put(descJSON);

            UvcLibrary.uvc_free_device_descriptor(desc.get());
        }

        UvcLibrary.uvc_free_device_list(devices.get(), (byte) 1);
        UvcLibrary.uvc_exit(ctx.get());

        return deviceJSON;
    }

    public static JSONObject getUvcDeviceControlJSON(Pointer<Pointer<uvc_device_handle>> handle, byte wIndex, UvcControl ctrl) throws DeviceException, JSONException {
        JSONObject controlJSON = new JSONObject();

        controlJSON.put("name", ctrl.getName());

        byte ctrlSelector = ctrl.getCtrlSelector();
        int wLength = ctrl.wLength();
        Pointer<?> result = ctrl.allocateDataPointer(wLength);

        // TODO - Better error checking

        UvcLibrary.uvc_get_ctrl(handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_CUR);
        controlJSON.put("current", ctrl.getControlData(result).get());

        UvcLibrary.uvc_get_ctrl(handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_DEF);
        controlJSON.put("default", ctrl.getControlData(result).get());

        UvcLibrary.uvc_get_ctrl(handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_MAX);
        controlJSON.put("max", ctrl.getControlData(result).get());

        UvcLibrary.uvc_get_ctrl(handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_MIN);
        controlJSON.put("min", ctrl.getControlData(result).get());

        UvcLibrary.uvc_get_ctrl(handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_RES);
        controlJSON.put("resolution", ctrl.getControlData(result).get());

        result.release();

        return controlJSON;
    }

    public static Pointer<uvc_device> findDevice(Pointer<Pointer<uvc_context>> ctx, Short vendorId, Short productId, String serialNum) throws DeviceException {
        // TODO - Allow any combination except all null
        if (vendorId == null) {
            throw new IllegalArgumentException("Vendor ID cannot be null");
        }
        if (productId == null) {
            throw new IllegalArgumentException("Product ID cannot be null");
        }

        Pointer<Pointer<uvc_device>> device = Pointer.allocatePointer(uvc_device.class);
        Pointer<Byte> serialNumCString = null;

        if (!serialNum.isEmpty()) {
            serialNumCString = setString(serialNum);
        }

        if (UvcLibrary.uvc_find_device(ctx.get(), device, vendorId, productId, serialNumCString) != uvc_error.UVC_SUCCESS) {
            serialNumCString.release();

            throw new DeviceException("Failed to find UVC device");
        }

        if (serialNumCString != null) {
            serialNumCString.release();
        }

        return device.get();
    }
}
