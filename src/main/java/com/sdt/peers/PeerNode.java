package com.sdt.peers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class PeerNode implements Runnable {
    private final String name;
    private final List<List<String>> versions = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String GROUP = "230.0.0.0";
    private final int PORT = 4446;

    public PeerNode(String name) {
        this.name = name;
        // start with empty version 0
        versions.add(new ArrayList<>());
    }

    @Override
    public void run() {
        try (MulticastSocket socket = new MulticastSocket(PORT)) {
            InetAddress group = InetAddress.getByName(GROUP);
            socket.joinGroup(group);
            System.out.println(name + " listening for updates on " + GROUP + ":" + PORT);
            byte[] buf = new byte[65536];
            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                socket.receive(packet);
                String json = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                try {
                    JsonNode node = mapper.readTree(json);
                    if (node.has("type") && "doc_update".equals(node.get("type").asText())) {
                        int version = node.get("version").asInt();
                        String cid = node.get("cid").asText();
                        JsonNode vectorNode = node.get("vector");
                        List<String> vector = new ArrayList<>();
                        if (vectorNode.isArray()) {
                            for (JsonNode v : vectorNode) vector.add(v.asText());
                        }
                        // store version (replace or append)
                        synchronized (versions) {
                            if (version <= versions.size() - 1) {
                                // replace existing
                                versions.set(version, vector);
                            } else if (version == versions.size()) {
                                versions.add(vector);
                            } else {
                                // fill gaps
                                while (versions.size() <= version) versions.add(new ArrayList<>());
                                versions.set(version, vector);
                            }
                        }
                        System.out.println(name + " received update v" + version + " cid=" + cid + " vectorSize=" + vector.size());
                        // embeddings available in node.get("embedding") if needed
                    }
                } catch (Exception ex) {
                    System.err.println(name + " failed to parse update: " + ex.getMessage());
                }
            }
            socket.leaveGroup(group);
        } catch (Exception e) {
            System.err.println(name + " listener error: " + e.getMessage());
        }
    }
}
