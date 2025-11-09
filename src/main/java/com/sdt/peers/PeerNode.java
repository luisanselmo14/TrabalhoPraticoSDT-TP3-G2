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
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    
    // Estruturas temporárias para armazenar versões não confirmadas
    private final Map<Integer, List<String>> pendingVersions = new HashMap<>();
    private final Map<Integer, float[]> pendingEmbeddings = new HashMap<>();
    private int confirmedVersion = 0;

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
                                byte[] decoded = Multibase.decode(multibaseData);
                                String msgJson = new String(decoded, StandardCharsets.UTF_8);
                                JsonNode node = mapper.readTree(msgJson);
                                
                                String messageType = node.has("type") ? node.get("type").asText() : "";
                                
                                switch (messageType) {
                                    case "doc_update_request":
                                        handleUpdateRequest(node);
                                        break;
                                    case "doc_update_commit":
                                        handleCommit(node);
                                        break;
                                    case "doc_update":
                                        handleRemoteUpdate(node);
                                        break;
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

    private void handleUpdateRequest(JsonNode node) {
        try {
            int requestedVersion = node.get("version").asInt();
            String cid = node.get("cid").asText();
            float[] embedding = mapper.convertValue(node.get("embedding"), float[].class);
            
            System.out.println(name + " received update request for v" + requestedVersion + " cid=" + cid);
            
            // Verificar conflito de versões
            synchronized (this) {
                if (requestedVersion != confirmedVersion + 1) {
                    System.err.println(name + " version conflict: expected v" + (confirmedVersion + 1) + 
                                     " but received v" + requestedVersion);
                    // TODO: Iniciar processo de resolução de conflitos
                    return;
                }
                
                // Criar nova versão temporária do vetor
                List<String> newVector = new ArrayList<>(versions.get(confirmedVersion));
                newVector.add(cid);
                
                // Armazenar temporariamente
                pendingVersions.put(requestedVersion, newVector);
                pendingEmbeddings.put(requestedVersion, embedding);
                
                // Calcular hash do vetor
                String vectorHash = calculateVectorHash(newVector);
                
                // Enviar resposta ao líder
                publishPrepareResponse(requestedVersion, vectorHash, cid);
                
                System.out.println(name + " prepared v" + requestedVersion + " hash=" + vectorHash);
            }
        } catch (Exception ex) {
            System.err.println(name + " handleUpdateRequest error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void handleCommit(JsonNode node) {
        try {
            int version = node.get("version").asInt();
            
            System.out.println(name + " received commit for v" + version);
            
            synchronized (this) {
                if (!pendingVersions.containsKey(version)) {
                    System.err.println(name + " no pending version v" + version + " to commit");
                    return;
                }
                
                // Substituir versão atual pela nova versão confirmada
                List<String> newVector = pendingVersions.remove(version);
                float[] embedding = pendingEmbeddings.remove(version);
                
                if (version <= versions.size() - 1) {
                    versions.set(version, newVector);
                } else if (version == versions.size()) {
                    versions.add(newVector);
                } else {
                    while (versions.size() <= version) {
                        versions.add(new ArrayList<>());
                    }
                    versions.set(version, newVector);
                }
                
                confirmedVersion = version;
                
                // TODO: Indexar embeddings no FAISS aqui
                System.out.println(name + " committed v" + version + " vectorSize=" + newVector.size());
            }
        } catch (Exception ex) {
            System.err.println(name + " handleCommit error: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private String calculateVectorHash(List<String> vector) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String vectorString = String.join(",", vector);
            byte[] hash = digest.digest(vectorString.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate hash", e);
        }
    }

    private void publishPrepareResponse(int version, String hash, String cid) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "doc_update_prepare_response");
            root.put("peer", name);
            root.put("version", version);
            root.put("hash", hash);
            root.put("cid", cid);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
            
            System.out.println(name + " sent prepare response v" + version + " hash=" + hash);
        } catch (Exception e) {
            System.err.println(name + " publishPrepareResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void publishMessage(String payloadJson) throws Exception {
        String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
        String urlStr = ipfsApiBase + "/api/v0/pubsub/pub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
        
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        
        String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
        conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        
        try (OutputStream os = conn.getOutputStream()) {
            os.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Disposition: form-data; name=\"data\"; filename=\"message.json\"\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(("Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            os.write(payloadJson.getBytes(StandardCharsets.UTF_8));
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
        }
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
    
    public int getConfirmedVersion() {
        return confirmedVersion;
    }
    
    public List<String> getCurrentVector() {
        synchronized (this) {
            return new ArrayList<>(versions.get(confirmedVersion));
        }
    }
}