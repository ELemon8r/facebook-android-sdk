/**
 * Copyright 2012 Facebook
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.graphics.Bitmap;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.text.TextUtils;

public class Request {

    private Session session;
    private String httpMethod;
    private String graphPath;
    private Object graphObject;
    private String restMethod;
    private String batchEntryName;
    private Bundle parameters;

    private static String sessionlessRequestApplicationId;

    // Graph paths
    public static final String ME = "me";
    public static final String MY_FRIENDS = "me/friends";
    public static final String MY_PHOTOS = "me/photos";
    public static final String SEARCH = "search";

    // HTTP methods/headers
    public static final String GET_METHOD = "GET";
    public static final String POST_METHOD = "POST";
    public static final String DELETE_METHOD = "DELETE";
    private static final String USER_AGENT_HEADER = "User-Agent";
    private static final String CONTENT_TYPE_HEADER = "Content-Type";

    // Parameter names/values
    private static final String PICTURE_PARAM = "picture";
    private static final String FORMAT_PARAM = "format";
    private static final String FORMAT_JSON = "json";
    private static final String SDK_PARAM = "sdk";
    private static final String SDK_ANDROID = "android";
    private static final String ACCESS_TOKEN_PARAM = "access_token";
    private static final String BATCH_ENTRY_NAME_PARAM = "name";
    private static final String BATCH_APP_ID_PARAM = "batch_app_id";
    private static final String BATCH_RELATIVE_URL_PARAM = "relative_url";
    private static final String BATCH_METHOD_PARAM = "method";
    private static final String BATCH_PARAM = "batch";
    private static final String ATTACHMENT_FILENAME_PREFIX = "file";
    private static final String ATTACHED_FILES_PARAM = "attached_files";

    private static final String MIME_BOUNDARY = "3i2ndDfv2rTHiSisAbouNdArYfORhtTPEefj3q2f";

    public Request() {
        this(null, null, null, null);
    }

    public Request(Session session, String graphPath) {
        this(session, graphPath, null, null);
    }

    public Request(Session session, String graphPath, Bundle parameters, String httpMethod) {
        this.session = session;
        this.graphPath = graphPath;

        // This handles the null case by using the default.
        setHttpMethod(httpMethod);

        if (parameters != null) {
            this.parameters = new Bundle(parameters);
        } else {
            this.parameters = new Bundle();
        }
    }

    public static Request newPostRequest(Session session, String graphPath, Object graphObject) {
        Request request = new Request(session, graphPath, null, POST_METHOD);
        request.setGraphObject(graphObject);
        return request;
    }

    public static Request newRestRequest(Session session, String restMethod, Bundle parameters, String httpMethod) {
        return null;
    }

    public static Request newMeRequest(Session session) {
        return new Request(session, ME);
    }

    public static Request newMyFriendsRequest(Session session) {
        return new Request(session, MY_FRIENDS);
    }

    public static Request newUploadPhotoRequest(Session session, Bitmap image) {
        Bundle parameters = new Bundle(1);
        parameters.putParcelable(PICTURE_PARAM, image);

        return new Request(session, MY_PHOTOS, parameters, POST_METHOD);
    }

    public static Request newPlacesSearchRequest(Session session, Location location, int radiusInMeters,
            int resultsLimit, String searchText) {
        Validate.notNull(location, "location");

        Bundle parameters = new Bundle(5);
        parameters.putString("type", "place");
        parameters.putInt("limit", resultsLimit);
        parameters.putInt("distance", radiusInMeters);
        parameters.putString("center",
                String.format(Locale.US, "%f,%f", location.getLatitude(), location.getLongitude()));
        if (searchText != null) {
            parameters.putString("q", searchText);
        }

        return new Request(session, SEARCH, parameters, GET_METHOD);
    }

    public final Object getGraphObject() {
        return this.graphObject;
    }

    public final void setGraphObject(Object graphObject) {
        this.graphObject = graphObject;
    }

    public final String getGraphPath() {
        return this.graphPath;
    }

    public final void setGraphPath(String graphPath) {
        this.graphPath = graphPath;
    }

    public final String getHttpMethod() {
        return this.httpMethod;
    }

    public final void setHttpMethod(String httpMethod) {
        this.httpMethod = (httpMethod != null) ? httpMethod.toUpperCase() : GET_METHOD;
    }

    public final Bundle getParameters() {
        return this.parameters;
    }

    public final String getRestMethod() {
        return this.restMethod;
    }

    public final void setRestMethod(String restMethod) {
        this.restMethod = restMethod;
    }

    public final Session getSession() {
        return this.session;
    }

    public final void setSession(Session session) {
        this.session = session;
    }

    public final String getBatchEntryName() {
        return this.batchEntryName;
    }

    public final void setBatchEntryName(String batchEntryName) {
        this.batchEntryName = batchEntryName;
    }

    public static final String getSessionlessRequestApplicationId() {
        return Request.sessionlessRequestApplicationId;
    }

    public static final void setSessionlessRequestApplicationId(String applicationId) {
        Request.sessionlessRequestApplicationId = applicationId;
    }

    public static HttpURLConnection toHttpConnection(RequestContext context, Request... requests) {
        return toHttpConnection(context, Arrays.asList(requests));
    }

    public static HttpURLConnection toHttpConnection(RequestContext context, List<Request> requests) {
        Validate.notEmptyAndContainsNoNulls(requests, "requests");

        URL url = null;
        try {
            if (requests.size() == 1) {
                // Single request case.
                Request request = requests.get(0);
                url = request.getUrlForSingleRequest();
            } else {
                // Batch case -- URL is just the graph API base, individual request URLs are serialized
                // as relative_url parameters within each batch entry.
                url = new URL(ServerProtocol.GRAPH_URL);
            }
        } catch (MalformedURLException e) {
            throw new FacebookException("could not construct URL for request", e);
        }

        HttpURLConnection connection;
        try {
            connection = (HttpURLConnection) url.openConnection();

            connection.setRequestProperty(USER_AGENT_HEADER, getUserAgent());
            connection.setRequestProperty(CONTENT_TYPE_HEADER, getMimeContentType());

            connection.setChunkedStreamingMode(0);

            serializeToUrlConnection(requests, connection);
        } catch (IOException e) {
            throw new FacebookException("could not construct request body", e);
        } catch (JSONException e) {
            throw new FacebookException("could not construct request body", e);
        }

        return connection;
    }

    public static Response execute(Request request) {
        return execute(null, request);
    }

    public static Response execute(RequestContext context, Request request) {
        List<Response> responses = executeBatch(context, request);

        if (responses == null || responses.size() != 1) {
            throw new FacebookException("invalid state: expected a single response");
        }

        return responses.get(0);
    }

    public static List<Response> executeBatch(Request... requests) {
        return executeBatch(null, requests);
    }

    public static List<Response> executeBatch(RequestContext context, Request... requests) {
        Validate.notNull(requests, "requests");

        return executeBatch(context, Arrays.asList(requests));
    }

    public static List<Response> executeBatch(RequestContext context, List<Request> requests) {
        Validate.notEmptyAndContainsNoNulls(requests, "requests");

        // TODO port: piggyback requests onto batch if needed

        HttpURLConnection connection = toHttpConnection(context, requests);
        List<Response> responses = Response.fromHttpConnection(context, connection, requests);

        // TODO port: callback or otherwise handle piggybacked requests
        // TODO port: strip out responses from piggybacked requests

        return responses;
    }

    @Override
    public String toString() {
        return new StringBuilder().append("{Request: ").append(" session: ").append(this.session)
                .append(", graphPath: ").append(this.graphPath).append(", graphObject: ").append(this.graphObject)
                .append(", restMethod: ").append(this.restMethod).append(", httpMethod: ").append(this.getHttpMethod())
                .append(", parameters: ").append(this.parameters).append("}").toString();
    }

    private void addCommonParameters() {
        this.parameters.putString(FORMAT_PARAM, FORMAT_JSON);
        this.parameters.putString(SDK_PARAM, SDK_ANDROID);
        if (this.session != null && !this.parameters.containsKey(ACCESS_TOKEN_PARAM)) {
            this.parameters.putString(ACCESS_TOKEN_PARAM, "TODO port: get access token");
        }
    }

    private String appendParametersToBaseUrl(String baseUrl) {
        Uri.Builder uriBuilder = new Uri.Builder().encodedPath(baseUrl);

        Set<String> keys = this.parameters.keySet();
        for (String key : keys) {
            Object value = this.parameters.get(key);

            // TODO port: handle other types? on iOS we assume all parameters
            // are strings, images, or NSData
            if (!(value instanceof String)) {
                if (getHttpMethod().equals(GET_METHOD)) {
                    throw new IllegalArgumentException("Cannot use GET to upload a file.");
                }

                // Skip non-strings. We add them later as attachments.
                continue;
            }
            uriBuilder.appendQueryParameter(key, value.toString());
        }

        return uriBuilder.toString();
    }

    private String getUrlStringForBatchedRequest() throws MalformedURLException {
        String baseUrl = null;
        if (this.restMethod != null) {
            baseUrl = ServerProtocol.BATCHED_REST_METHOD_URL_BASE + this.restMethod;
        } else {
            baseUrl = this.graphPath;
        }

        addCommonParameters();
        // We don't convert to a URL because it may only be part of a URL.
        return appendParametersToBaseUrl(baseUrl);
    }

    private URL getUrlForSingleRequest() throws MalformedURLException {
        String baseUrl = null;
        if (this.restMethod != null) {
            baseUrl = ServerProtocol.REST_URL_BASE + this.restMethod;
        } else {
            baseUrl = ServerProtocol.GRAPH_URL_BASE + this.graphPath;
        }

        addCommonParameters();
        return new URL(appendParametersToBaseUrl(baseUrl));
    }

    private void serializeToBatch(JSONArray batch, Bundle attachments) throws JSONException, MalformedURLException {
        JSONObject batchEntry = new JSONObject();

        if (this.batchEntryName != null) {
            batchEntry.put(BATCH_ENTRY_NAME_PARAM, this.batchEntryName);
        }

        String relativeURL = getUrlStringForBatchedRequest();
        batchEntry.put(BATCH_RELATIVE_URL_PARAM, relativeURL);
        batchEntry.put(BATCH_METHOD_PARAM, getHttpMethod());
        if (this.session != null) {
            batchEntry.put(ACCESS_TOKEN_PARAM, this.session.getAccessToken());
        }

        // Find all of our attachments. Remember their names and put them in the attachment map.
        ArrayList<String> attachmentNames = new ArrayList<String>();
        Set<String> keys = this.parameters.keySet();
        for (String key : keys) {
            Object value = this.parameters.get(key);
            if (Serializer.isSupportedAttachmentType(value)) {
                // Make the name unique across this entire batch.
                String name = String.format("%s%d", ATTACHMENT_FILENAME_PREFIX, attachments.size());
                attachmentNames.add(name);
                Utility.putObjectInBundle(attachments, name, value);
            }
        }

        if (!attachmentNames.isEmpty()) {
            String attachmentNamesString = TextUtils.join(",", attachmentNames);
            batchEntry.put(ATTACHED_FILES_PARAM, attachmentNamesString);
        }

        if (this.graphObject != null) {
            // TODO port: serialize graph object to JSON
            // TODO port: set "body" element of batchEntry
        }

        batch.put(batchEntry);
    }

    private static void serializeToUrlConnection(List<Request> requests, HttpURLConnection connection)
            throws IOException, JSONException {
        int numRequests = requests.size();

        String connectionHttpMethod = (numRequests == 1) ? connection.getRequestMethod() : POST_METHOD;
        connection.setRequestMethod(connectionHttpMethod);

        // If we have a single non-POST request, don't try to serialize anything or HttpURLConnection will
        // turn it into a POST.
        boolean isPost = connectionHttpMethod.equals(POST_METHOD);
        if (!isPost) {
            return;
        }

        connection.setDoOutput(true);

        OutputStream outputStream = new BufferedOutputStream(connection.getOutputStream());
        try {
            Serializer serializer = new Serializer(outputStream);

            if (numRequests == 1) {
                Request request = requests.get(0);
                serializeParameters(request.parameters, serializer);
                serializeAttachments(request.parameters, serializer);
                if (request.graphObject != null) {
                    // TODO port: serialize graphObject to JSON
                }
            } else {
                String batchAppID = getBatchAppID(requests);
                if (Utility.isNullOrEmpty(batchAppID)) {
                    throw new FacebookException("At least one request in a batch must have an open Session, or a "
                            + "default app ID must be specified.");
                }

                serializer.writeString(BATCH_APP_ID_PARAM, batchAppID);

                // We write out all the requests as JSON, remembering which file attachments they have, then
                // write out the attachments.
                Bundle attachments = new Bundle();
                serializeRequestsAsJSON(serializer, requests, attachments);
                serializeAttachments(attachments, serializer);

                // TODO port: attach attachments with above names
                // TODO port: ensure method is POST
            }
        } finally {
            outputStream.close();
        }
    }

    private static void serializeParameters(Bundle bundle, Serializer serializer) throws IOException {
        Set<String> keys = bundle.keySet();

        for (String key : keys) {
            Object value = bundle.get(key);
            if (Serializer.isSupportedParameterType(value)) {
                serializer.writeObject(key, value);
            }
        }
    }

    private static void serializeAttachments(Bundle bundle, Serializer serializer) throws IOException {
        Set<String> keys = bundle.keySet();

        for (String key : keys) {
            Object value = bundle.get(key);
            if (Serializer.isSupportedAttachmentType(value)) {
                serializer.writeObject(key, value);
            }
        }
    }

    private static void serializeRequestsAsJSON(Serializer serializer, List<Request> requests, Bundle attachments)
            throws JSONException, IOException {
        JSONArray batch = new JSONArray();
        for (Request request : requests) {
            request.serializeToBatch(batch, attachments);
        }

        String batchAsString = batch.toString();
        serializer.writeString(BATCH_PARAM, batchAsString);
    }

    private static String getMimeContentType() {
        return String.format("multipart/form-data; boundary=%s", MIME_BOUNDARY);
    }

    private static String getUserAgent() {
        // TODO port: construct user agent string with version
        return "FBAndroidSDK";
    }

    private static String getBatchAppID(List<Request> requests) {
        for (Request request : requests) {
            Session session = request.getSession();
            if (session != null) {
                return session.getApplicationId();
            }
        }
        return Request.sessionlessRequestApplicationId;
    }

    private static class Serializer {
        private OutputStream outputStream;
        private boolean firstWrite = true;

        public Serializer(OutputStream outputStream) {
            this.outputStream = outputStream;
        }

        public void writeObject(String key, Object value) throws IOException {
            if (value instanceof String) {
                writeString(key, (String) value);
            } else if (value instanceof Bitmap) {
                writeBitmap(key, (Bitmap) value);
            } else if (value instanceof byte[]) {
                writeBytes(key, (byte[]) value);
            } else {
                throw new IllegalArgumentException("value is not a supported type: String, Bitmap, byte[]");
            }
        }

        public void writeString(String key, String value) throws IOException {
            writeContentDisposition(key, null, null);
            writeLine(value);
            writeRecordBoundary();
        }

        public void writeBitmap(String key, Bitmap bitmap) throws IOException {
            writeContentDisposition(key, key, "image/png");
            // Note: quality parameter is ignored for PNG
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, this.outputStream);
            writeRecordBoundary();
        }

        public void writeBytes(String key, byte[] bytes) throws IOException {
            writeContentDisposition(key, key, "content/unknown");
            this.outputStream.write(bytes);
            writeRecordBoundary();
        }

        public void writeRecordBoundary() throws IOException {
            writeLine("--%s", MIME_BOUNDARY);
        }

        public void writeContentDisposition(String name, String filename, String contentType) throws IOException {
            write("Content-Disposition: form-data; name=\"%s\"", name);
            if (filename != null) {
                write("; filename=\"%s\"", filename);
            }
            writeLine(""); // newline after Content-Disposition
            if (contentType != null) {
                writeLine("%s: %s", CONTENT_TYPE_HEADER, contentType);
            }
            writeLine(""); // blank line before content
        }

        public void write(String format, Object... args) throws IOException {
            if (firstWrite) {
                // Prepend all of our output with a boundary string.
                this.outputStream.write("--".getBytes());
                this.outputStream.write(MIME_BOUNDARY.getBytes());
                this.outputStream.write("\r\n".getBytes());
                firstWrite = false;
            }
            this.outputStream.write(String.format(format, args).getBytes());
        }

        public void writeLine(String format, Object... args) throws IOException {
            write(format, args);
            write("\r\n");
        }

        public static boolean isSupportedAttachmentType(Object value) {
            return value instanceof Bitmap || value instanceof byte[];
        }

        public static boolean isSupportedParameterType(Object value) {
            return value instanceof String;
        }

    }

}