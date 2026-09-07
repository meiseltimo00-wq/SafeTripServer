FROM eclipse-temurin:8-jdk

WORKDIR /app

COPY SafeTripServer.java .

RUN javac SafeTripServer.java

CMD ["java", "SafeTripServer"]
