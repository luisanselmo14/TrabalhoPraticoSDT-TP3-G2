package com.sdt.api;


import io.ipfs.api.IPFS;
import io.ipfs.api.MerkleNode;
import io.ipfs.api.NamedStreamable;
import java.io.File;
import java.util.List;


public class IPFSClient {
    private final IPFS ipfs;


    // default constructor reads env var IPFS_MULTIADDR or falls back to /ip4/127.0.0.1/tcp/5001
    public IPFSClient() {
       String multi = System.getenv("IPFS_MULTIADDR");
       if (multi == null || multi.isBlank()) {
           multi = "/ip4/127.0.0.1/tcp/5001";
       }
       this.ipfs = new IPFS(multi);
    }

    public IPFSClient(String multiAddress) {
       this.ipfs = new IPFS(multiAddress);
    }


    public String addFile(File file) throws Exception {
        NamedStreamable.FileWrapper wrapper = new NamedStreamable.FileWrapper(file);
        List<MerkleNode> result = ipfs.add(wrapper);
        return result.get(0).hash.toString();
    }
    
    public byte[] getFileBytes(String cid) throws Exception {
        // usa Multihash para recuperar o conteúdo
        io.ipfs.multihash.Multihash mh = io.ipfs.multihash.Multihash.fromBase58(cid);
        return ipfs.cat(mh);
    }

}