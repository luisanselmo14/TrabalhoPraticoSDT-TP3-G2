package com.sdt.peers;


import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.MulticastSocket;


public class LeaderSender {
    public static void main(String[] args) throws Exception {
        MulticastSocket socket = new MulticastSocket();
        InetAddress group = InetAddress.getByName("230.0.0.0");
        byte[] msg = "Mensagem de teste do líder".getBytes();
        DatagramPacket packet = new DatagramPacket(msg, msg.length, group, 4446);
        socket.send(packet);
        socket.close();
        System.out.println("Mensagem enviada para todos os peers!");
    }
}
