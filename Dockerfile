FROM registry.uc.host.dxy/library/maven

ADD . /work/

WORKDIR /work

ENV VERSION 1.0-SNAPSHOT
ENV MODEL engine

RUN mvn clean package -DskipTests && \
    mkdir /software



ENTRYPOINT ./bootstrap.sh start ${MODEL}