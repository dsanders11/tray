package qz.communication;

import java.util.ArrayList;

import org.apache.commons.lang3.tuple.Pair;

import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;

import qz.communication.UvcControl;
import qz.utils.UvcUtilities;

import javax.usb.util.UsbUtil;

import org.bridj.IntValuedEnum;
import org.bridj.Pointer;

import org.openpnp.libuvc4j.UvcLibrary;
import org.openpnp.libuvc4j.UvcLibrary.uvc_context;
import org.openpnp.libuvc4j.UvcLibrary.uvc_device;
import org.openpnp.libuvc4j.UvcLibrary.uvc_device_handle;
import org.openpnp.libuvc4j.UvcLibrary.uvc_error;
import org.openpnp.libuvc4j.UvcLibrary.uvc_it_type;
import org.openpnp.libuvc4j.UvcLibrary.uvc_req_code;

import org.openpnp.libuvc4j.uvc_device_descriptor;
import org.openpnp.libuvc4j.uvc_input_terminal;
import org.openpnp.libuvc4j.uvc_processing_unit;

public class UvcDevice implements Device {
    private enum ProcessingUnitControl implements UvcControl {
        PU_BRIGHTNESS_CONTROL                    (0,  "Brightness",                      (byte) 0x02, 2, 0, 2),
        PU_CONTRAST_CONTROL                      (1,  "Contrast",                        (byte) 0x03, 2, 0, 2),
        PU_HUE_CONTROL                           (2,  "Hue",                             (byte) 0x06, 2, 0, 2),
        PU_SATURATION_CONTROL                    (3,  "Saturation",                      (byte) 0x07, 2, 0, 2),
        PU_SHARPNESS_CONTROL                     (4,  "Sharpness",                       (byte) 0x08, 2, 0, 2),
        PU_GAMMA_CONTROL                         (5,  "Gamma",                           (byte) 0x09, 2, 0, 2),
        PU_WHITE_BALANCE_TEMPERATURE_CONTROL     (6,  "White Balance Temperature",       (byte) 0x0A, 2, 0, 2),
        PU_WHITE_BALANCE_BLUE_COMPONENT_CONTROL  (7,  "White Balance Blue Component",    (byte) 0x0C, 4, 0, 2),
        PU_WHITE_BALANCE_RED_COMPONENT_CONTROL   (7,  "White Balance Red Component",     (byte) 0x0C, 4, 2, 2),
        PU_BACKLIGHT_COMPENSATION_CONTROL        (8,  "Backlight Compensation",          (byte) 0x01, 2, 0, 2),
        PU_GAIN_CONTROL                          (9,  "Gain",                            (byte) 0x04, 2, 0, 2),
        PU_POWER_LINE_FREQUENCY_CONTROL          (10, "Power Line Frequency",            (byte) 0x05, 1, 0, 1),
        PU_HUE_AUTO_CONTROL                      (11, "Hue, Auto",                       (byte) 0x10, 1, 0, 1),
        PU_WHITE_BALANCE_TEMPERATURE_AUTO_CONTROL(12, "White Balance Temperature, Auto", (byte) 0x0B, 1, 0, 1),
        PU_WHITE_BALANCE_COMPONENT_AUTO_CONTROL  (13, "White Balance Component, Auto",   (byte) 0x0D, 1, 0, 1),
        PU_DIGITAL_MULTIPLIER_CONTROL            (14, "Digital Multiplier",              (byte) 0x0E, 2, 0, 2),
        PU_DIGITAL_MULTIPLIER_LIMIT_CONTROL      (15, "Digital Multiplier Limit",        (byte) 0x0F, 2, 0, 2),
        PU_ANALOG_VIDEO_STANDARD_CONTROL         (16, "Analog Video Standard",           (byte) 0x11, 1, 0, 1),
        PU_ANALOG_LOCK_STATUS_CONTROL            (17, "Analog Video Lock Status",        (byte) 0x12, 1, 0, 1);

        private final long bitmapIndex;
        private final byte ctrlSelector;
        private final int wLength;
        private final int ctrlLength;
        private final int offset;
        private final String name;

        private ProcessingUnitControl(long bitmapIndex, String name, byte ctrlSelector, int wLength, int offset, int ctrlLength) {
            this.bitmapIndex = bitmapIndex;
            this.ctrlSelector = ctrlSelector;
            this.wLength = wLength;
            this.offset = offset;
            this.ctrlLength = ctrlLength;
            this.name = name;
        }

        public long getBitmapIndex() {
            return 1 << this.bitmapIndex;
        }

        public int wLength() {
            return this.wLength;
        }

        public int offset() {
            return this.offset;
        }

        public int getCtrlLength() {
            return this.ctrlLength;
        }

        public byte getCtrlSelector() {
            return this.ctrlSelector;
        }

        public String getName() {
            return this.name;
        }
    }

