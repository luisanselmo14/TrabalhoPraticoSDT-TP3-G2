package com.sdt.peers;


import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;


public class PeerListener {
public static void main(String[] args) throws Exception {
    MulticastSocket socket = new MulticastSocket(4446);
    InetAddress group = InetAddress.getByName("230.0.0.0");
    socket.joinGroup(group);
    System.out.println("Peer listening on multicast group...");


    byte[] buf = new byte[256];
    while (true) {
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        socket.receive(packet);
        String received = new String(packet.getData(), 0, packet.getLength());
        System.out.println("Mensagem recebida: " + received);
    }
}
}