package com.sdt.peers;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Índice FAISS simplificado em memória para busca por similaridade vetorial
 * Usa cosine similarity para busca de embeddings
 */
public class FAISSIndex {
    private static final int EMBEDDING_DIM = 384;
    
    // Armazena embeddings: CID -> embedding vector
    private final Map<String, float[]> embeddings = new ConcurrentHashMap<>();
    
    // Armazena metadados: CID -> version
    private final Map<String, Integer> cidVersions = new ConcurrentHashMap<>();
    
    // Lock para operações de escrita
    private final ReadWriteLock lock = new ReentrantReadWriteLock();
    
    /**
     * Adiciona um embedding ao índice
     * @param cid CID do documento
     * @param embedding Vetor de embedding (384 dimensões)
     * @param version Versão do documento
     */
    public void add(String cid, float[] embedding, int version) {
        if (embedding.length != EMBEDDING_DIM) {
            throw new IllegalArgumentException("Embedding dimension must be " + EMBEDDING_DIM + 
                                             ", got " + embedding.length);
        }
        
        lock.writeLock().lock();
        try {
            // Normalizar embedding antes de armazenar
            float[] normalized = normalize(embedding);
            embeddings.put(cid, normalized);
            cidVersions.put(cid, version);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Remove um embedding do índice
     * @param cid CID do documento
     */
    public void remove(String cid) {
        lock.writeLock().lock();
        try {
            embeddings.remove(cid);
            cidVersions.remove(cid);
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Busca os k documentos mais similares
     * @param queryEmbedding Vetor de embedding de consulta
     * @param k Número de resultados
     * @return Lista de CIDs ordenados por similaridade (mais similar primeiro)
     */
    public List<String> search(float[] queryEmbedding, int k) {
        if (queryEmbedding.length != EMBEDDING_DIM) {
            throw new IllegalArgumentException("Query embedding dimension must be " + EMBEDDING_DIM);
        }
        
        float[] normalizedQuery = normalize(queryEmbedding);
        
        lock.readLock().lock();
        try {
            List<SimilarityResult> results = new ArrayList<>();
            
            for (Map.Entry<String, float[]> entry : embeddings.entrySet()) {
                String cid = entry.getKey();
                float[] storedEmbedding = entry.getValue();
                
                // Calcular cosine similarity
                float similarity = cosineSimilarity(normalizedQuery, storedEmbedding);
                results.add(new SimilarityResult(cid, similarity));
            }
            
            // Ordenar por similaridade (decrescente)
            results.sort((a, b) -> Float.compare(b.similarity, a.similarity));
            
            // Retornar top k
            List<String> topK = new ArrayList<>();
            for (int i = 0; i < Math.min(k, results.size()); i++) {
                topK.add(results.get(i).cid);
            }
            
            return topK;
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Normaliza um vetor (L2 normalization)
     */
    private float[] normalize(float[] vector) {
        float sumSquares = 0.0f;
        for (float v : vector) {
            sumSquares += v * v;
        }
        float norm = (float) Math.sqrt(sumSquares);
        
        if (norm < 1e-8f) {
            // Vetor zero, retornar como está
            return Arrays.copyOf(vector, vector.length);
        }
        
        float[] normalized = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }
    
    /**
     * Calcula cosine similarity entre dois vetores normalizados
     */
    private float cosineSimilarity(float[] a, float[] b) {
        float dotProduct = 0.0f;
        for (int i = 0; i < a.length; i++) {
            dotProduct += a[i] * b[i];
        }
        return dotProduct; // Já são normalizados, então é a cosine similarity
    }
    
    /**
     * Retorna o número de documentos indexados
     */
    public int size() {
        lock.readLock().lock();
        try {
            return embeddings.size();
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Limpa o índice
     */
    public void clear() {
        lock.writeLock().lock();
        try {
            embeddings.clear();
            cidVersions.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }
    
    /**
     * Retorna todas as CIDs indexadas
     */
    public Set<String> getAllCids() {
        lock.readLock().lock();
        try {
            return new HashSet<>(embeddings.keySet());
        } finally {
            lock.readLock().unlock();
        }
    }
    
    /**
     * Classe auxiliar para resultados de similaridade
     */
    private static class SimilarityResult {
        final String cid;
        final float similarity;
        
        SimilarityResult(String cid, float similarity) {
            this.cid = cid;
            this.similarity = similarity;
        }
    }
}

