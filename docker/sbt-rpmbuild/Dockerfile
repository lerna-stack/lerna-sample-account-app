FROM centos:7

ARG OPENJDK_VERSION
ARG SBT_VERSION

ENV PROJECT_PATH '/project'
ENV WORKSPACE    '/workspace'

RUN set -xe; \
  curl https://bintray.com/sbt/rpm/rpm | tee /etc/yum.repos.d/bintray-sbt-rpm.repo; \
  yum install --disablerepo=updates -y \
    rpmdevtools \
    yum-utils \
    java-${OPENJDK_VERSION}-openjdk \
    java-${OPENJDK_VERSION}-openjdk-devel \
    git \
    sbt-${SBT_VERSION}; \
 yum clean all; \
 rm -rf /var/cache/*; \
 # check
 java  -version; \
 javac -version; \
 sbt sbtVersion;

COPY entrypoint.sh /usr/local/bin/entrypoint.sh

RUN set -xe && \
   chmod +x /usr/local/bin/entrypoint.sh

VOLUME  "${WORKSPACE}"
WORKDIR "${WORKSPACE}"

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]

CMD ["-h"]
