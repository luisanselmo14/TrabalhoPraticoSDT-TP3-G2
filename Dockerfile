# Build stage
FROM maven:3.9.3-eclipse-temurin-17 AS builder
WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn -B clean package -DskipTests

# Runtime stage
FROM eclipse-temurin:17-jdk
WORKDIR /app

# Instalar dependências nativas necessárias
RUN apt-get update && \
    apt-get install -y libgomp1 libomp5 curl unzip && \
    (apt-get install -y libopenblas0 || apt-get install -y libopenblas-base || true) && \
    rm -rf /var/lib/apt/lists/*

COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT.jar /app/target/
COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT-cluster-runner.jar /app/target/
COPY run.sh /app/run.sh

RUN apt-get update && apt-get install -y dos2unix && dos2unix /app/run.sh
RUN chmod +x /app/run.sh

EXPOSE 8081
CMD ["/app/run.sh"]
