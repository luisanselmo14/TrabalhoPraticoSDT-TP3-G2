package com.sdt.api;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.inference.Predictor;
import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.Translator;
import ai.djl.translate.TranslatorContext;
import org.apache.tika.Tika;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;

/**
 * Serviço de geração de embeddings semânticos usando all-MiniLM-L6-v2
 * Compatível com FAISS (384 dimensões)
 */
public class EmbeddingService {
    private static final int MAX_LENGTH = 128; // Reduzido de 256 para evitar problemas de shape
    private static final int EMBEDDING_DIM = 384;
    
    private ZooModel<String, float[]> model;
    private final Tika tika;
    private boolean modelLoaded = false;
    
    public EmbeddingService() throws Exception {
        System.out.println("Initializing EmbeddingService...");
        this.tika = new Tika();
        
        try {
            // Tentar ONNX primeiro (mais estável que PyTorch JIT)
            System.out.println("Attempting to load ONNX model...");
            Criteria<String, float[]> criteria = Criteria.builder()
                    .setTypes(String.class, float[].class)
                    .optModelUrls("djl://ai.djl.huggingface.onnxruntime/sentence-transformers/all-MiniLM-L6-v2")
                    .optTranslator(new SentenceTransformer())
                    .optEngine("OnnxRuntime")
                    .optProgress(new ai.djl.training.util.ProgressBar())
                    .build();
            
            this.model = criteria.loadModel();
            this.modelLoaded = true;
            System.out.println("EmbeddingService initialized successfully with ONNX!");
        } catch (Exception e) {
            System.err.println("Failed to load ONNX model: " + e.getMessage());
            
            try {
                // Fallback: tentar PyTorch
                System.out.println("Attempting to load PyTorch model...");
                Criteria<String, float[]> criteria = Criteria.builder()
                        .setTypes(String.class, float[].class)
                        .optModelUrls("djl://ai.djl.huggingface.pytorch/sentence-transformers/all-MiniLM-L6-v2")
                        .optTranslator(new SentenceTransformer())
                        .optEngine("PyTorch")
                        .optProgress(new ai.djl.training.util.ProgressBar())
                        .build();
                
                this.model = criteria.loadModel();
                this.modelLoaded = true;
                System.out.println("EmbeddingService initialized successfully with PyTorch!");
            } catch (Exception ex) {
                System.err.println("Failed to load PyTorch model: " + ex.getMessage());
                System.err.println("WARNING: No model loaded. Using deterministic embeddings only.");
                this.modelLoaded = false;
            }
        }
    }
    
    /**
     * Gera embeddings para um arquivo
     */
    public float[] generateEmbedding(File file) throws Exception {
        // Extrair texto do arquivo
        String text = extractText(file);
        
        if (text == null || text.trim().isEmpty()) {
            System.err.println("Warning: Empty text extracted from " + file.getName());
            return new float[EMBEDDING_DIM]; // retorna vetor zero
        }
        
        // Truncar texto se muito longo (max ~2000 chars para evitar problemas)
        if (text.length() > 2000) {
            text = text.substring(0, 2000);
            System.out.println("Text truncated to 2000 chars for " + file.getName());
        }
        
        // Se modelo não carregou, usar fallback
        if (!modelLoaded) {
            System.out.println("Model not available, using fallback para " + file.getName());
            return generateFallbackEmbedding(file);
        }
        
        // Gerar embedding
        try (Predictor<String, float[]> predictor = model.newPredictor()) {
            float[] embedding = predictor.predict(text);
            System.out.println("Generated embedding: " + embedding.length + " dimensions para " + file.getName());
            return embedding;
        } catch (Exception e) {
            System.err.println("Error generating embedding for " + file.getName() + ": " + e.getMessage());
            e.printStackTrace();
            // Fallback: retorna embedding determinístico baseado em hash
            System.out.println("Using fallback deterministic embedding para " + file.getName());
            return generateFallbackEmbedding(file);
        }
    }
    
    /**
     * Embedding fallback se o modelo falhar
     */
    private float[] generateFallbackEmbedding(File file) throws Exception {
        System.out.println("Using fallback deterministic embedding para " + file.getName());
        byte[] data = Files.readAllBytes(file.toPath());
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = md.digest(data);
        
        float[] emb = new float[EMBEDDING_DIM];
        for (int i = 0; i < EMBEDDING_DIM; i++) {
            int b = hash[i % hash.length] & 0xFF;
            emb[i] = (b / 255.0f);
        }
        
        // Normalização L2
        double sum = 0;
        for (float v : emb) sum += v * v;
        double norm = Math.sqrt(sum);
        
        if (norm > 0) {
            for (int i = 0; i < emb.length; i++) {
                emb[i] /= norm;
            }
        }
        
        return emb;
    }
    
