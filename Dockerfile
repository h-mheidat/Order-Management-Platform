# ------------------------------------------------------------------------------------------------
# Build stage
# ------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /build

# Dependencies are copied and resolved before the source. Docker caches each layer, so editing a
# Java file re-runs only the compile - not a full dependency download. Copying everything at once
# invalidates the cache on every change and turns a 20-second rebuild into several minutes.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
# Tests are not run here. They need Docker (Testcontainers), which is not available inside a build
# container, and a release image must never be the first place tests are executed - CI runs
# `./mvnw verify` before anything is built.
RUN ./mvnw -B -q clean package -DskipTests

# Renamed to a fixed filename before extraction. The extracted `application` layer keeps the jar's
# original name, so without this the runtime ENTRYPOINT would have to reference
# orders-0.0.1-SNAPSHOT.jar and would break on the next version bump.
RUN cp target/orders-*.jar application.jar

# Unpacks the fat jar into its layers: dependencies change rarely, application classes change every
# commit. Copied separately below so a redeploy ships a few hundred kilobytes instead of ~60 MB of
# unchanged dependencies.
RUN java -Djarmode=tools -jar application.jar extract --layers --destination extracted

# ------------------------------------------------------------------------------------------------
# Runtime stage
# ------------------------------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# JRE, not JDK: a compiler, javadoc and debugging tools in a production image are attack surface
# with no purpose. Alpine keeps the base small, which means fewer packages that can carry a CVE.

# Never root. A container escape from a root process is a root process on the host, and nothing this
# application does requires privilege.
RUN addgroup -S orders && adduser -S -G orders orders

WORKDIR /app

COPY --from=build --chown=orders:orders /build/extracted/dependencies/ ./
COPY --from=build --chown=orders:orders /build/extracted/spring-boot-loader/ ./
COPY --from=build --chown=orders:orders /build/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=orders:orders /build/extracted/application/ ./

USER orders
EXPOSE 8080

# MaxRAMPercentage rather than a fixed -Xmx: the JVM sizes its heap from the container's actual memory
# limit, so changing the limit does not require rebuilding the image. Without it a JVM in a small
# container can pick a heap larger than the limit and be OOM-killed by the kernel with no Java error.
#
# ExitOnOutOfMemoryError: a JVM that has run out of heap is not going to recover, and a process that
# stays alive while failing every request is worse than one that restarts. Let the orchestrator do it.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError -XX:+UseContainerSupport"

# Exec form via sh -c so JAVA_OPTS expands, with `exec` so java becomes PID 1 and receives SIGTERM
# directly. Without exec, the shell is PID 1, signals never reach the JVM, graceful shutdown does not
# happen, and every deploy kills in-flight requests after the 10s grace period.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar application.jar"]
