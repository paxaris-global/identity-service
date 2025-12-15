# Start from Java 17 image
FROM eclipse-temurin:17-jdk

# Install Docker CLI
RUN apt-get update && \
    apt-get install -y docker.io && \
    ln -s /usr/bin/docker /usr/local/bin/docker && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy your Spring Boot JAR
COPY target/identity_service-0.0.1-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8087

# Run the app
ENTRYPOINT ["java", "-jar", "app.jar"]
