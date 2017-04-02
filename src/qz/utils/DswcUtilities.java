package qz.utils;

import java.util.ArrayList;
import java.util.HashMap;

import org.apache.commons.lang3.SystemUtils;
import org.codehaus.jettison.json.JSONArray;
import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dswc.ControlInfo;
import org.dswc.ControlType;
import org.dswc.Library;
import org.dswc.Webcam;

public class DswcUtilities {

    private static final Logger log = LoggerFactory.getLogger(DswcUtilities.class);

    private static Library dswcLibrary = null;

    private static final HashMap<String,Webcam> webcamMap = new HashMap<>();


    public static Boolean isSupported() {
        return SystemUtils.IS_OS_WINDOWS;
    }

    public static JSONArray getDswcWebcamsJSON() throws JSONException {
        JSONArray webcamsJSON = new JSONArray();

        ArrayList<Webcam> webcams = dswcLibrary.ListWebcams();

        for (Webcam webcam : webcams) {
            JSONObject webcamJSON = new JSONObject();
            webcamJSON.put("name", webcam.GetFriendlyName());
            webcamJSON.put("devicePath", webcam.GetDevicePath());

            String description = webcam.GetDescription();

            if (!description.isEmpty()) {
                webcamJSON.put("description", description);
            }

            webcam.Dispose();
            webcamsJSON.put(webcamJSON);
        }

        return webcamsJSON;
    }

    public static Webcam getWebcam(String devicePath) {
        Webcam webcam = webcamMap.get(devicePath);

        if (webcam == null) {
            webcam = dswcLibrary.GetWebcam(devicePath);

            if (webcam != null) {
                // Store so we don't have to use GetWebcam often
                webcamMap.put(devicePath, webcam);
            }
        }

        return webcam;
    }

    public static JSONArray listDswcControls(Webcam webcam) throws JSONException {
        JSONArray controlsJSON = new JSONArray();

        for (ControlInfo control : webcam.ListControls() ) {
            JSONObject controlJSON = new JSONObject();

            switch (control.Control) {
                case ZOOM:
                    controlJSON.put("name", "Zoom");
                    controlJSON.put("current", webcam.GetZoom());
                    break;

                case FOCUS:
                    controlJSON.put("name", "Focus");
                    controlJSON.put("current", webcam.GetFocus());
                    break;

                default:
                    controlJSON.put("name", "Unknown");
                    controlJSON.put("current", -1);
                    break;
            }

            controlJSON.put("default", control.Default);
            controlJSON.put("max", control.Max);
            controlJSON.put("min", control.Min);
            controlJSON.put("resolution", control.Step);

            controlsJSON.put(controlJSON);
        }

        return controlsJSON;
    }

    static {
        if (isSupported()) {
            Library.InitializeLibrary();
            dswcLibrary = new Library();
        }
    }
}
