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
import java.util.*;
import java.util.concurrent.*;

public class LeaderCoordinator {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();
    
    // Mudança: Map de version -> List de hashes (permite duplicados)
    private final Map<Integer, List<String>> prepareResponses = new ConcurrentHashMap<>();
    private final Map<Integer, CountDownLatch> versionLatches = new ConcurrentHashMap<>();
    private final int totalPeers;
    private final int majorityThreshold;

    public LeaderCoordinator(int totalPeers) {
        this.totalPeers = totalPeers;
        this.majorityThreshold = (totalPeers / 2) + 1;
        startPubSubSubscriber();
    }

    private void startPubSubSubscriber() {
        subscriberExecutor.submit(() -> {
            try {
                String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
                String urlStr = ipfsApiBase + "/api/v0/pubsub/sub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
                
                System.out.println("Leader connecting to " + urlStr);
                
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
                    System.err.println("Leader pubsub subscribe failed: status=" + responseCode + " body=" + err);
                    return;
                }
                
                System.out.println("Leader subscribed to " + PUBSUB_TOPIC + " successfully!");
                
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
                                
                                if (node.has("type") && "doc_update_prepare_response".equals(node.get("type").asText())) {
                                    handlePrepareResponse(node);
                                }
                            }
                        } catch (Exception exInner) {
                            System.err.println("Leader failed to parse pubsub message: " + exInner.getMessage());
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("Leader pubsub subscriber failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }

    private void handlePrepareResponse(JsonNode node) {
        try {
            int version = node.get("version").asInt();
            String hash = node.get("hash").asText();
            String peer = node.get("peer").asText();
            
            System.out.println("Leader received prepare response from " + peer + " for v" + version + " hash=" + hash);
            
            // Adicionar à lista (permite duplicados do mesmo hash)
            prepareResponses.computeIfAbsent(version, k -> new ArrayList<>()).add(hash);
            
            CountDownLatch latch = versionLatches.get(version);
            if (latch != null) {
                latch.countDown();
            }
        } catch (Exception ex) {
            System.err.println("Leader handlePrepareResponse error: " + ex.getMessage());
        }
    }

    public boolean coordinateUpdate(int version, String cid, float[] embedding) {
        try {
            System.out.println("Leader coordinating update v" + version + " cid=" + cid);
            
            // Fase 1: Enviar pedido de atualização
            publishUpdateRequest(version, cid, embedding);
            
            // Aguardar respostas (timeout de 10 segundos)
            CountDownLatch latch = new CountDownLatch(majorityThreshold);
            versionLatches.put(version, latch);
            
            boolean receivedMajority = latch.await(10, TimeUnit.SECONDS);
            
            if (!receivedMajority) {
                System.err.println("Leader timeout waiting for majority responses for v" + version);
                cleanup(version);
                return false;
            }
            
            // Verificar se maioria tem mesmo hash
            List<String> hashes = prepareResponses.get(version);
            if (hashes == null || hashes.isEmpty()) {
                System.err.println("Leader no hashes received for v" + version);
                cleanup(version);
                return false;
            }
            
            System.out.println("Leader received " + hashes.size() + " responses for v" + version);
            
            // Contar ocorrências de cada hash
            Map<String, Integer> hashCounts = new HashMap<>();
            for (String hash : hashes) {
                hashCounts.put(hash, hashCounts.getOrDefault(hash, 0) + 1);
            }
            
            System.out.println("Leader hash distribution: " + hashCounts);
            
            // Verificar se algum hash tem maioria
            Optional<Map.Entry<String, Integer>> majorityHash = hashCounts.entrySet().stream()
                .filter(e -> e.getValue() >= majorityThreshold)
                .findFirst();
            
            if (majorityHash.isEmpty()) {
                System.err.println("Leader no consensus on hash for v" + version + 
                                 " (need " + majorityThreshold + " votes)");
                cleanup(version);
                return false;
            }
            
            System.out.println("Leader achieved consensus for v" + version + 
                             " hash=" + majorityHash.get().getKey() + 
                             " votes=" + majorityHash.get().getValue());
            
            // Fase 2: Enviar commit
            publishCommit(version);
            
            cleanup(version);
            return true;
            
        } catch (Exception e) {
            System.err.println("Leader coordinateUpdate error: " + e.getMessage());
            e.printStackTrace();
            cleanup(version);
            return false;
        }
    }

    private void publishUpdateRequest(int version, String cid, float[] embedding) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc_update_request");
        root.put("version", version);
        root.put("cid", cid);
        root.set("embedding", mapper.valueToTree(embedding));

        String payloadJson = mapper.writeValueAsString(root);
        publishMessage(payloadJson);
        
        System.out.println("Leader published update request v" + version);
    }

    private void publishCommit(int version) throws Exception {
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "doc_update_commit");
        root.put("version", version);

        String payloadJson = mapper.writeValueAsString(root);
        publishMessage(payloadJson);
        
        System.out.println("Leader published commit v" + version);
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
                throw new RuntimeException("PubSub publish failed: " + err);
            }
        }
    }

    private void cleanup(int version) {
        prepareResponses.remove(version);
        versionLatches.remove(version);
    }

    public void shutdown() {
        subscriberExecutor.shutdownNow();
    }
}