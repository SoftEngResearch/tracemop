# JavaMOP

Improved and integrated source code that was forked off the official [JavaMOP](https://github.com/runtimeverification/javamop) and [RV-Monitor](https://github.com/runtimeverification/rv-monitor) repositories, *which are no longer maintained*.

## Prerequisites

We have only tested JavaMOP on:

1. Java 1.8
2. Maven 3.6.3 and above
3. Maven Surefire 2.14 and above
4. Operating System: Linux or OSX

## Setting up

1. **INSTALLING via Docker** Ensure that you have Docker installed. Then, from the same directory as this README.md file, run:

   a. `cd scripts`

   b. `docker build -t mop:latest - < javamopDockerfile`

   c. `docker run -it mop:latest`

   d. In the Docker container, follow instructions in `$HOME/javamop-agent-bundle/README.txt` to set up a Java agent that attaches JavaMOP to running Java processes.

2. **INSTALLING LOCALLY** 

Before attempting a local install make sure that the following prerequisites are met on your
devices environment.

   a. Ensure that a version of java 1.8 is installed on your device. For linux environments run
   (note that this will override your existing JAVA_HOME, and update your PATH environment variables):
      i. `sudo apt-get install openjdk-8-jdk`
      ii. `sudo mv /usr/lib/jvm/java-8-openjdk* /usr/lib/jvm/java-8-openjdk`
      iii. `export JAVA_HOME=/usr/lib/jvm/java-8-openjdk`
   b. Ensure that a version of maven 3.6.3 is installed
      i. `sudo apt-get install maven`
      ii. `wget http://mirrors.ibiblio.org/apache/maven/maven-3/3.6.3/binaries/apache-maven-3.6.3-bin.tar.gz && tar -xzf apache-maven-3.6.3-bin.tar.gz && mv apache-maven-3.6.3/ apache-maven/ && rm apache-maven-3.6.3-bin.tar.gz`
      iii. `export MAVEN_HOME=${HOME}/apache-maven`
   c. Ensure that AspectJ is installed
      i. `wget https://www.cs.cornell.edu/courses/cs6156/2020fa/resources/aspectj1.8.tgz && tar -xzf aspectj1.8.tgz && rm aspectj1.8.tgz`
   d. Clone javamop `git clone https://github.com/owolabileg/javamop.git && cd javamop/`
   e. Run the following command which installs a modified version of [JavaParser](https://github.com/javaparser/javaparser.git) that this version of JavaMOP depends on.
   `bash scripts/install-javaparser.sh`
   f. Run the following command 
    `${HOME}/apache-maven/bin/mvn install -DskipTests && cd ${HOME}`
   g. Set up configurations to update your PATH and Java CLASSPATH
    `export M2_HOME=${HOME}/apache-maven &&export MAVEN_HOME=${HOME}/apache-maven && export ASPECTJ_DIR=${HOME}/aspectj1.8 && export PATH=${M2_HOME}/bin:${JAVAHOME}/bin:${ASPECTJ_DIR}/bin:${ASPECTJ_DIR}/lib/aspectjweaver.jar:${HOME}/javamop/javamop/bin:${HOME}/javamop/rv-monitor/bin:${HOME}/javamop/rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:${HOME}/javamop/rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar:${PATH} && export CLASSPATH=${ASPECTJ_DIR}/lib/aspectjtools.jar:${ASPECTJ_DIR}/lib/aspectjrt.jar:${ASPECTJ_DIR}/lib/aspectjweaver.jar:${HOME}/javamop/rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:${HOME}/javamop/rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar:${CLASSPATH}`
   h. Fetch the script that gets the JavaMOP  Java Agents and the files that it requires.
   `wget https://www.cs.cornell.edu/courses/cs6156/2020fa/resources/javamop-agent-bundle.tgz && tar xf javamop-agent-bundle.tgz && rm javamop-agent-bundle.tgz`
   


From the same directory as this README.md file, run:
   
   . `bash scripts/integration-test.sh`

    This command runs all the tests in JavaMOP, makes a JavaMOP agent, installs the JavaMOP agent, integrates the JavaMOP agent into the [Apache Commons FileUpload](https://github.com/apache/commons-fileupload) open-source project, then monitors the tests in that project against [161 specs](https://github.com/owolabileg/property-db/tree/master/annotated-java-api/java). So, understanding and running `scripts/integration-test.sh` is a good way to get started with using JavaMOP.

   NOTE: We are aware of one parsing-related flaky unit test in JavaMOP. When that test fails, the run of the second script will stop. One work-around is to change `mvn clean package -DskipITs` to `mvn clean package -DskipITs -DskipTests` in `scripts/integration-test.sh`. Another work-around is to comment out all occurrences of `exit 1` in `scripts/integration-test.sh`. We plan to fix these tests soon, but please feel free to contribute a pull request if you have a patch.

## Contributing

We are accepting issues and pull requests. We welcome all who are interested to help fix issues.



