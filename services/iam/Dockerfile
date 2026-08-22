FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml ./
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
COPY --from=build /src/target/iam-0.1.0.jar /app/iam.jar
ENTRYPOINT ["java", "-jar", "/app/iam.jar"]
