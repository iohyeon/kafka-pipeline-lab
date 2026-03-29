#!/bin/bash
# JMX Exporter JAR 다운로드 (한 번만 실행)
# Kafka 브로커에 Java Agent로 붙어서 JMX 메트릭을 Prometheus 형식으로 노출

VERSION="0.20.0"
JAR_NAME="jmx_prometheus_javaagent-${VERSION}.jar"
URL="https://repo1.maven.org/maven2/io/prometheus/jmx/jmx_prometheus_javaagent/${VERSION}/${JAR_NAME}"

if [ -f "$JAR_NAME" ]; then
    echo "이미 존재: $JAR_NAME"
else
    echo "다운로드 중: $URL"
    curl -L -o "$JAR_NAME" "$URL"
    echo "완료: $JAR_NAME ($(wc -c < "$JAR_NAME") bytes)"
fi
