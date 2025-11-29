package com.sdt.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class IPFSClient {
    private final String ipfsApiBase;
    private final ObjectMapper mapper = new ObjectMapper();

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
    
    /**
     * Faz pinning de um CID no IPFS
     * @param cid CID do ficheiro a fazer pinning
     * @return true se pinning foi bem-sucedido
     */
    public boolean pinAdd(String cid) throws Exception {
        URL url = new URL(ipfsApiBase + "/api/v0/pin/add?arg=" + URLEncoder.encode(cid, StandardCharsets.UTF_8));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            try (InputStream errorStream = conn.getErrorStream()) {
                String error = new String(errorStream.readAllBytes());
                System.err.println("IPFS pin add failed for " + cid + ": " + error);
                return false;
            }
        }
        
        try (InputStream is = conn.getInputStream()) {
            String response = new String(is.readAllBytes());
            // Verificar se pinning foi bem-sucedido
            return response.contains("\"Pins\":") || response.contains(cid);
        }
    }
    
    /**
     * Remove pinning de um CID
     * @param cid CID do ficheiro a remover pinning
     * @return true se remoção foi bem-sucedida
     */
    public boolean pinRemove(String cid) throws Exception {
        URL url = new URL(ipfsApiBase + "/api/v0/pin/rm?arg=" + URLEncoder.encode(cid, StandardCharsets.UTF_8));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            try (InputStream errorStream = conn.getErrorStream()) {
                String error = new String(errorStream.readAllBytes());
                System.err.println("IPFS pin rm failed for " + cid + ": " + error);
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Lista todos os CIDs pinned
     * @return Lista de CIDs pinned
     */
    public List<String> listPinned() throws Exception {
        URL url = new URL(ipfsApiBase + "/api/v0/pin/ls?type=recursive");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        
        int responseCode = conn.getResponseCode();
        if (responseCode >= 400) {
            try (InputStream errorStream = conn.getErrorStream()) {
                String error = new String(errorStream.readAllBytes());
                System.err.println("IPFS pin ls failed: " + error);
                return new ArrayList<>();
            }
        }
        
        try (InputStream is = conn.getInputStream()) {
            String response = new String(is.readAllBytes());
            JsonNode root = mapper.readTree(response);
            List<String> pinned = new ArrayList<>();
            
            if (root.has("Keys")) {
                root.get("Keys").fieldNames().forEachRemaining(pinned::add);
            }
            
            return pinned;
        }
    }
}