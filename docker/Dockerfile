FROM clojure:tools-deps-1.11.1.1435 as builder

WORKDIR /src

# INSTALAR DEPENDENCIAS DEL SISTEMA PRIMERO
RUN apt-get update && apt-get install -y curl

# CACHING DEPS - con manejo de errores
COPY ./deps.edn ./
COPY ./build.clj ./  # ← CORREGIDO: copia build.clj desde la raíz
RUN clojure -P || clojure -P || echo "Continuing despite potential warnings"
RUN clojure -T:build || true

# EL RESTO SE MANTIENE IGUAL:
COPY ./src/ /src/src/  # ← Solo src/, ya no copiamos build/

RUN clojure -T:build uber

FROM eclipse-temurin:22_36-jre-alpine as runtime

WORKDIR /app

RUN \
  apk add --no-cache \
    ca-certificates \
    tini \
    tzdata

COPY --from=builder /src/target/doplarr.jar ./
ENTRYPOINT ["/sbin/tini", "--"]
CMD ["java","-jar","/app/doplarr.jar"]

LABEL "maintainer"="Kiran Shila <me@kiranshila.com>"
LABEL "org.opencontainers.image.source"="https://github.com/kiranshila/Doplarr"

LABEL "maintainer"="Kiran Shila <me@kiranshila.com>"
LABEL "org.opencontainers.image.source"="https://github.com/kiranshila/Doplarr"
