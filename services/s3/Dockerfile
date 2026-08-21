FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/s3-0.1.0.jar /app/s3.jar
ENTRYPOINT ["java", "-jar", "/app/s3.jar"]
