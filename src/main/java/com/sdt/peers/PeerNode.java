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
import java.util.concurrent.ConcurrentHashMap;
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
    private final int totalClusterPeers; // Total de peers no cluster (incluindo líder)
    
    // RAFT state
    private volatile long currentTerm = 0;
    private volatile String votedFor = null; // null ou nome do peer que recebeu voto
    private volatile String currentLeader = null; // nome do líder atual
    private volatile RaftState state = RaftState.FOLLOWER;
    private final ScheduledExecutorService electionExecutor = Executors.newScheduledThreadPool(1);
    private final AtomicLong electionTimeoutEnd = new AtomicLong(0);
    private final Map<String, Long> knownPeers = new ConcurrentHashMap<>(); // peer name -> last seen timestamp
    
    // Recovery data structures
    private final Map<String, Integer> peerVersions = new ConcurrentHashMap<>(); // peer -> confirmedVersion
    private final Map<String, List<List<String>>> peerVersionsData = new ConcurrentHashMap<>(); // peer -> versions
    private final Map<String, Map<String, float[]>> peerEmbeddings = new ConcurrentHashMap<>(); // peer -> cid -> embedding
    private volatile boolean isRecovering = false;
    private final long recoveryTimeoutSeconds = 30; // Timeout para recuperação
    
    // Pinning management
    private final Map<String, List<String>> cidPinningPeers = new ConcurrentHashMap<>(); // cid -> lista de peers que fazem pinning
    private final Map<String, Long> pinnedCids = new ConcurrentHashMap<>(); // cid -> timestamp do pinning
    private final int minPinningRedundancy = 2; // Mínimo de 2 peers por ficheiro
    
    // RF2: Pesquisa de Informação - armazenamento local de resultados
    private final Map<String, Map<String, Object>> searchResults = new ConcurrentHashMap<>(); // searchId -> resultado
    
    enum RaftState {
        FOLLOWER, CANDIDATE, LEADER
    }

    public PeerNode(String name) {
        this.name = name;
        versions.add(new ArrayList<>());
        // Configurar timeout de detecção de falha (padrão: 15 segundos = 3 períodos de heartbeat)
        this.heartbeatTimeoutSeconds = Long.parseLong(
            System.getProperty("heartbeat.timeout.seconds", "15"));
        // Total de peers no cluster (padrão: 3 = 1 líder + 2 peers)
        this.totalClusterPeers = Integer.parseInt(
            System.getProperty("cluster.peers", "3"));
        startPubSubSubscriber();
        startFailureDetection();
        startElectionTimer();
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
                                
                                // Validar integridade da mensagem (segurança básica - RNF6)
                                if (!validateMessageIntegrity(node)) {
                                    System.err.println(name + " Message integrity validation failed for type: " + messageType);
                                    continue; // Ignorar mensagem com integridade comprometida
                                }
                                
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
                                    case "raft_request_vote":
                                        handleRequestVote(node);
                                        break;
                                    case "raft_vote_response":
                                        handleVoteResponse(node);
                                        break;
                                    case "raft_recovery_request":
                                        handleRecoveryRequest(node);
                                        break;
                                    case "raft_recovery_response":
                                        handleRecoveryResponse(node);
                                        break;
                                    case "raft_leader_announcement":
                                        handleLeaderAnnouncement(node);
                                        break;
                                    case "conflict_resolution_request":
                                        handleConflictResolutionRequest(node);
                                        break;
                                    case "conflict_resolution_response":
                                        handleConflictResolutionResponse(node);
                                        break;
                                    case "pinning_assignment":
                                        handlePinningAssignment(node);
                                        break;
                                    case "pinning_recovery_request":
                                        handlePinningRecoveryRequest(node);
                                        break;
                                    case "search_request":
                                        handleSearchRequest(node);
                                        break;
                                    case "search_result_request":
                                        handleSearchResultRequest(node);
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
                    
                    // Resolução de conflitos: sincronizar com o líder
                    if (requestedVersion > confirmedVersion + 1) {
                        // Versão muito à frente - precisamos sincronizar
                        System.out.println(name + " Initiating conflict resolution: requesting sync from leader");
                        publishConflictResolutionRequest(confirmedVersion, requestedVersion);
                    } else if (requestedVersion <= confirmedVersion) {
                        // Versão antiga - rejeitar e informar versão atual
                        System.out.println(name + " Rejecting outdated version " + requestedVersion + 
                                         " (current: " + confirmedVersion + ")");
                        publishConflictResponse(requestedVersion, false, confirmedVersion);
                    }
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
    
    /**
     * Publica pedido de resolução de conflitos
     */
    private void publishConflictResolutionRequest(int currentVersion, int requestedVersion) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "conflict_resolution_request");
            root.put("peer", name);
            root.put("currentVersion", currentVersion);
            root.put("requestedVersion", requestedVersion);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
        } catch (Exception e) {
            System.err.println(name + " publishConflictResolutionRequest error: " + e.getMessage());
        }
    }
    
    /**
     * Publica resposta de conflito
     */
    private void publishConflictResponse(int requestedVersion, boolean accepted, int currentVersion) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "conflict_resolution_response");
            root.put("peer", name);
            root.put("requestedVersion", requestedVersion);
            root.put("accepted", accepted);
            root.put("currentVersion", currentVersion);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
        } catch (Exception e) {
            System.err.println(name + " publishConflictResponse error: " + e.getMessage());
        }
    }
    
    /**
     * Processa pedido de resolução de conflitos
     */
    private synchronized void handleConflictResolutionRequest(JsonNode node) {
        // Apenas o líder processa pedidos de resolução
        // Peers normais não precisam processar isso
    }
    
    /**
     * Processa resposta de resolução de conflitos
     */
    private synchronized void handleConflictResolutionResponse(JsonNode node) {
        try {
            int requestedVersion = node.get("requestedVersion").asInt();
            boolean accepted = node.get("accepted").asBoolean();
            int peerCurrentVersion = node.has("currentVersion") ? node.get("currentVersion").asInt() : 0;
            
            if (!accepted && peerCurrentVersion > confirmedVersion) {
                // Peer tem versão mais recente, precisamos sincronizar
                System.out.println(name + " Peer has newer version (" + peerCurrentVersion + 
                                 "), need to sync");
            }
        } catch (Exception e) {
            System.err.println(name + " handleConflictResolutionResponse error: " + e.getMessage());
        }
    }
    
    /**
     * Processa atribuição de pinning
     */
    private synchronized void handlePinningAssignment(JsonNode node) {
        try {
            String cid = node.get("cid").asText();
            JsonNode assignedPeersNode = node.get("assignedPeers");
            
            if (assignedPeersNode != null && assignedPeersNode.isArray()) {
                List<String> assignedPeers = new ArrayList<>();
                for (JsonNode peerNode : assignedPeersNode) {
                    assignedPeers.add(peerNode.asText());
                }
                
                // Se este peer está na lista, fazer pinning
                if (assignedPeers.contains(name)) {
                    System.out.println(name + " Assigned to pin CID: " + cid);
                    performPinning(cid);
                    cidPinningPeers.put(cid, assignedPeers);
                    pinnedCids.put(cid, System.currentTimeMillis());
                } else {
                    System.out.println(name + " Not assigned to pin CID: " + cid);
                }
            }
        } catch (Exception e) {
            System.err.println(name + " handlePinningAssignment error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Executa pinning de um CID no IPFS
     */
    private void performPinning(String cid) {
        try {
            // Usar IPFS API para fazer pinning
            URL url = new URL(ipfsApiBase + "/api/v0/pin/add?arg=" + 
                            java.net.URLEncoder.encode(cid, StandardCharsets.UTF_8));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            
            int responseCode = conn.getResponseCode();
            if (responseCode >= 400) {
                InputStream errorStream = conn.getErrorStream();
                if (errorStream != null) {
                    String error = new String(errorStream.readAllBytes());
                    System.err.println(name + " Failed to pin " + cid + ": " + error);
                }
            } else {
                System.out.println(name + " Successfully pinned CID: " + cid);
            }
        } catch (Exception e) {
            System.err.println(name + " performPinning error for " + cid + ": " + e.getMessage());
        }
    }
    
    /**
     * Processa pedido de recuperação de pinning
     */
    private synchronized void handlePinningRecoveryRequest(JsonNode node) {
        try {
            String failedPeer = node.get("failedPeer").asText();
            JsonNode cidsToRecoverNode = node.get("cidsToRecover");
            
            if (cidsToRecoverNode != null && cidsToRecoverNode.isArray()) {
                List<String> cidsToRecover = new ArrayList<>();
                for (JsonNode cidNode : cidsToRecoverNode) {
                    cidsToRecover.add(cidNode.asText());
                }
                
                System.out.println(name + " Received pinning recovery request for peer " + failedPeer + 
                                 " with " + cidsToRecover.size() + " CIDs");
                
                // Verificar quais CIDs precisam de mais pinning
                for (String cid : cidsToRecover) {
                    List<String> currentPinningPeers = cidPinningPeers.getOrDefault(cid, new ArrayList<>());
                    if (currentPinningPeers.size() < minPinningRedundancy && 
                        !currentPinningPeers.contains(name)) {
                        // Este peer pode fazer pinning para garantir redundância
                        System.out.println(name + " Taking over pinning for CID: " + cid);
                        performPinning(cid);
                        currentPinningPeers.add(name);
                        cidPinningPeers.put(cid, currentPinningPeers);
                        pinnedCids.put(cid, System.currentTimeMillis());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println(name + " handlePinningRecoveryRequest error: " + e.getMessage());
            e.printStackTrace();
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
            root.put("timestamp", System.currentTimeMillis());
            
            // Adicionar hash de integridade da mensagem
            String payloadJson = mapper.writeValueAsString(root);
            String messageHash = calculateMessageHash(payloadJson);
            root.put("messageHash", messageHash);
            
            payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
            
            System.out.println(name + " sent prepare response v" + version + " hash=" + hash);
        } catch (Exception e) {
            System.err.println(name + " publishPrepareResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Calcula hash de integridade de uma mensagem
     */
    private String calculateMessageHash(String message) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to calculate message hash", e);
        }
    }
    
    /**
     * Valida integridade de uma mensagem
     */
    private boolean validateMessageIntegrity(JsonNode node) {
        try {
            if (!node.has("messageHash")) {
                return true; // Mensagens antigas podem não ter hash
            }
            
            String receivedHash = node.get("messageHash").asText();
            
            // Criar cópia sem o hash para recalcular
            ObjectNode nodeCopy = mapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!entry.getKey().equals("messageHash")) {
                    nodeCopy.set(entry.getKey(), entry.getValue());
                }
            });
            
            String messageJson = mapper.writeValueAsString(nodeCopy);
            String calculatedHash = calculateMessageHash(messageJson);
            
            boolean isValid = receivedHash.equals(calculatedHash);
            if (isValid) {
                System.out.println(name + " Message integrity validated successfully (messageHash verified)");
            } else {
                System.err.println(name + " Message integrity validation failed: hash mismatch");
            }
            
            return isValid;
        } catch (Exception e) {
            System.err.println(name + " validateMessageIntegrity error: " + e.getMessage());
            return false;
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
    
    private synchronized void handleHeartbeat(JsonNode node) {
        try {
            long sequence = node.has("sequence") ? node.get("sequence").asLong() : 0;
            String heartbeatLeader = node.has("leader") ? node.get("leader").asText() : null;
            
            // Se recebemos heartbeat, resetar estado de eleição
            if (state == RaftState.CANDIDATE) {
                state = RaftState.FOLLOWER;
                votedFor = null;
            }
            
            // Atualizar líder atual se identificado no heartbeat
            if (heartbeatLeader != null) {
                currentLeader = heartbeatLeader;
            }
            
            lastHeartbeatTimestamp.set(System.currentTimeMillis());
            lastHeartbeatSequence.set(sequence);
            leaderFailed.set(false);
            
            // Se o líder estava marcado como falhado, mas recebemos um heartbeat, resetar
            if (leaderFailed.get()) {
                System.out.println(name + " received heartbeat from leader " + 
                                 (heartbeatLeader != null ? heartbeatLeader : "unknown") + 
                                 " (sequence=" + sequence + "), leader is alive again!");
                leaderFailed.set(false);
            } else {
                System.out.println(name + " received heartbeat #" + sequence + " from leader " + 
                                 (heartbeatLeader != null ? heartbeatLeader : "unknown"));
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
        // Não iniciar eleição se somos o líder (Spring Boot app não usa PeerNode como líder)
        // Peers normais não devem se tornar líderes enquanto o líder real (Spring Boot) está ativo
        // Apenas detectar falha mas não iniciar eleição automática
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
                System.err.println(name + " Current leader: " + currentLeader);
                
                // Apenas logar a detecção - eleição será iniciada apenas se necessário
                // (não iniciar automaticamente para evitar loops com o líder real)
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
        System.out.println(name + " *** LEADER FAILURE DETECTED ***");
        System.out.println(name + " Current confirmed version: " + confirmedVersion);
        System.out.println(name + " Current vector size: " + 
                          (confirmedVersion < versions.size() ? versions.get(confirmedVersion).size() : 0));
        
        // NOTA: Em produção, os peers normais (peer-1, peer-2) não devem se tornar líderes
        // O líder real é sempre o Spring Boot app (LeaderCoordinator)
        // Esta eleição é apenas para recuperação se o líder Spring Boot realmente falhar
        // Por enquanto, apenas logar - eleição será controlada pelo timer de eleição
    }
    
    /**
     * Inicia timer de eleição (para detectar quando iniciar nova eleição)
     */
    private void startElectionTimer() {
        // Timer aleatório entre 150ms e 300ms para evitar split votes
        long electionTimeout = 150 + (long)(Math.random() * 150);
        
        electionExecutor.scheduleAtFixedRate(() -> {
            try {
                // Só iniciar eleição se:
                // 1. Não recebemos heartbeat há muito tempo
                // 2. Não estamos em eleição ou já somos líder
                // 3. O líder atual não é o líder real do Spring Boot (para evitar loops)
                if (leaderFailed.get() && state != RaftState.CANDIDATE && state != RaftState.LEADER) {
                    long timeSinceLastHeartbeat = getTimeSinceLastHeartbeat();
                    // Aumentar threshold para evitar eleições desnecessárias
                    // Só iniciar se passou muito mais tempo que o timeout (2x)
                    if (timeSinceLastHeartbeat >= heartbeatTimeoutSeconds * 2) {
                        System.out.println(name + " Election timer expired (no heartbeat for " + 
                                         timeSinceLastHeartbeat + "s), starting election");
                        startElection();
                    }
                }
            } catch (Exception e) {
                System.err.println(name + " Election timer error: " + e.getMessage());
            }
        }, electionTimeout, electionTimeout, TimeUnit.MILLISECONDS);
    }
    
    /**
     * Inicia processo de eleição RAFT
     */
    private synchronized void startElection() {
        if (state == RaftState.LEADER) {
            return; // Já somos líder
        }
        
        System.out.println(name + " Starting RAFT election (term: " + currentTerm + ")");
        
        // Incrementar term e tornar-se candidato
        currentTerm++;
        state = RaftState.CANDIDATE;
        votedFor = name; // Votar em si mesmo
        
        // Coletar informações sobre versão para enviar na eleição
        int myLastLogIndex = confirmedVersion;
        long myLastLogTerm = currentTerm - 1;
        
        // Enviar RequestVote para todos os peers conhecidos
        publishRequestVote(currentTerm, myLastLogIndex, myLastLogTerm);
        
        // Resetar timeout de eleição
        electionTimeoutEnd.set(System.currentTimeMillis() + (heartbeatTimeoutSeconds * 1000));
    }
    
    /**
     * Publica mensagem RequestVote (candidato pedindo votos)
     */
    private void publishRequestVote(long term, int lastLogIndex, long lastLogTerm) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "raft_request_vote");
            root.put("term", term);
            root.put("candidate", name);
            root.put("lastLogIndex", lastLogIndex);
            root.put("lastLogTerm", lastLogTerm);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
            
            System.out.println(name + " Published RequestVote: term=" + term + 
                             " lastLogIndex=" + lastLogIndex);
        } catch (Exception e) {
            System.err.println(name + " publishRequestVote error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Processa RequestVote recebido
     */
    private synchronized void handleRequestVote(JsonNode node) {
        try {
            long term = node.get("term").asLong();
            String candidate = node.get("candidate").asText();
            int candidateLastLogIndex = node.has("lastLogIndex") ? node.get("lastLogIndex").asInt() : 0;
            long candidateLastLogTerm = node.has("lastLogTerm") ? node.get("lastLogTerm").asLong() : 0;
            
            System.out.println(name + " Received RequestVote from " + candidate + " term=" + term);
            
            boolean voteGranted = false;
            
            // Verificar se candidato tem termo maior ou igual
            if (term > currentTerm) {
                currentTerm = term;
                state = RaftState.FOLLOWER;
                votedFor = null;
                currentLeader = null;
            }
            
            // Votar se:
            // 1. Não votamos neste termo OU votamos neste candidato
            // 2. Candidato tem log pelo menos tão atualizado quanto o nosso
            if ((votedFor == null || votedFor.equals(candidate)) && term >= currentTerm) {
                // Verificar se log do candidato está atualizado
                boolean logUpToDate = (candidateLastLogTerm > currentTerm - 1) ||
                                     (candidateLastLogTerm == currentTerm - 1 && 
                                      candidateLastLogIndex >= confirmedVersion);
                
                if (logUpToDate) {
                    voteGranted = true;
                    votedFor = candidate;
                    System.out.println(name + " Voted for " + candidate + " in term " + term);
                }
            }
            
            // Enviar resposta
            publishVoteResponse(term, voteGranted);
            
        } catch (Exception e) {
            System.err.println(name + " handleRequestVote error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Publica resposta de voto
     */
    private void publishVoteResponse(long term, boolean voteGranted) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "raft_vote_response");
            root.put("term", currentTerm);
            root.put("voter", name);
            root.put("voteGranted", voteGranted);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
        } catch (Exception e) {
            System.err.println(name + " publishVoteResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Processa resposta de voto recebida
     */
    private synchronized void handleVoteResponse(JsonNode node) {
        if (state != RaftState.CANDIDATE) {
            return; // Não estamos mais em eleição
        }
        
        try {
            long term = node.get("term").asLong();
            String voter = node.get("voter").asText();
            boolean voteGranted = node.get("voteGranted").asBoolean();
            
            System.out.println(name + " Received vote from " + voter + ": " + 
                             (voteGranted ? "GRANTED" : "DENIED") + " (term=" + term + ")");
            
            // Se recebemos termo maior, tornar-se follower
            if (term > currentTerm) {
                currentTerm = term;
                state = RaftState.FOLLOWER;
                votedFor = null;
                return;
            }
            
            // Contar votos apenas se estamos no mesmo termo
            if (term == currentTerm && voteGranted) {
                knownPeers.put(voter, System.currentTimeMillis());
                
                // Verificar se temos maioria (incluindo nosso próprio voto)
                // Contar apenas votos recebidos recentemente
                int recentVotes = (int) knownPeers.values().stream()
                    .filter(timestamp -> System.currentTimeMillis() - timestamp < heartbeatTimeoutSeconds * 1000)
                    .count();
                int totalVotes = recentVotes + 1; // +1 para nosso próprio voto
                
                // Maioria = (total de peers no cluster / 2) + 1
                int majority = (totalClusterPeers / 2) + 1;
                
                System.out.println(name + " Vote count: " + totalVotes + " / " + majority + " needed (total cluster peers: " + totalClusterPeers + ")");
                
                if (totalVotes >= majority) {
                    System.out.println(name + " *** ELECTED AS LEADER *** (term: " + currentTerm + ")");
                    becomeLeader();
                }
            }
        } catch (Exception e) {
            System.err.println(name + " handleVoteResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Torna-se líder e inicia recuperação de dados
     */
    private synchronized void becomeLeader() {
        state = RaftState.LEADER;
        currentLeader = name;
        leaderFailed.set(false);
        
        System.out.println(name + " Becoming leader, initiating data recovery...");
        
        // Iniciar recuperação de dados
        initiateDataRecovery();
    }
    
    /**
     * Anuncia que somos líder
     */
    private void publishLeaderAnnouncement() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "raft_leader_announcement");
            root.put("term", currentTerm);
            root.put("leader", name);
            root.put("lastLogIndex", confirmedVersion);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
        } catch (Exception e) {
            System.err.println(name + " publishLeaderAnnouncement error: " + e.getMessage());
        }
    }
    
    /**
     * Processa anúncio de líder
     */
    private synchronized void handleLeaderAnnouncement(JsonNode node) {
        try {
            long term = node.get("term").asLong();
            String leader = node.get("leader").asText();
            
            if (term >= currentTerm) {
                currentTerm = term;
                state = RaftState.FOLLOWER;
                currentLeader = leader;
                leaderFailed.set(false);
                System.out.println(name + " New leader announced: " + leader + " (term: " + term + ")");
            }
        } catch (Exception e) {
            System.err.println(name + " handleLeaderAnnouncement error: " + e.getMessage());
        }
    }
    
    /**
     * Inicia processo de recuperação de dados de todos os peers
     */
    private void initiateDataRecovery() {
        isRecovering = true;
        System.out.println(name + " Initiating full data recovery from all peers...");
        
        // Limpar dados de recuperação anteriores
        peerVersions.clear();
        peerVersionsData.clear();
        peerEmbeddings.clear();
        
        // Enviar pedido de recuperação para todos os peers
        publishRecoveryRequest();
        
        // Timeout para recuperação
        electionExecutor.schedule(() -> {
            if (isRecovering) {
                System.out.println(name + " Recovery timeout reached, completing recovery with available data");
                completeDataRecovery();
            }
        }, recoveryTimeoutSeconds, TimeUnit.SECONDS);
    }
    
    /**
     * Publica pedido de recuperação de dados
     */
    private void publishRecoveryRequest() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "raft_recovery_request");
            root.put("term", currentTerm);
            root.put("leader", name);
            root.put("requester", name);

            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
            
            System.out.println(name + " Published recovery request");
        } catch (Exception e) {
            System.err.println(name + " publishRecoveryRequest error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Processa pedido de recuperação (enviar nossos dados)
     */
    private synchronized void handleRecoveryRequest(JsonNode node) {
        try {
            long term = node.get("term").asLong();
            String leader = node.get("leader").asText();
            
            if (term < currentTerm) {
                return; // Líder tem termo antigo
            }
            
            System.out.println(name + " Received recovery request from leader " + leader);
            
            // Preparar resposta com TODAS as estruturas de dados
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "raft_recovery_response");
            root.put("term", currentTerm);
            root.put("peer", name);
            root.put("leader", leader);
            
            // Estruturas permanentes
            root.put("confirmedVersion", confirmedVersion);
            root.set("versions", mapper.valueToTree(versions));
            
            // Índice FAISS (embeddings) - enviar apenas CIDs, embeddings serão reconstruídos
            ObjectNode cidVersionsNode = mapper.createObjectNode();
            for (String cid : faissIndex.getAllCids()) {
                cidVersionsNode.put(cid, getVersionForCid(cid));
            }
            root.set("faissCids", mapper.valueToTree(faissIndex.getAllCids()));
            root.set("cidVersions", cidVersionsNode);
            
            // Estruturas temporárias (pendingVersions, pendingEmbeddings, pendingCids)
            root.set("pendingVersions", mapper.valueToTree(pendingVersions));
            
            ObjectNode pendingEmbeddingsNode = mapper.createObjectNode();
            for (Map.Entry<Integer, float[]> entry : pendingEmbeddings.entrySet()) {
                pendingEmbeddingsNode.set(String.valueOf(entry.getKey()), mapper.valueToTree(entry.getValue()));
            }
            root.set("pendingEmbeddings", pendingEmbeddingsNode);
            
            ObjectNode pendingCidsNode = mapper.createObjectNode();
            for (Map.Entry<Integer, String> entry : pendingCids.entrySet()) {
                pendingCidsNode.put(String.valueOf(entry.getKey()), entry.getValue());
            }
            root.set("pendingCids", pendingCidsNode);
            
            String payloadJson = mapper.writeValueAsString(root);
            publishMessage(payloadJson);
            
            System.out.println(name + " Sent recovery response: version=" + confirmedVersion + 
                             " versions=" + versions.size() + 
                             " pending=" + pendingVersions.size() +
                             " confirmedVersion=" + confirmedVersion +
                             " faissCids=" + faissIndex.getAllCids().size() +
                             " pendingVersions=" + pendingVersions.size() +
                             " pendingEmbeddings=" + pendingEmbeddings.size() +
                             " pendingCids=" + pendingCids.size());
            
        } catch (Exception e) {
            System.err.println(name + " handleRecoveryRequest error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Obtém versão para um CID (helper)
     */
    private int getVersionForCid(String cid) {
        synchronized (this) {
            for (int i = 0; i < versions.size(); i++) {
                if (versions.get(i).contains(cid)) {
                    return i;
                }
            }
        }
        return -1;
    }
    
    /**
     * Processa resposta de recuperação (receber dados dos peers)
     */
    private synchronized void handleRecoveryResponse(JsonNode node) {
        if (!isRecovering || state != RaftState.LEADER) {
            return;
        }
        
        try {
            long term = node.get("term").asLong();
            String peer = node.get("peer").asText();
            
            if (term != currentTerm) {
                return; // Resposta de termo diferente
            }
            
            System.out.println(name + " Received recovery response from " + peer);
            
            // Extrair dados
            int peerVersion = node.get("confirmedVersion").asInt();
            JsonNode versionsNode = node.get("versions");
            JsonNode pendingVersionsNode = node.get("pendingVersions");
            
            // Armazenar versão do peer
            peerVersions.put(peer, peerVersion);
            
            // Recuperar estruturas permanentes
            if (versionsNode != null && versionsNode.isArray()) {
                List<List<String>> peerVersionsList = new ArrayList<>();
                for (JsonNode vNode : versionsNode) {
                    List<String> versionList = new ArrayList<>();
                    if (vNode.isArray()) {
                        for (JsonNode cidNode : vNode) {
                            versionList.add(cidNode.asText());
                        }
                    }
                    peerVersionsList.add(versionList);
                }
                peerVersionsData.put(peer, peerVersionsList);
            }
            
            // Recuperar estruturas temporárias
            if (pendingVersionsNode != null && pendingVersionsNode.isObject()) {
                // Estas serão mescladas depois
            }
            
            knownPeers.put(peer, System.currentTimeMillis());
            
            System.out.println(name + " Recovery from " + peer + ": version=" + peerVersion + 
                             " versions=" + (peerVersionsData.containsKey(peer) ? peerVersionsData.get(peer).size() : 0));
            
            // Verificar se recebemos respostas de maioria e completar recuperação
            int responsesReceived = peerVersions.size();
            int majority = (knownPeers.size() + 1) / 2 + 1; // +1 para incluir nós mesmos
            
            if (responsesReceived >= majority - 1) { // -1 porque não contamos a nós mesmos
                System.out.println(name + " Received majority of recovery responses, completing recovery");
                completeDataRecovery();
            }
            
        } catch (Exception e) {
            System.err.println(name + " handleRecoveryResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * Completa recuperação de dados, mesclando dados de todos os peers
     */
    private synchronized void completeDataRecovery() {
        if (!isRecovering) {
            return;
        }
        
        System.out.println(name + " *** COMPLETING DATA RECOVERY ***");
        
        // Encontrar versão mais alta entre todos os peers
        int maxVersion = confirmedVersion;
        for (int peerVersion : peerVersions.values()) {
            maxVersion = Math.max(maxVersion, peerVersion);
        }
        
        System.out.println(name + " Max version across all peers: " + maxVersion + 
                         " (local: " + confirmedVersion + ")");
        
        // Mesclar versões: usar versão mais recente confirmada
        List<List<String>> mergedVersions = new ArrayList<>(versions);
        
        // Adicionar nossas próprias informações
        peerVersions.put(name, confirmedVersion);
        peerVersionsData.put(name, new ArrayList<>(versions));
        
        // Para cada peer, verificar se tem versões mais recentes
        for (Map.Entry<String, List<List<String>>> entry : peerVersionsData.entrySet()) {
            String peer = entry.getKey();
            List<List<String>> peerVersionsList = entry.getValue();
            
            // Atualizar para versões mais recentes se disponíveis
            while (mergedVersions.size() < peerVersionsList.size()) {
                mergedVersions.add(new ArrayList<>(peerVersionsList.get(mergedVersions.size())));
            }
            
            // Para versões existentes, verificar se peer tem dados mais confiáveis
            for (int i = 0; i < Math.min(mergedVersions.size(), peerVersionsList.size()); i++) {
                List<String> peerVersionList = peerVersionsList.get(i);
                if (peerVersionList.size() > mergedVersions.get(i).size()) {
                    // Peer tem mais dados nesta versão, usar dele
                    mergedVersions.set(i, new ArrayList<>(peerVersionList));
                    System.out.println(name + " Updated version " + i + " from peer " + peer);
                }
            }
        }
        
        // Atualizar nossas estruturas permanentes
        versions.clear();
        versions.addAll(mergedVersions);
        
        if (maxVersion > confirmedVersion) {
            confirmedVersion = maxVersion;
            System.out.println(name + " Updated confirmed version to " + confirmedVersion);
        }
        
        // Limpar estruturas temporárias antigas que não foram commitadas
        pendingVersions.entrySet().removeIf(e -> e.getKey() <= confirmedVersion);
        pendingEmbeddings.entrySet().removeIf(e -> e.getKey() <= confirmedVersion);
        pendingCids.entrySet().removeIf(e -> e.getKey() <= confirmedVersion);
        
        isRecovering = false;
        
        System.out.println(name + " *** DATA RECOVERY COMPLETE ***");
        System.out.println(name + " Final state: version=" + confirmedVersion + 
                         " versions.size()=" + versions.size() + 
                         " faiss.size()=" + faissIndex.size() +
                         " (Recovery completed within timeout: " + recoveryTimeoutSeconds + " seconds)");
        
        // Anunciar que somos líder
        publishLeaderAnnouncement();
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
    
    /**
     * RF2: Processa pedido de pesquisa
     * O peer que aceita o token utiliza FAISS para obter documentos relevantes
     */
    private void handleSearchRequest(JsonNode node) {
        try {
            String searchId = node.get("searchId").asText();
            String token = node.get("token").asText();
            String targetPeer = node.has("targetPeer") ? node.get("targetPeer").asText() : null;
            String prompt = node.has("prompt") ? node.get("prompt").asText() : "";
            float[] queryEmbedding = mapper.convertValue(node.get("queryEmbedding"), float[].class);
            
            // Distribuição de carga: apenas o peer designado processa
            if (targetPeer != null && !targetPeer.equals(name)) {
                System.out.println(name + " Ignoring search request id=" + searchId + " (not assigned to this peer)");
                return;
            }
            
            // Se não há targetPeer especificado, usar abordagem distribuída (hash do token)
            if (targetPeer == null) {
                // Usar hash do token para determinar qual peer processa
                int tokenHash = token.hashCode();
                // Simplificação: assumir que peers têm nomes como "peer-1", "peer-2", etc.
                // Ou usar uma abordagem mais simples: apenas processar se hash mod 2 == 0 (exemplo)
                // Por enquanto, todos os peers processam, mas em produção seria mais sofisticado
                // Nota: peerIndex calculado mas não usado - pode ser usado para filtragem futura
            }
            
            System.out.println(name + " Processing search request id=" + searchId + " token=" + token + " prompt=" + prompt);
            
            // Usar FAISS para obter documentos mais relevantes
            int k = 5; // Número de resultados (pode ser configurável)
            List<String> relevantCids = faissIndex.search(queryEmbedding, k);
            
            System.out.println(name + " FAISS search completed for id=" + searchId + " found " + relevantCids.size() + " results");
            
            // Armazenar resultado localmente
            Map<String, Object> result = new HashMap<>();
            result.put("searchId", searchId);
            result.put("status", "completed");
            result.put("results", relevantCids);
            result.put("prompt", prompt);
            result.put("timestamp", System.currentTimeMillis());
            searchResults.put(searchId, result);
            
            // Enviar resposta ao líder
            publishSearchResultResponse(searchId, "completed", relevantCids, null);
            
        } catch (Exception e) {
            System.err.println(name + " handleSearchRequest error: " + e.getMessage());
            e.printStackTrace();
            
            // Enviar resposta de erro
            try {
                String searchId = node.get("searchId").asText();
                publishSearchResultResponse(searchId, "error", null, e.getMessage());
            } catch (Exception ex) {
                System.err.println(name + " Failed to send error response: " + ex.getMessage());
            }
        }
    }
    
    /**
     * RF2: Processa pedido de resultado de pesquisa
     * Quando o líder solicita o resultado, devolve se disponível
     */
    private void handleSearchResultRequest(JsonNode node) {
        try {
            String searchId = node.get("searchId").asText();
            String requestedFrom = node.has("requestedFrom") ? node.get("requestedFrom").asText() : null;
            
            // Verificar se este peer foi o que processou
            if (requestedFrom != null && !requestedFrom.equals(name)) {
                return; // Não é para este peer
            }
            
            System.out.println(name + " Received search result request for id=" + searchId);
            
            // Verificar se temos o resultado
            Map<String, Object> result = searchResults.get(searchId);
            
            if (result != null) {
                String status = (String) result.get("status");
                @SuppressWarnings("unchecked")
                List<String> results = (List<String>) result.get("results");
                String error = (String) result.get("error");
                
                // Enviar resposta
                publishSearchResultResponse(searchId, status, results, error);
                System.out.println(name + " Sent search result for id=" + searchId + " status=" + status);
            } else {
                // Resultado não disponível ainda
                System.out.println(name + " Search result not available yet for id=" + searchId);
                publishSearchResultResponse(searchId, "processing", null, null);
            }
            
        } catch (Exception e) {
            System.err.println(name + " handleSearchResultRequest error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * RF2: Publica resposta de pesquisa
     */
    private void publishSearchResultResponse(String searchId, String status, List<String> results, String error) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "search_result_response");
            root.put("searchId", searchId);
            root.put("peer", name);
            root.put("status", status);
            
            if (results != null) {
                root.set("results", mapper.valueToTree(results));
            }
            if (error != null) {
                root.put("error", error);
            }
            root.put("timestamp", System.currentTimeMillis());
            
            // Adicionar hash de integridade
            String payloadJson = mapper.writeValueAsString(root);
            String messageHash = calculateMessageHash(payloadJson);
            root.put("messageHash", messageHash);
            
            payloadJson = mapper.writeValueAsString(root);
            System.out.println(name + " Adding messageHash for integrity: " + messageHash.substring(0, Math.min(16, messageHash.length())) + "...");
            publishMessage(payloadJson);
            
            System.out.println(name + " Published search result response id=" + searchId + " status=" + status);
        } catch (Exception e) {
            System.err.println(name + " publishSearchResultResponse error: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    public void shutdown() {
        subscriberExecutor.shutdownNow();
        failureDetectionExecutor.shutdownNow();
        electionExecutor.shutdownNow();
        try {
            if (!failureDetectionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                failureDetectionExecutor.shutdownNow();
            }
            if (!electionExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                electionExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            failureDetectionExecutor.shutdownNow();
            electionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
    
    // Getters para estado RAFT
    public long getCurrentTerm() {
        return currentTerm;
    }
    
    public RaftState getState() {
        return state;
    }
    
    public String getCurrentLeader() {
        return currentLeader;
    }
    
    public boolean isRecovering() {
        return isRecovering;
    }
}