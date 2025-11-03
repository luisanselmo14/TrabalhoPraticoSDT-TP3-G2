package com.sdt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
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
        
        startPubSubSubscriber();
    }

    public synchronized int addDocumentAndPropagate(File storedFile, String cid) throws Exception {
        // Criar nova versão do vetor (sem substituir a anterior)
        List<String> base = new ArrayList<>(versions.get(versions.size() - 1));
        base.add(cid);
        versions.add(Collections.unmodifiableList(base));
        int newVersion = versionCounter.incrementAndGet();

        // Gerar embeddings semânticos REAIS usando all-MiniLM-L6-v2
        System.out.println("Generating semantic embeddings for " + storedFile.getName() + "...");
        float[] embedding = embeddingService.generateEmbedding(storedFile);
        System.out.println("Embeddings generated: " + embedding.length + " dimensions");

        // Salvar metadados
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

        // Propagar para peers
        publishToPubSub(newVersion, cid, base, embedding);

        return newVersion;
    }

    private void publishToPubSub(int version, String cid, List<String> vector, float[] embedding) {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("type", "doc_update");
            root.put("version", version);
            root.put("cid", cid);
            root.set("vector", mapper.valueToTree(vector));
            root.set("embedding", mapper.valueToTree(embedding));

            String payloadJson = mapper.writeValueAsString(root);
            
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
                    System.err.println("DocumentManager PubSub publish failed: " + err);
                }
            } else {
                System.out.println("DocumentManager published update v" + version + " cid=" + cid);
            }
        } catch (Exception e) {
            System.err.println("DocumentManager publishToPubSub error: " + e.getMessage());
            e.printStackTrace();
        }
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
                                
                                if (msg.has("type") && "doc_update".equals(msg.get("type").asText())) {
                                    applyRemoteUpdate(msg);
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
    
    public void shutdown() {
        subscriberExecutor.shutdown();
        embeddingService.close();
    }
}