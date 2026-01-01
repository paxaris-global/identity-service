FROM openjdk:17-slim

RUN apt-get update && apt-get install -y git && rm -rf /var/lib/apt/lists/*

COPY target/identity-service.jar /app/identity-service.jar

WORKDIR /app

ENTRYPOINT ["java", "-jar", "identity-service.jar"]
