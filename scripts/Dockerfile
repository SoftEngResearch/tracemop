FROM ubuntu:22.04

RUN \
  apt-get update && \
  apt-get install -y software-properties-common && \
# Install Git
  apt-get install -y git && \
# Install python
  apt-get update && \
  apt-get install -y python3 python3-pip python-is-python3 && \
  rm -rf /var/lib/apt/lists/* && \
# Install misc
  apt-get update && \
  apt-get install -y sudo && \
  apt-get install -y vim && \
  apt-get install -y emacs && \
  apt-get install -y wget && \
  apt-get install -y bc && \
  apt-get install -y cloc && \
  apt-get install -y zip unzip && \
  apt-get install -y locales locales-all && \
  apt-get install -y parallel && \
  apt-get install -y pigz && \
# Install OpenJDK 21
  apt-get install -y openjdk-21-jdk && \
  mv /usr/lib/jvm/java-21-openjdk* /usr/lib/jvm/java-21-openjdk

# Set up user (tracemop)
RUN useradd -ms /bin/bash -c "tracemop" tracemop && echo "tracemop:docker" | chpasswd && adduser tracemop sudo

USER tracemop

WORKDIR /home/tracemop/

# Use OpenJDK 21 when building the docker image
ENV JAVA_HOME=/usr/lib/jvm/java-21-openjdk

# Install Maven 3.8.8 locally for user
RUN \
  wget https://archive.apache.org/dist/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz && \
  tar -xzf apache-maven-3.8.8-bin.tar.gz && mv apache-maven-3.8.8/ apache-maven/ && \
  rm apache-maven-3.8.8-bin.tar.gz && \
  cd /home/tracemop && \
# Set up the user's configurations
  tail -n +10 ~/.bashrc > ~/tmp-bashrc && \
  mv ~/tmp-bashrc ~/.bashrc && \
  wget https://github.com/eclipse-aspectj/aspectj/releases/download/V1_9_21_2/aspectj-1.9.21.2.jar && \
  mkdir aspectj-1.9.21.2 && /usr/lib/jvm/java-21-openjdk/bin/java -jar aspectj-1.9.21.2.jar -to aspectj-1.9.21.2 && rm aspectj-1.9.21.2.jar && \
  sed -i 's/64M/10240M/g' aspectj-1.9.21.2/bin/ajc && \
  echo 'JAVAHOME=/usr/lib/jvm/java-21-openjdk' >> ~/.bashrc && \
  echo 'export JAVA_HOME=${JAVAHOME}' >> ~/.bashrc && \
  echo 'export M2_HOME=${HOME}/apache-maven' >> ~/.bashrc && \
  echo 'export MAVEN_HOME=${HOME}/apache-maven' >> ~/.bashrc && \
  echo 'ASPECTJ_DIR=${HOME}/aspectj-1.9.21.2' >> ~/.bashrc && \
  echo 'export PATH=${M2_HOME}/bin:${JAVAHOME}/bin:${ASPECTJ_DIR}/bin:${ASPECTJ_DIR}/lib/aspectjweaver.jar:${PATH}' >> ~/.bashrc && \
  echo 'export CLASSPATH=${ASPECTJ_DIR}/lib/aspectjtools.jar:${ASPECTJ_DIR}/lib/aspectjrt.jar:${ASPECTJ_DIR}/lib/aspectjweaver.jar:${CLASSPATH}' >> ~/.bashrc

# Install profiler
RUN \
  cd /home/tracemop && \
  wget https://github.com/async-profiler/async-profiler/releases/download/v2.9/async-profiler-2.9-linux-x64.tar.gz && \
  tar xf async-profiler-2.9-linux-x64.tar.gz && \
  rm async-profiler-2.9-linux-x64.tar.gz

ENV LC_ALL en_US.UTF-8
ENV LANG en_US.UTF-8
ENV LANGUAGE en_US.UTF-8

COPY --chown=tracemop:tracemop scripts/setup.sh /home/tracemop/setup.sh
