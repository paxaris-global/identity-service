# Use an official Java 17 image
FROM eclipse-temurin:17-jdk

# Install Docker CLI inside container (optional if you need CLI)
RUN apt-get update && \
    apt-get install -y docker.io && \
    rm -rf /var/lib/apt/lists/*

# Set working directory
WORKDIR /app

# Copy the built jar file from the target directory into the image
COPY target/identity_service-0.0.1-SNAPSHOT.jar app.jar

# Expose the port your service runs on
EXPOSE 8087

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"]
