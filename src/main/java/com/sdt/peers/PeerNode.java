package com.sdt.peers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.ipfs.multibase.Multibase;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class PeerNode implements Runnable {
    private final String name;
    private final List<List<String>> versions = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();

    public PeerNode(String name) {
        this.name = name;
        versions.add(new ArrayList<>());
        startPubSubSubscriber();
    }

    @Override
    public void run() {
        System.out.println(name + " peer run() retorna; a subscrição pubsub corre em background.");
    }

    private void startPubSubSubscriber() {
        subscriberExecutor.submit(() -> {
            try {
                String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
                String urlStr = ipfsApiBase + "/api/v0/pubsub/sub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
                
                System.out.println(name + " connecting to " + urlStr);
                
                URL url = new URL(urlStr);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setDoOutput(true);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(0);
                
                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    InputStream errorStream = conn.getErrorStream();
                    String err = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    System.err.println(name + " pubsub subscribe failed: status=" + responseCode + " body=" + err);
                    return;
                }
                
                System.out.println(name + " subscribed to " + PUBSUB_TOPIC + " successfully!");
                
                try (BufferedReader br = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null && !Thread.currentThread().isInterrupted()) {
                        line = line.trim();
                        if (line.isEmpty()) continue;
                        
                        try {
                            JsonNode wrapper = mapper.readTree(line);
                            
                            if (wrapper.has("data")) {
                                String multibaseData = wrapper.get("data").asText();
                                
                                // Decode Multibase (IPFS returns data in Multibase format)
                                byte[] decoded = Multibase.decode(multibaseData);
                                String msgJson = new String(decoded, StandardCharsets.UTF_8);
                                
                                JsonNode node = mapper.readTree(msgJson);
                                
                                if (node.has("type") && "doc_update".equals(node.get("type").asText())) {
                                    handleRemoteUpdate(node);
                                }
                            }
                        } catch (Exception exInner) {
                            System.err.println(name + " failed to parse pubsub message: " + exInner.getMessage());
                            exInner.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println(name + " pubsub subscriber failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handleRemoteUpdate(JsonNode node) {
        try {
            int version = node.get("version").asInt();
            String cid = node.get("cid").asText();
            JsonNode vectorNode = node.get("vector");
            List<String> vector = new ArrayList<>();
            
            if (vectorNode != null && vectorNode.isArray()) {
                for (JsonNode v : vectorNode) {
                    vector.add(v.asText());
                }
            }
            
            synchronized (versions) {
                if (version <= versions.size() - 1) {
                    versions.set(version, vector);
                } else if (version == versions.size()) {
                    versions.add(vector);
                } else {
                    while (versions.size() <= version) {
                        versions.add(new ArrayList<>());
                    }
                    versions.set(version, vector);
                }
            }
            
            System.out.println(name + " received update v" + version + " cid=" + cid + " vectorSize=" + vector.size());
        } catch (Exception ex) {
            System.err.println(name + " handleRemoteUpdate error: " + ex.getMessage());
        }
    }

    public void publishUpdate(int version, String cid, List<String> vector, float[] embedding) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "doc_update");
            root.put("version", version);
            root.put("cid", cid);
            root.set("vector", mapper.valueToTree(vector));
            root.set("embedding", mapper.valueToTree(embedding));

            String payloadJson = mapper.writeValueAsString(root);
            
            // Encode topic in multibase Base64URL
            String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
            
            // Build URL with topic
            String urlStr = ipfsApiBase + "/api/v0/pubsub/pub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
            
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            
            // Create proper multipart form data with filename
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            
            try (OutputStream os = conn.getOutputStream()) {
                // Start boundary
                os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                
                // Content-Disposition with name and filename
                os.write(("Content-Disposition: form-data; name=\"data\"; filename=\"message.json\"\r\n").getBytes(StandardCharsets.UTF_8));
                os.write(("Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                
                // Payload
                os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
                
                // End boundary
                os.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 400) {
                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    String err = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
                    System.err.println(name + " PubSub publish failed: " + err);
                }
            } else {
                System.out.println(name + " published update v" + version + " cid=" + cid);
            }
        } catch (Exception e) {
            System.err.println(name + " publishUpdate error: " + e.getMessage());
            e.printStackTrace();
        }
    }
}