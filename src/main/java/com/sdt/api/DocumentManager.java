package com.sdt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.sdt.peers.LeaderCoordinator;
import io.ipfs.multibase.Multibase;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentManager {
    private final Path storageRoot = Paths.get("storage");
    private final IPFSClient ipfsClient;
    private final List<List<String>> versions = new ArrayList<>();
    private final AtomicInteger versionCounter = new AtomicInteger(0);
    private final ObjectMapper mapper = new ObjectMapper();
    private final EmbeddingService embeddingService;
    private final LeaderCoordinator coordinator;

    private final String PUBSUB_TOPIC = "sdt_doc_updates";
    private final String ipfsApiBase = System.getProperty("ipfs.api.base",
            System.getenv().getOrDefault("IPFS_API_BASE", "http://ipfs:5001"));
    private final ExecutorService subscriberExecutor = Executors.newSingleThreadExecutor();

    public DocumentManager(IPFSClient ipfsClient) throws Exception {
        this.ipfsClient = ipfsClient;
        Files.createDirectories(storageRoot);
        versions.add(new ArrayList<>());

        System.out.println("DocumentManager using IPFS API base: " + ipfsApiBase);
        
        // Inicializar serviço de embeddings
        this.embeddingService = new EmbeddingService();
        
        // Inicializar coordenador (número de peers pode vir de configuração)
        int totalPeers = Integer.parseInt(System.getProperty("cluster.peers", "3"));
        this.coordinator = new LeaderCoordinator(totalPeers);
        
        startPubSubSubscriber();
    }

    public synchronized int addDocumentAndPropagate(File storedFile, String cid) throws Exception {
        // Gerar embeddings semânticos REAIS usando all-MiniLM-L6-v2
        System.out.println("Generating semantic embeddings for " + storedFile.getName() + "...");
        float[] embedding = embeddingService.generateEmbedding(storedFile);
        System.out.println("Embeddings generated: " + embedding.length + " dimensions");

        // Calcular próxima versão
        int newVersion = versionCounter.get() + 1;

        // Salvar metadados localmente
        Path cidDir = storageRoot.resolve(cid);
        Files.createDirectories(cidDir);
        Path namePath = cidDir.resolve(".name");
        
        if (!Files.exists(namePath)) {
            Files.writeString(namePath, storedFile.getName(), StandardCharsets.UTF_8);
        }
        
        Path embPath = cidDir.resolve(".embedding.json");
        ObjectNode embNode = mapper.createObjectNode();
        embNode.put("cid", cid);
        embNode.put("version", newVersion);
        embNode.set("embedding", mapper.valueToTree(embedding));
        Files.writeString(embPath, mapper.writeValueAsString(embNode), StandardCharsets.UTF_8);

        // Fase 1 e 2 do 2PC: Coordenar atualização com peers
        System.out.println("DocumentManager: Starting 2PC for v" + newVersion + " cid=" + cid);
        boolean consensusAchieved = coordinator.coordinateUpdate(newVersion, cid, embedding);

        if (!consensusAchieved) {
            System.err.println("DocumentManager: Failed to achieve consensus for v" + newVersion);
            throw new RuntimeException("Failed to achieve consensus with peers");
        }

        // Consensus alcançado! Atualizar versão local
        List<String> base = new ArrayList<>(versions.get(versions.size() - 1));
        base.add(cid);
        versions.add(Collections.unmodifiableList(base));
        versionCounter.set(newVersion);

        System.out.println("DocumentManager: Updated list" + versions);

        System.out.println("DocumentManager: Committed v" + newVersion + " cid=" + cid);

        return newVersion;
    }

    private void startPubSubSubscriber() {
        subscriberExecutor.submit(() -> {
            try {
                String encodedTopic = Multibase.encode(Multibase.Base.Base64Url, PUBSUB_TOPIC.getBytes(StandardCharsets.UTF_8));
                String urlStr = ipfsApiBase + "/api/v0/pubsub/sub?arg=" + URLEncoder.encode(encodedTopic, StandardCharsets.UTF_8);
                
                System.out.println("DocumentManager connecting to " + urlStr);
                
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
                    System.err.println("DocumentManager pubsub subscribe failed: status=" + responseCode + " body=" + err);
                    return;
                }
                
                System.out.println("DocumentManager subscribed to " + PUBSUB_TOPIC + " successfully!");
                
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
                                JsonNode msg = mapper.readTree(msgJson);
                                
                                // Ignorar mensagens do próprio 2PC (são tratadas pelo LeaderCoordinator)
                                if (msg.has("type")) {
                                    String type = msg.get("type").asText();
                                    if ("doc_update".equals(type)) {
                                        applyRemoteUpdate(msg);
                                    }
                                    // Mensagens "doc_update_request", "doc_update_prepare_response" 
                                    // e "doc_update_commit" são tratadas por LeaderCoordinator e PeerNode
                                }
                            }
                        } catch (Exception exInner) {
                            System.err.println("DocumentManager: failed to parse msg: " + exInner.getMessage());
                        }
                    }
                }
            } catch (Exception ex) {
                System.err.println("DocumentManager pubsub subscriber failed: " + ex.getMessage());
                ex.printStackTrace();
            }
        });
    }

    private synchronized void applyRemoteUpdate(JsonNode msg) {
        try {
            int remoteVersion = msg.get("version").asInt();
            if (remoteVersion <= versionCounter.get()) return;
            
            List<String> vector = mapper.convertValue(msg.get("vector"), new TypeReference<List<String>>() {});
            versions.add(Collections.unmodifiableList(new ArrayList<>(vector)));
            versionCounter.set(remoteVersion);
            
            System.out.println("DocumentManager applied remote update: version=" + remoteVersion);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public synchronized List<List<String>> getVersions() {
        return new ArrayList<>(versions);
    }
    
    public synchronized int getCurrentVersion() {
        return versionCounter.get();
    }
    
    public IPFSClient getIpfsClient() {
        return ipfsClient;
    }
    
    public void shutdown() {
        subscriberExecutor.shutdown();
        coordinator.shutdown();
        embeddingService.close();
    }
}