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
import java.util.concurrent.atomic.AtomicLong;

public class LeaderCoordinator {
    private final ObjectMapper mapper = new ObjectMapper();
    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newScheduledThreadPool(1);
    
    // Mudança: Map de version -> List de hashes (permite duplicados)
    private final Map<Integer, List<String>> prepareResponses = new ConcurrentHashMap<>();
    private final Map<Integer, CountDownLatch> versionLatches = new ConcurrentHashMap<>();
    
    // Descoberta dinâmica de peers
    private final Map<String, Long> activePeers = new ConcurrentHashMap<>(); // peer name -> last seen timestamp
    private final ScheduledExecutorService peerDiscoveryExecutor = Executors.newScheduledThreadPool(1);
    private final long peerTimeoutSeconds = 30; // Considerar peer inativo após 30 segundos sem resposta
    private volatile int dynamicPeerCount;
    private volatile int majorityThreshold;
    
    // Heartbeat configuration
    private final long heartbeatIntervalSeconds;
    private final AtomicLong heartbeatSequence = new AtomicLong(0);

    public LeaderCoordinator(int initialPeerCount) {
        // Valor inicial pode vir de configuração, mas será atualizado dinamicamente
        this.dynamicPeerCount = Math.max(initialPeerCount, 1); // Mínimo 1
        this.majorityThreshold = (dynamicPeerCount / 2) + 1;
        
        // Configurar intervalo de heartbeat (padrão: 5 segundos)
        this.heartbeatIntervalSeconds = Long.parseLong(
            System.getProperty("heartbeat.interval.seconds", "5"));
        
        startPubSubSubscriber();
        startHeartbeatService();
        startPeerDiscovery();
        
        System.out.println("LeaderCoordinator: Initial peer count: " + initialPeerCount + 
                          ", majority threshold: " + majorityThreshold);
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
            
            // Atualizar descoberta dinâmica de peers
            updateActivePeer(peer);
            
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
    
    /**
     * Atualiza o timestamp de um peer ativo
     */
    private void updateActivePeer(String peerName) {
        long currentTime = System.currentTimeMillis();
        activePeers.put(peerName, currentTime);
        
        // Atualizar contagem dinâmica de peers
        int newPeerCount = activePeers.size();
        if (newPeerCount != dynamicPeerCount) {
            dynamicPeerCount = newPeerCount;
            majorityThreshold = (dynamicPeerCount / 2) + 1;
            System.out.println("LeaderCoordinator: Updated peer count to " + dynamicPeerCount + 
                             ", new majority threshold: " + majorityThreshold);
        }
    }
    
    /**
     * Remove peers inativos e atualiza contagem
     */
    private void cleanupInactivePeers() {
        long currentTime = System.currentTimeMillis();
        long timeoutMillis = peerTimeoutSeconds * 1000;
        
        activePeers.entrySet().removeIf(entry -> {
            long timeSinceLastSeen = currentTime - entry.getValue();
            if (timeSinceLastSeen > timeoutMillis) {
                System.out.println("LeaderCoordinator: Removing inactive peer: " + entry.getKey() + 
                                 " (last seen " + (timeSinceLastSeen / 1000) + " seconds ago)");
                return true;
            }
            return false;
        });
        
        // Atualizar contagem após limpeza
        int newPeerCount = activePeers.size();
        if (newPeerCount != dynamicPeerCount) {
            dynamicPeerCount = Math.max(newPeerCount, 1); // Mínimo 1
            majorityThreshold = (dynamicPeerCount / 2) + 1;
            System.out.println("LeaderCoordinator: After cleanup, peer count: " + dynamicPeerCount + 
                             ", majority threshold: " + majorityThreshold);
        }
    }
    
    /**
     * Inicia o serviço de descoberta de peers
     */
    private void startPeerDiscovery() {
        System.out.println("LeaderCoordinator: Starting peer discovery service");
        
        // Limpar peers inativos a cada 10 segundos
        peerDiscoveryExecutor.scheduleAtFixedRate(() -> {
            try {
                cleanupInactivePeers();
            } catch (Exception e) {
                System.err.println("LeaderCoordinator: Peer discovery error: " + e.getMessage());
            }
        }, 10, 10, TimeUnit.SECONDS);
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

    private void startHeartbeatService() {
        System.out.println("LeaderCoordinator: Starting heartbeat service (interval: " + 
                          heartbeatIntervalSeconds + " seconds)");
        
        heartbeatExecutor.scheduleAtFixedRate(() -> {
            try {
                publishHeartbeat();
            } catch (Exception e) {
                System.err.println("LeaderCoordinator: Heartbeat error: " + e.getMessage());
                e.printStackTrace();
            }
        }, heartbeatIntervalSeconds, heartbeatIntervalSeconds, TimeUnit.SECONDS);
    }
    
    private void publishHeartbeat() throws Exception {
        long sequence = heartbeatSequence.incrementAndGet();
        ObjectNode root = mapper.createObjectNode();
        root.put("type", "heartbeat");
        root.put("sequence", sequence);
        root.put("timestamp", System.currentTimeMillis());
        
        String payloadJson = mapper.writeValueAsString(root);
        publishMessage(payloadJson);
        
        System.out.println("LeaderCoordinator: Published heartbeat #" + sequence);
    }

    public void shutdown() {
        subscriberExecutor.shutdownNow();
        heartbeatExecutor.shutdownNow();
        peerDiscoveryExecutor.shutdownNow();
        try {
            if (!heartbeatExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                heartbeatExecutor.shutdownNow();
            }
            if (!peerDiscoveryExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                peerDiscoveryExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            heartbeatExecutor.shutdownNow();
            peerDiscoveryExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    /**
     * Retorna o número atual de peers ativos
     */
    public int getActivePeerCount() {
        return dynamicPeerCount;
    }
    
    /**
     * Retorna o threshold de maioria atual
     */
    public int getMajorityThreshold() {
        return majorityThreshold;
    }
}