# Build stage
FROM maven:3.9.3-eclipse-temurin-17 AS builder
WORKDIR /app

# Copiar pom + fontes
COPY pom.xml .
COPY src ./src

# Build (gera JARs e baixa dependências para ~/.m2)
RUN mvn -B clean package -DskipTests

# Tenta extrair libdjl_torch.so dos jars em ~/.m2 (se presente)
RUN bash -lc '\
  set -eux; \
  FOUND=0; \
  while IFS= read -r JAR; do \
    if unzip -l "$JAR" | awk "{print \$4}" | grep -q "libdjl_torch.so"; then \
      ENTRY=$(unzip -l "$JAR" | awk '"'"'/libdjl_torch.so$/ {print $4; exit}'"'"'); \
      mkdir -p /root/.djl/jni/0.28.0/linux-x86_64/cpu; \
      unzip -p "$JAR" "$ENTRY" > /root/.djl/jni/0.28.0/linux-x86_64/cpu/libdjl_torch.so; \
      echo "Extracted libdjl_torch.so from $JAR"; \
      FOUND=1; break; \
    fi; \
  done < <(find ~/.m2/repository -name "*.jar"); \
  if [ "$FOUND" -eq 0 ]; then echo "libdjl_torch.so not found in local repo, skipping extraction"; fi \
'

# Garantir que /root/.djl exista para que o COPY não falhe (mesmo vazio)
RUN mkdir -p /root/.djl && touch /root/.djl/.placeholder

# Runtime stage
FROM eclipse-temurin:17-jdk
WORKDIR /app

# Copiar JARs e cache DJL preparado (se extraído)
COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT.jar /app/target/
COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT-cluster-runner.jar /app/target/
COPY --from=builder /root/.djl /root/.djl

COPY run.sh /app/run.sh
RUN apt-get update && apt-get install -y dos2unix \
    && dos2unix /app/run.sh
RUN chmod +x /app/run.sh

EXPOSE 8081
CMD ["/app/run.sh"]