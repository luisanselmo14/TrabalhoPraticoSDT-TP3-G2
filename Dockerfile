# Build stage
FROM maven:3.9.3-eclipse-temurin-17 AS builder
WORKDIR /app

# Copiar pom + fontes
COPY pom.xml . 
COPY src ./src

# Build (gera JARs)
RUN mvn -B -DskipTests package

# Runtime stage
FROM eclipse-temurin:17-jdk
WORKDIR /app

# Copiar JARs e run.sh
COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT.jar /app/target/
COPY --from=builder /app/target/leader-api-1.0-SNAPSHOT-cluster-runner.jar /app/target/
COPY run.sh /app/run.sh
RUN chmod +x /app/run.sh

EXPOSE 8081
CMD ["/app/run.sh"]
