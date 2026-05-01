FROM jetbrains/teamcity-agent:latest

USER root

# Установка зависимостей
RUN apt-get update && \
    apt-get install -y maven git openjdk-17-jdk && \
    apt-get clean

RUN JAVA_BIN=$(readlink -f $(which java)) && \
    JAVA_HOME=$(dirname $(dirname $JAVA_BIN)) && \
    echo "Detected JAVA_HOME=$JAVA_HOME" && \
    echo "JAVA_HOME=$JAVA_HOME" >> /etc/environment && \
    ln -s $JAVA_HOME /opt/java-home

ENV JAVA_HOME=/opt/java-home
ENV PATH=$PATH:$JAVA_HOME/bin

USER buildagent