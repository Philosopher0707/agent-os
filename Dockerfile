FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY agent-os-demo/build/distributions/agent-os-demo.tar /app/
RUN tar xf agent-os-demo.tar && rm agent-os-demo.tar

EXPOSE 9090 9091
HEALTHCHECK --interval=5s --timeout=3s CMD wget -qO- http://localhost:9091/health || exit 1

ENTRYPOINT ["/app/agent-os-demo/bin/agent-os-demo"]
