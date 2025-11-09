package com.sdt.api;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;

public class IPFSClient {
    private final String ipfsApiBase;

    public IPFSClient(String ipfsApiBase) {
        this.ipfsApiBase = ipfsApiBase;
    }

    public String uploadFile(File file) throws Exception {
        URL url = new URL(ipfsApiBase + "/api/v0/add");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        
        try (var os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\n").getBytes());
            os.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + file.getName() + "\"\r\n").getBytes());
            os.write("Content-Type: application/octet-stream\r\n\r\n".getBytes());
            Files.copy(file.toPath(), os);
            os.write(("\r\n--" + boundary + "--\r\n").getBytes());
        }
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            try (InputStream errorStream = conn.getErrorStream()) {
                String error = new String(errorStream.readAllBytes());
                throw new RuntimeException("IPFS upload failed: " + error);
            }
        }
        
        try (InputStream is = conn.getInputStream()) {
            String response = new String(is.readAllBytes());
            // Parse JSON response to extract CID
            int hashIndex = response.indexOf("\"Hash\":\"");
            if (hashIndex == -1) throw new RuntimeException("No CID in response");
            int start = hashIndex + 8;
            int end = response.indexOf("\"", start);
            return response.substring(start, end);
        }
    }
}