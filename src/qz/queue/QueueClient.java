package qz.queue;

import org.codehaus.jettison.json.JSONException;
import org.codehaus.jettison.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.print.PrinterAbortException;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.ProtocolException;
import java.net.URL;
import java.util.Base64;
import java.util.Iterator;

import javax.net.ssl.HttpsURLConnection;

import qz.printer.PrintOptions;
import qz.printer.PrintOutput;
import qz.printer.action.PrintProcessor;
import qz.utils.PrintingUtilities;


public class QueueClient {
    private static final Logger log = LoggerFactory.getLogger(QueueClient.class);

    private final String printerID;
    private final String httpURL;
    private final String httpAuth;

    // TODO - List of processed jobs that can't be marked as complete due to network problems

    public QueueClient(String printerID, String httpURL, String httpCredentials) {
        this.printerID = printerID;

        if (!httpURL.endsWith("/")) {
            httpURL += "/";
        }

        this.httpURL = httpURL;

        if (httpCredentials != null) {
            httpAuth = new String(Base64.getEncoder().encode(httpCredentials.getBytes()));
        } else {
            httpAuth = null;
        }
    }

    private boolean print(JSONObject job) throws JSONException {
        // TODO - Find a way to pass job listener (addPrintJobListener)
        PrintProcessor processor = PrintingUtilities.getPrintProcessor(job.getJSONArray("data"));
        log.debug("Using {} to print", processor.getClass().getName());
        boolean success = true;

        try {
            PrintOutput output = new PrintOutput(job.optJSONObject("printer"));
            PrintOptions options = new PrintOptions(job.optJSONObject("options"), output);

            processor.parseData(job.getJSONArray("data"), options);
            processor.print(output, options);
            log.info("Printing complete");
        }
        catch(PrinterAbortException e) {
            success = false;
            log.warn("Printing cancelled");
        }
        catch(Exception e) {
            success = false;
            log.error("Failed to print", e);
        }
        finally {
            PrintingUtilities.releasePrintProcessor(processor);
        }

        return success;
    }

    private String httpRequest(String method, String urlSuffix) {
        return httpRequest(method, urlSuffix, null, false);
    }

    private String httpRequest(String method, String urlSuffix, Boolean shortTimeout) {
        return httpRequest(method, urlSuffix, null, shortTimeout);
    }

    private String httpRequest(String method, String urlSuffix, String data) {
        return httpRequest(method, urlSuffix, data, false);
    }

    private String httpRequest(String method, String urlSuffix, String data, Boolean shortTimeout) {
        BufferedReader resultReader = null;

        try {
            HttpsURLConnection connection = (HttpsURLConnection) new URL(httpURL + urlSuffix).openConnection();
            try {
                connection.setRequestMethod(method);
            } catch (ProtocolException e) {
                throw new IllegalArgumentException("Invalid HTTP method");
            }
            if (data != null) {
                connection.setDoOutput(true);
            }
            if (httpAuth != null) {
                connection.setRequestProperty("Authorization", "Basic " + httpAuth);
            }

            if (shortTimeout) {
                connection.setConnectTimeout(150);
                connection.setReadTimeout(150);
            } else {
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
            }

            if (data != null) {
                OutputStreamWriter writer = new OutputStreamWriter(connection.getOutputStream());

                writer.write(data);
                writer.flush();
                writer.close();
            }

            resultReader = new BufferedReader(new InputStreamReader(connection.getInputStream()));

            String response = resultReader.readLine();
            int responseCode = connection.getResponseCode();

            if (responseCode != HttpsURLConnection.HTTP_OK) {
                log.error("Got non-200 OK response from server", responseCode);
                return null;
            }

            return response;
        } catch (IOException e) {
            // Connection error
            log.warn("Error connecting to HTTP print queue", e);
        } finally {
            if (resultReader != null) {
                try {
                    // If a connection was opened, try to close it
                    resultReader.close();
                } catch (IOException e) {
                    // Error closing the connection
                    log.debug("Failed to close HTTPS connection", e);
                }
            }
        }

        return null;
    }

    public int processClaimedQueue() {
        String claimedQueue = httpRequest("GET", printerID + "/claimed/");
        int jobCount = -1;

        if (claimedQueue == null) {
            log.error("Failed to retrieve list of claimed print jobs");
            return jobCount;
        }

        try {
            JSONObject json = new JSONObject(claimedQueue);
            jobCount = json.length();

            if (jobCount> 0) {
                Iterator<?> keys = json.keys();

                while (keys.hasNext()) {
                    String key = (String) keys.next();

                    try {
                        JSONObject printJob = json.getJSONObject(key);
                        boolean printed = print(printJob);
                        // TODO - Retry logic if marking the job complete fails
                        if (printed) {
                            markJobComplete(key);
                        } else {
                            markJobFailed(key);
                        }
                        // TODO - Failure listener events
                    } catch(JSONException e) {
                        log.error("Bad JSON: {}", e.getMessage());
                    }
                }
            } else {
                log.debug("Claimed queue is empty");
            }
        }
        catch(JSONException e) {
            log.error("Bad JSON: {}", e.getMessage());
        }

        return jobCount;
    }

    public void claimAndProcessJobs(int maxClaim) {
        String jobs = httpRequest("POST", printerID + "/claim/", "&max_num=" + maxClaim);

        if (jobs == null) {
            log.error("Failed to claim print jobs");
            return;
        }

        try {
            JSONObject json = new JSONObject(jobs);

            if (json.length() > 0) {
                Iterator<?> keys = json.keys();

                while (keys.hasNext()) {
                    String key = (String) keys.next();

                    try {
                        JSONObject printJob = json.getJSONObject(key);
                        print(printJob);
                        // TODO - Retry logic if marking the job complete fails
                        markJobComplete(key);
                        // TODO - Failure
                    } catch(JSONException e) {
                        log.error("Bad JSON: {}", e.getMessage());
                    }
                }
            } else {
                log.debug("No queue jobs returned when claiming");
            }
        }
        catch(JSONException e) {
            log.error("Bad JSON: {}", e.getMessage());
        }
    }

    public void releaseClaimedQueue() {
        String response = httpRequest("DELETE", printerID + "/claimed/", true);

        if (response == null) {
            log.error("Failed to release list of claimed print jobs");
        }
    }

    private void markJobComplete(String jobID) {
        String response = httpRequest("POST", "complete/", "job_id=" + jobID);

        if (response == null) {
            log.error("Failed to mark print job as complete");
        }
    }

    private void markJobFailed(String jobID) {
        String response = httpRequest("POST", "fail/", "job_id=" + jobID);

        if (response == null) {
            log.error("Failed to mark print job as failed");
        }
    }
}
