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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;

public class PeerNode implements Runnable {
    private final String name;
    private final List<List<String>> versions = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();
    private final ScheduledExecutorService failureDetectionExecutor = Executors.newScheduledThreadPool(1);
    
    // Estruturas temporárias para armazenar versões não confirmadas
    private final Map<Integer, List<String>> pendingVersions = new HashMap<>();
    private final Map<Integer, float[]> pendingEmbeddings = new HashMap<>();
    private final Map<Integer, String> pendingCids = new HashMap<>(); // version -> cid
    private int confirmedVersion = 0;
    
    // Índice FAISS para busca por similaridade
    private final FAISSIndex faissIndex = new FAISSIndex();
    
    // Heartbeat monitoring
    private final AtomicLong lastHeartbeatTimestamp = new AtomicLong(System.currentTimeMillis());
    private final AtomicLong lastHeartbeatSequence = new AtomicLong(0);
    private final AtomicBoolean leaderFailed = new AtomicBoolean(false);
    private final long heartbeatTimeoutSeconds;
    private final long heartbeatIntervalSeconds;

    public PeerNode(String name) {
        this.name = name;
        versions.add(new ArrayList<>());
        // Configurar timeout de detecção de falha (padrão: 15 segundos = 3 períodos de heartbeat)
        this.heartbeatTimeoutSeconds = Long.parseLong(
            System.getProperty("heartbeat.timeout.seconds", "15"));
        this.heartbeatIntervalSeconds = Long.parseLong(
            System.getProperty("heartbeat.interval.seconds", "5"));
        startPubSubSubscriber();
        startFailureDetection();
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
                                    case "heartbeat":
                                        handleHeartbeat(node);
                                        break;
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
                pendingCids.put(requestedVersion, cid);
                
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
                String cid = pendingCids.remove(version);
                
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
                
                // Indexar embedding no FAISS
                if (cid != null && embedding != null) {
                    try {
                        faissIndex.add(cid, embedding, version);
                        System.out.println(name + " indexed embedding in FAISS for cid=" + cid + 
                                         " (index size: " + faissIndex.size() + ")");
                    } catch (Exception e) {
                        System.err.println(name + " failed to index embedding in FAISS: " + e.getMessage());
                        e.printStackTrace();
                    }
                }
                
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
    
    private void handleHeartbeat(JsonNode node) {
        try {
            long sequence = node.has("sequence") ? node.get("sequence").asLong() : 0;
            // Timestamp do heartbeat pode ser usado para sincronização futura
            // long timestamp = node.has("timestamp") ? node.get("timestamp").asLong() : System.currentTimeMillis();
            
            lastHeartbeatTimestamp.set(System.currentTimeMillis());
            lastHeartbeatSequence.set(sequence);
            
            // Se o líder estava marcado como falhado, mas recebemos um heartbeat, resetar
            if (leaderFailed.get()) {
                System.out.println(name + " received heartbeat from leader (sequence=" + sequence + 
                                 "), leader is alive again!");
                leaderFailed.set(false);
            } else {
                System.out.println(name + " received heartbeat #" + sequence + " from leader");
            }
        } catch (Exception ex) {
            System.err.println(name + " handleHeartbeat error: " + ex.getMessage());
        }
    }
    
    private void startFailureDetection() {
        System.out.println(name + ": Starting failure detection (timeout: " + 
                          heartbeatTimeoutSeconds + " seconds)");
        
        failureDetectionExecutor.scheduleAtFixedRate(() -> {
            try {
                checkLeaderFailure();
            } catch (Exception e) {
                System.err.println(name + ": Failure detection error: " + e.getMessage());
            }
        }, heartbeatTimeoutSeconds, 1, TimeUnit.SECONDS); // Verificar a cada 1 segundo
    }
    
    private void checkLeaderFailure() {
        long currentTime = System.currentTimeMillis();
        long lastHeartbeat = lastHeartbeatTimestamp.get();
        long timeSinceLastHeartbeat = (currentTime - lastHeartbeat) / 1000; // em segundos
        
        if (timeSinceLastHeartbeat >= heartbeatTimeoutSeconds) {
            if (!leaderFailed.get()) {
                leaderFailed.set(true);
                System.err.println(name + " *** LEADER FAILURE DETECTED ***");
                System.err.println(name + " No heartbeat received for " + timeSinceLastHeartbeat + 
                                 " seconds (timeout: " + heartbeatTimeoutSeconds + " seconds)");
                System.err.println(name + " Last heartbeat sequence: " + lastHeartbeatSequence.get());
                System.err.println(name + " Last heartbeat timestamp: " + lastHeartbeat);
                
                // TODO: Implementar recuperação de ficheiros pinned por outro peer
                // Por enquanto, apenas registamos a detecção
                onLeaderFailureDetected();
            }
        } else {
            // Se recebemos heartbeat recentemente e estava marcado como falhado, resetar
            if (leaderFailed.get() && timeSinceLastHeartbeat < heartbeatTimeoutSeconds) {
                System.out.println(name + " Leader is alive again (heartbeat received " + 
                                 timeSinceLastHeartbeat + " seconds ago)");
                leaderFailed.set(false);
            }
        }
    }
    
    private void onLeaderFailureDetected() {
        // TODO: Implementar recuperação automática de ficheiros pinned
        // Por enquanto, apenas logamos a detecção
        System.out.println(name + " Leader failure detected. Recovery mechanism to be implemented.");
        System.out.println(name + " Current confirmed version: " + confirmedVersion);
        System.out.println(name + " Current vector size: " + 
                          (confirmedVersion < versions.size() ? versions.get(confirmedVersion).size() : 0));
    }
    
    public boolean isLeaderFailed() {
        return leaderFailed.get();
    }
    
    public long getLastHeartbeatSequence() {
        return lastHeartbeatSequence.get();
    }
    
    public long getTimeSinceLastHeartbeat() {
        return (System.currentTimeMillis() - lastHeartbeatTimestamp.get()) / 1000;
    }
    
    /**
     * Busca documentos similares usando FAISS
     * @param queryEmbedding Embedding de consulta
     * @param k Número de resultados
     * @return Lista de CIDs mais similares
     */
    public List<String> searchSimilar(float[] queryEmbedding, int k) {
        return faissIndex.search(queryEmbedding, k);
    }
    
    /**
     * Retorna o tamanho do índice FAISS
     */
    public int getFAISSSize() {
        return faissIndex.size();
    }
    
    public void shutdown() {
        subscriberExecutor.shutdownNow();
        failureDetectionExecutor.shutdownNow();
        try {
            if (!failureDetectionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                failureDetectionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            failureDetectionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}