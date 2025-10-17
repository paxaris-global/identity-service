# Use an official Java 17 image
FROM eclipse-temurin:17-jdk-alpine

# Set working directory
WORKDIR /app

# Copy the built jar file from the target directory into the image
COPY target/identity-service-*.jar app.jar

# Expose the port your service runs on
EXPOSE 8087

# Run the jar file
ENTRYPOINT ["java", "-jar", "app.jar"]
