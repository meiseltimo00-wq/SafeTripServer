FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY SafeTripServer.java .

RUN javac SafeTripServer.java

CMD ["java", "SafeTripServer"]