    private enum CameraTerminalControl implements UvcControl {
        CT_SCANNING_MODE_CONTROL         (0,  "Scanning Mode",            (byte) 0x01, 1, 0, 1),
        CT_AE_MODE_CONTROL               (1,  "Auto-Exposure Mode",       (byte) 0x02, 1, 0, 1),
        CT_AE_PRIORITY_CONTROL           (2,  "Auto-Exposure Priority",   (byte) 0x03, 1, 0, 1),
        CT_EXPOSURE_TIME_ABSOLUTE_CONTROL(3,  "Exposure Time (Absolute)", (byte) 0x04, 4, 0, 4),
        CT_EXPOSURE_TIME_RELATIVE_CONTROL(4,  "Exposure Time (Relative)", (byte) 0x05, 1, 0, 1),
        CT_FOCUS_ABSOLUTE_CONTROL        (5,  "Focus (Absolute)",         (byte) 0x06, 2, 0, 2),
        CT_FOCUS_RELATIVE_CONTROL        (6,  "Focus (Relative)",         (byte) 0x07, 2, 0, 1),
        CT_IRIS_ABSOLUTE_CONTROL         (7,  "Iris (Absolute)",          (byte) 0x09, 2, 0, 2),
        CT_IRIS_RELATIVE_CONTROL         (8,  "Iris (Relative)",          (byte) 0x0A, 1, 0, 1),
        CT_ZOOM_ABSOLUTE_CONTROL         (9,  "Zoom (Absolute)",          (byte) 0x0B, 2, 0, 2),
        // CT_ZOOM_RELATIVE_CONTROL         (10, "Zoom (Relative)",          (byte) 0x0C, 3, 0, 3),
        CT_PAN_ABSOLUTE_CONTROL          (11, "Pan (Absolute)",           (byte) 0x0D, 8, 0, 4),
        CT_TILT_ABSOLUTE_CONTROL         (11, "Tilt (Absolute)",          (byte) 0x0D, 8, 4, 4),
        // CT_PANTILT_RELATIVE_CONTROL      (12, "PanTilt (Relative)",       (byte) 0x0E, 4, 0, 4),
        CT_ROLL_ABSOLUTE_CONTROL         (13, "Roll (Absolute)",          (byte) 0x0F, 2, 0, 2),
        //CT_ROLL_RELATIVE_CONTROL         (14, "Roll (Relative)",          (byte) 0x10, 2, 0, 2),
        CT_FOCUS_AUTO_CONTROL            (17, "Focus, Auto",              (byte) 0x08, 1, 0, 1),
        CT_PRIVACY_CONTROL               (18, "Privacy",                  (byte) 0x11, 1, 0, 1);

        private final long bitmapIndex;
        private final byte ctrlSelector;
        private final int wLength;
        private final int ctrlLength;
        private final int offset;
        private final String name;

        private CameraTerminalControl(long bitmapIndex, String name, byte ctrlSelector, int wLength, int offset, int ctrlLength) {
            this.bitmapIndex = bitmapIndex;
            this.ctrlSelector = ctrlSelector;
            this.wLength = wLength;
            this.offset = offset;
            this.ctrlLength = ctrlLength;
            this.name = name;
        }

        public long getBitmapIndex() {
            return 1 << this.bitmapIndex;
        }

        public int wLength() {
            return this.wLength;
        }

        public int offset() {
            return this.offset;
        }

        public int getCtrlLength() {
            return this.ctrlLength;
        }

        public byte getCtrlSelector() {
            return this.ctrlSelector;
        }

        public String getName() {
            return this.name;
        }
    }

    private Pointer<Pointer<uvc_context>> ctx;
    private Pointer<uvc_device> device;
    private Pointer<Pointer<uvc_device_handle>> handle;

    private String vendorId;
    private String productId;
    private String serialNum;

    public UvcDevice(Short vendorId, Short productId, String serialNum) throws DeviceException {
        this.ctx = Pointer.allocatePointer(uvc_context.class);

        if (UvcLibrary.uvc_init(ctx, null) != uvc_error.UVC_SUCCESS) {
            throw new DeviceException("Failed to initialize UVC library");
        }

        this.device = UvcUtilities.findDevice(ctx, vendorId, productId, serialNum);
        this.handle = null;

        Pointer<Pointer<uvc_device_descriptor>> desc = Pointer.allocatePointer(uvc_device_descriptor.class);
        UvcLibrary.uvc_get_device_descriptor(this.device, desc);

        this.vendorId = UsbUtil.toHexString(desc.get().get().idVendor());
        this.productId = UsbUtil.toHexString(desc.get().get().idProduct());
        this.serialNum = UvcUtilities.getString(desc.get().get().serialNumber());

        UvcLibrary.uvc_free_device_descriptor(desc.get());
    }

    public void open() throws DeviceException {
        this.handle = Pointer.allocatePointer(uvc_device_handle.class);

        if (UvcLibrary.uvc_open(this.device, this.handle) != uvc_error.UVC_SUCCESS) {
            throw new DeviceException("Failed to open UVC device");
        }

        // TODO - Ideally we'd have a callback which would detect device unplug and mark it as closed
        // UvcLibrary.uvc_set_status_callback(this.handle.get(), new CloseDevice(), null);
    }

    public boolean isOpen() {
        return this.handle != null;
    }

