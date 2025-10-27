package com.sdt.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.File;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class DocumentManager {
    private final Path storageRoot = Paths.get("storage");
    private final IPFSClient ipfsClient;
    private final List<List<String>> versions = new ArrayList<>();
    private final AtomicInteger versionCounter = new AtomicInteger(0);
    private final ObjectMapper mapper = new ObjectMapper();

    // multicast settings (reaproveita peers existentes)
    private final String GROUP = "230.0.0.0";
    private final int PORT = 4446;

    public DocumentManager(IPFSClient ipfsClient) throws Exception {
        this.ipfsClient = ipfsClient;
        Files.createDirectories(storageRoot);
        // iniciar versão 0 vazia
        versions.add(new ArrayList<>());
    }

    // chamada após adicionar a IPFS (cid já obtido)
    public synchronized int addDocumentAndPropagate(File storedFile, String cid) throws Exception {
        // criar nova versão com append (não substitui versão actual)
        List<String> base = new ArrayList<>(versions.get(versions.size() - 1));
        base.add(cid);
        versions.add(Collections.unmodifiableList(base));
        int newVersion = versionCounter.incrementAndGet();

        // gerar embedding (placeholder determinístico)
        float[] embedding = computeDeterministicEmbedding(storedFile, 128);

        // guardar embedding e metadata localmente em storage/<cid>/
        Path cidDir = storageRoot.resolve(cid);
        Files.createDirectories(cidDir);
        Path namePath = cidDir.resolve(".name");
        // se ficheiro já tem nome/type guardados, não sobrescrever
        if (!Files.exists(namePath)) {
            Files.writeString(namePath, storedFile.getName(), StandardCharsets.UTF_8);
        }
        // write embedding as JSON array
        Path embPath = cidDir.resolve(".embedding.json");
        ObjectNode embNode = mapper.createObjectNode();
        embNode.put("cid", cid);
        embNode.put("version", newVersion);
        embNode.set("embedding", mapper.valueToTree(embedding));
        Files.writeString(embPath, mapper.writeValueAsString(embNode), StandardCharsets.UTF_8);

        // propaga para peers via multicast (JSON)
        propagateVersion(newVersion, cid, base, embedding);

        return newVersion;
    }

    private void propagateVersion(int version, String cid, List<String> vector, float[] embedding) {
        try (MulticastSocket socket = new MulticastSocket()) {
            InetAddress group = InetAddress.getByName(GROUP);

            ObjectNode root = mapper.createObjectNode();
            root.put("type", "doc_update");
            root.put("version", version);
            root.put("cid", cid);
            root.set("vector", mapper.valueToTree(vector));
            root.set("embedding", mapper.valueToTree(embedding));

            byte[] payload = mapper.writeValueAsString(root).getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(payload, payload.length, group, PORT);
            socket.send(packet);
            // nota: multicast não garante entrega; considerar libp2p/pubsub em produção
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // placeholder deterministic embedding: derive floats from SHA-256 bytes
    private float[] computeDeterministicEmbedding(File file, int dim) throws Exception {
        byte[] data = Files.readAllBytes(file.toPath());
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        float[] emb = new float[dim];
        for (int i = 0; i < dim; i++) {
            int b = hash[i % hash.length] & 0xFF;
            emb[i] = (b / 255.0f);
        }
        // normalize L2
        double sum = 0;
        for (float v : emb) sum += v * v;
        double norm = Math.sqrt(sum);
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) emb[i] /= norm;
        }
        return emb;
    }

    // Getter para testes/inspeção
    public synchronized List<List<String>> getVersions() {
        return new ArrayList<>(versions);
    }
}