    /**
     * Extrai texto de qualquer tipo de arquivo usando Apache Tika
     */
    private String extractText(File file) {
        try {
            return tika.parseToString(file);
        } catch (Exception e) {
            System.err.println("Error extracting text from " + file.getName() + ": " + e.getMessage());
            // Fallback: ler como texto simples
            try {
                return Files.readString(file.toPath());
            } catch (IOException ex) {
                return "";
            }
        }
    }
    
    public void close() {
        if (model != null) {
            model.close();
        }
    }
    
    /**
     * Translator para o modelo sentence-transformers
     */
    private static class SentenceTransformer implements Translator<String, float[]> {
        private HuggingFaceTokenizer tokenizer;
        
        @Override
        public void prepare(TranslatorContext ctx) throws IOException {
            try {
                // O modelo já vem com o tokenizer
                Path modelPath = ctx.getModel().getModelPath();
                Path tokenizerPath = modelPath.resolve("tokenizer.json");
                
                if (Files.exists(tokenizerPath)) {
                    tokenizer = HuggingFaceTokenizer.newInstance(tokenizerPath);
                    System.out.println("Loaded tokenizer from model path");
                } else {
                    // Fallback: criar tokenizer padrão
                    System.out.println("Using fallback tokenizer: bert-base-uncased");
                    tokenizer = HuggingFaceTokenizer.newInstance("bert-base-uncased");
                }
            } catch (Exception e) {
                System.err.println("Error loading tokenizer: " + e.getMessage());
                tokenizer = HuggingFaceTokenizer.newInstance("bert-base-uncased");
            }
        }
        
        @Override
        public NDList processInput(TranslatorContext ctx, String input) {
            NDManager manager = ctx.getNDManager();
            
            // Tokenizar (sem parâmetro de max_length no encode)
            Encoding encoding = tokenizer.encode(input, true, false);
            
            long[] inputIds = encoding.getIds();
            long[] attentionMask = encoding.getAttentionMask();
            
            // Truncar ou fazer padding para MAX_LENGTH
            if (inputIds.length > MAX_LENGTH) {
                inputIds = Arrays.copyOf(inputIds, MAX_LENGTH);
                attentionMask = Arrays.copyOf(attentionMask, MAX_LENGTH);
                inputIds[MAX_LENGTH - 1] = 102; // [SEP]
                attentionMask[MAX_LENGTH - 1] = 1;
            } else if (inputIds.length < MAX_LENGTH) {
                long[] paddedIds = new long[MAX_LENGTH];
                long[] paddedMask = new long[MAX_LENGTH];
                System.arraycopy(inputIds, 0, paddedIds, 0, inputIds.length);
                System.arraycopy(attentionMask, 0, paddedMask, 0, attentionMask.length);
                inputIds = paddedIds;
                attentionMask = paddedMask;
            }
            
            // Criar array 2D: [1, MAX_LENGTH] usando matriz 2D diretamente
            long[][] inputIds2D = new long[1][MAX_LENGTH];
            long[][] attentionMask2D = new long[1][MAX_LENGTH];
            System.arraycopy(inputIds, 0, inputIds2D[0], 0, MAX_LENGTH);
            System.arraycopy(attentionMask, 0, attentionMask2D[0], 0, MAX_LENGTH);
            
            NDArray inputIdArray = manager.create(inputIds2D);
            NDArray attentionMaskArray = manager.create(attentionMask2D);
            
            // Debug: verificar shape
            System.out.println("Input shape: " + inputIdArray.getShape());
            
            return new NDList(inputIdArray, attentionMaskArray);
        }
        
        @Override
        public float[] processOutput(TranslatorContext ctx, NDList list) {
            try {
                // O modelo retorna embeddings já processados
                NDArray embeddings = list.get(0);
                
                // Se for [batch, seq_len, hidden], fazer mean pooling
                if (embeddings.getShape().dimension() == 3) {
                    embeddings = embeddings.mean(new int[]{1});
                }
                
                // Squeeze para remover dimensões extras
                embeddings = embeddings.squeeze();
                
                // Normalização L2
                NDArray norm = embeddings.norm(new int[]{0}, true);
                NDArray normalized = embeddings.div(norm.add(1e-12));
                
                float[] result = normalized.toFloatArray();
                
                // Se não tiver 384 dimensões, preencher ou truncar
                if (result.length != EMBEDDING_DIM) {
                    System.out.println("Warning: embedding dimension mismatch. Got " + result.length + ", expected " + EMBEDDING_DIM);
                    float[] fixed = new float[EMBEDDING_DIM];
                    System.arraycopy(result, 0, fixed, 0, Math.min(result.length, EMBEDDING_DIM));
                    return fixed;
                }
                
                return result;
            } catch (Exception e) {
                System.err.println("Error in processOutput: " + e.getMessage());
                e.printStackTrace();
                // Retornar vetor zero em caso de erro
                return new float[EMBEDDING_DIM];
            }
        }
    }
}