    public String getVendorId() {
        return this.vendorId;
    }

    public String getProductId() {
        return this.productId;
    }

    public JSONObject getControl(String control) throws DeviceException, JSONException {
        JSONObject controlJSON = new JSONObject();

        Byte wIndex = null;
        UvcControl targetCtrl = null;

        for ( Pair<Byte, UvcControl> availableCtrl : this.getAvailableControls() ) {
            UvcControl ctrl = availableCtrl.getRight();

            if (ctrl.getName().equals(control)) {
                wIndex = availableCtrl.getLeft();
                targetCtrl = ctrl;
                break;
            }
        }

        if (targetCtrl == null) {
            throw new DeviceException("UVC control not found or not available");
        }

        byte ctrlSelector = targetCtrl.getCtrlSelector();
        int wLength = targetCtrl.wLength();

        Pointer<?> result = targetCtrl.allocateDataPointer(wLength);

        if (UvcLibrary.uvc_get_ctrl(this.handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_CUR) < 0) {
            throw new DeviceException("Failed to get UVC control");
        }

        // This control might be at an offset, so get data with helper method
        UvcControl.BoxedValue<?> controlData = targetCtrl.getControlData(result);

        controlJSON.put("value", controlData.get());

        result.release();

        return controlJSON;
    }

    public void setControl(String control, String value) throws DeviceException {
        Byte wIndex = null;
        UvcControl targetCtrl = null;

        for ( Pair<Byte, UvcControl> availableCtrl : this.getAvailableControls() ) {
            UvcControl ctrl = availableCtrl.getRight();

            if (ctrl.getName().equals(control)) {
                wIndex = availableCtrl.getLeft();
                targetCtrl = ctrl;
                break;
            }
        }

        if (targetCtrl == null) {
            throw new DeviceException("UVC control not found or not available");
        }

        byte ctrlSelector = targetCtrl.getCtrlSelector();
        int wLength = targetCtrl.wLength();

        // First get the current value so we can handle offsets correctly
        Pointer<?> result = targetCtrl.allocateDataPointer(wLength);

        if (UvcLibrary.uvc_get_ctrl(this.handle.get(), wIndex, ctrlSelector, result, wLength, uvc_req_code.UVC_GET_CUR) < 0) {
            throw new DeviceException("Failed to set UVC control");
        }

        // Create the data pointer using the current value and the new value
        Pointer<?> data = targetCtrl.createDataPointer(result, value);

        // Current value is no longer needed
        result.release();

        if (UvcLibrary.uvc_set_ctrl(this.handle.get(), wIndex, ctrlSelector, data, wLength) < 0) {
            throw new DeviceException("Failed to set UVC control");
        }

        // Data is no longer needed
        data.release();
    }

    private ArrayList<Pair<Byte, UvcControl>> getAvailableControls() {
        ArrayList<Pair<Byte, UvcControl>> controls = new ArrayList<Pair<Byte, UvcControl>>();

        // Processing Units
        Pointer<uvc_processing_unit> pu = UvcLibrary.uvc_get_processing_units(this.handle.get());

        while (pu != null) {
            byte wIndex = pu.get().bUnitID();
            long puControls = pu.get().bmControls();

            for (ProcessingUnitControl ctrl : ProcessingUnitControl.values()) {
                long bitmapIndex = ctrl.getBitmapIndex();

                if ((puControls & bitmapIndex) == bitmapIndex) {
                    controls.add(Pair.of(wIndex, ctrl));
                }
            }
            pu = pu.get().next();
        }

        // Input Terminals
        Pointer<uvc_input_terminal> it = UvcLibrary.uvc_get_input_terminals(this.handle.get());

        while (it != null) {
            IntValuedEnum<uvc_it_type> terminalType = it.get().wTerminalType();
            byte wIndex = it.get().bTerminalID();

            // Camera Terminal is the only type supported currently
            if (terminalType.value() == uvc_it_type.UVC_ITT_CAMERA.value()) {
                long ctControls = it.get().bmControls();

                for (CameraTerminalControl ctrl : CameraTerminalControl.values()) {
                    long bitmapIndex = ctrl.getBitmapIndex();

                    if ((ctControls & bitmapIndex) == bitmapIndex) {
                        controls.add(Pair.of(wIndex, ctrl));
                    }
                }
            }

            it = it.get().next();
        }

        return controls;
    }

    public JSONArray listControls() throws DeviceException, JSONException {
        JSONArray controlsJSON = new JSONArray();

        for ( Pair<Byte, UvcControl> availableCtrl : this.getAvailableControls() ) {
            byte wIndex = availableCtrl.getLeft();
            UvcControl ctrl = availableCtrl.getRight();

            controlsJSON.put(UvcUtilities.getUvcDeviceControlJSON(this.handle, wIndex, ctrl));
        }

        return controlsJSON;
    }

    public void close() throws DeviceException {
        UvcLibrary.uvc_close(this.handle.get());
        this.handle = null;

        // TODO - Probably need to unref this.dev and free pointer

        UvcLibrary.uvc_exit(this.ctx.get());
    }
}
