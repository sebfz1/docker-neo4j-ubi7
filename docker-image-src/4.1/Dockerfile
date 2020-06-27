FROM registry.access.redhat.com/ubi7/ubi
# FROM openjdk:11-jdk-slim

RUN yum install -y java-11-openjdk.x86_64

# Original Dockerfile ('apt' commands replaced by 'yum') #

ENV NEO4J_SHA256=%%NEO4J_SHA%% \
    NEO4J_TARBALL=%%NEO4J_TARBALL%% \
    NEO4J_EDITION=%%NEO4J_EDITION%% \
    NEO4J_HOME="/var/lib/neo4j" \
    TINI_VERSION="v0.18.0" \
    TINI_SHA256="12d20136605531b09a2c2dac02ccee85e1b874eb322ef6baf7561cd93f93c855"
ARG NEO4J_URI=%%NEO4J_DIST_SITE%%/%%NEO4J_TARBALL%%

RUN groupadd -g 7474 neo4j && useradd -u 7474 -M -d "${NEO4J_HOME}" -g neo4j neo4j

COPY ./local-package/* /tmp/

# setup gosu 
RUN gpg --keyserver pool.sks-keyservers.net --recv-keys B42F6819007F00F88E364FD4036A9C25BF357DD4 \
    && curl -o /usr/local/bin/gosu -SL "https://github.com/tianon/gosu/releases/download/1.12/gosu-amd64" \
    && curl -o /usr/local/bin/gosu.asc -SL "https://github.com/tianon/gosu/releases/download/1.12/gosu-amd64.asc" \
    && gpg --verify /usr/local/bin/gosu.asc \
    && rm /usr/local/bin/gosu.asc \
    && rm -r /root/.gnupg/ \
    && chmod +x /usr/local/bin/gosu

# epal (jq)
RUN yum -y install https://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm

RUN yum update -y \
    && yum install -y curl wget jq \
    && curl -L --fail --silent --show-error "https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini" > /sbin/tini \
    && echo "${TINI_SHA256}  /sbin/tini" | sha256sum -c --strict --quiet \
    && chmod +x /sbin/tini \
    && curl --fail --silent --show-error --location --remote-name ${NEO4J_URI} \
    && echo "${NEO4J_SHA256}  ${NEO4J_TARBALL}" | sha256sum -c --strict --quiet \
    && tar --extract --file ${NEO4J_TARBALL} --directory /var/lib \
    && mv /var/lib/neo4j-* "${NEO4J_HOME}" \
    && rm ${NEO4J_TARBALL} \
    && mv "${NEO4J_HOME}"/data /data \
    && mv "${NEO4J_HOME}"/logs /logs \
    && chown -R neo4j:neo4j /data \
    && chmod -R 777 /data \
    && chown -R neo4j:neo4j /logs \
    && chmod -R 777 /logs \
    && chown -R neo4j:neo4j "${NEO4J_HOME}" \
    && chmod -R 777 "${NEO4J_HOME}" \
    && ln -s /data "${NEO4J_HOME}"/data \
    && ln -s /logs "${NEO4J_HOME}"/logs \
    && mv /tmp/neo4jlabs-plugins.json /neo4jlabs-plugins.json \
    && rm -rf /tmp/* \
    # && rm -rf /var/lib/apt/lists/* \
    && yum clean all

ENV PATH "${NEO4J_HOME}"/bin:$PATH

WORKDIR "${NEO4J_HOME}"

VOLUME /data /logs

COPY docker-entrypoint.sh /docker-entrypoint.sh

EXPOSE 7474 7473 7687

ENTRYPOINT ["/sbin/tini", "-g", "--", "/docker-entrypoint.sh"]
CMD ["neo4j"]
