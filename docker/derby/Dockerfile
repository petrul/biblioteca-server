# Apache Derby Network Server for biblioteca-server (replaces the shared
# prod MySQL - see biblioteca-server/README.md). No official Derby image
# exists on Docker Hub, so this fetches the exact jars from Maven Central -
# same DERBY_VERSION biblioteca-server's own derbyclient dependency uses
# (build.gradle), kept in sync deliberately, not pinned independently.
FROM eclipse-temurin:21-jre

ARG DERBY_VERSION=10.17.1.0
WORKDIR /opt/derby

# ADD with a URL fetches directly - no curl/wget needed in the base image.
ADD https://repo1.maven.org/maven2/org/apache/derby/derby/${DERBY_VERSION}/derby-${DERBY_VERSION}.jar derby.jar
ADD https://repo1.maven.org/maven2/org/apache/derby/derbynet/${DERBY_VERSION}/derbynet-${DERBY_VERSION}.jar derbynet.jar
ADD https://repo1.maven.org/maven2/org/apache/derby/derbyshared/${DERBY_VERSION}/derbyshared-${DERBY_VERSION}.jar derbyshared.jar
ADD https://repo1.maven.org/maven2/org/apache/derby/derbytools/${DERBY_VERSION}/derbytools-${DERBY_VERSION}.jar derbytools.jar

RUN useradd --create-home --shell /usr/sbin/nologin derby \
    && mkdir -p /var/lib/derby \
    && chown derby:derby /var/lib/derby \
    # ADD fetches these as root, mode 600 - unreadable by the derby user
    # this image runs as (USER derby, below) otherwise.
    && chmod 644 /opt/derby/*.jar

COPY entrypoint.sh /opt/derby/entrypoint.sh
RUN chmod +x /opt/derby/entrypoint.sh

USER derby
# derby.system.home: where the server looks for derby.properties and
# where every database's own subdirectory is created - set explicitly
# rather than relying on "whatever the cwd happens to be" so it's the
# same directory the DERBY_DATA_DIR volume mounts over (see compose file).
WORKDIR /var/lib/derby
EXPOSE 1527

ENTRYPOINT ["/opt/derby/entrypoint.sh"]
