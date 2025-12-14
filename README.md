# TraceMOP: An Explicit-Trace Runtime Verification Tool for Java

This repository allows to do explicit-trace runtime verification, and it is built on top of improved and integrated source code that was forked off the official [JavaMOP](https://github.com/runtimeverification/javamop) and [RV-Monitor](https://github.com/runtimeverification/rv-monitor) repositories. Those repositories are *no longer maintained but we have ensured to preserve all their Java-related functionality here (read on to see how to run TraceMOP to get only those existing functionality)*.

## Experimental scripts and data

You can find our experimental scripts and data in this [directory](experiments).

## Prerequisites

We have tested TraceMOP on:

1. Java 1.8
2. Maven 3.8.8 and above
3. Maven Surefire 3.1.2 and above
4. Operating System: Ubuntu 20.04.6 LTS

If you would like directly use TraceMOP in your GitHub Action workflow, then follow the in instructions in [GitHubActions.md](docs/GitHubActions.md) instead.
Otherwise, follow the instructions below to setup TraceMOP with or without Docker.

## Setting up TraceMOP

### Setting up TraceMOP with Docker

[Install Docker](https://docs.docker.com/get-started/get-docker/)

There are two ways to setup TraceMOP using Docker.

1. **Using Docker image provided by TraceMOP.** From any directory, run:

   a. `docker pull softengresearch/tracemop`
   
   <details>
     <summary>Example Output</summary>
      
      ```
      Using default tag: latest
      latest: Pulling from softengresearch/tracemop
      3c67549075b6: Pull complete
      2709eb233a65: Pull complete
      eb812c018a94: Pull complete
      4f4fb700ef54: Pull complete
      576edd3b2a6b: Pull complete
      78c11a51b319: Pull complete
      0e7657b93c61: Pull complete
      8cada9ec2edc: Pull complete
      Digest: sha256:13bfc3191ee33486b23706b9947f879715e4c7dfefe5864c4a6264ee5f807f68
      Status: Downloaded newer image for softengresearch/tracemop:latest
      docker.io/softengresearch/tracemop:latest
      ```
   </details>

   b. `docker run -it softengresearch/tracemop`

   <details>
     <summary>Example Output</summary>
      
      ```
      To run a command as administrator (user "root"), use "sudo <command>".
      See "man sudo_root" for details.

      tracemop@aa9aed9b5592:~$
      ```
   </details>

This provided image has TraceMOP's Maven plugin and TraceMOP's Java agents installed.

2. **Building your own image from scratch.** This option will take longer than the first option (~15 minutes on our machine) because this option will install TraceMOP's dependencies, download TraceMOP, and build TraceMOP's Maven plugin and Java agent. 

   From the same directory as this README.md file, run:

   a. `docker build -f scripts/Dockerfile . -t tracemop`  # This command will create a Docker image that has TraceMOP's dependencies installed (e.g., Java, Maven, and AspectJ).
   
   <details>
     <summary>Example Output</summary>
      
      ```
      [+] Building 193.8s (13/13) FINISHED                                                                                                                                                                  docker:default
      => [internal] load build definition from Dockerfile                                                                                                                                                            0.0s
      => => transferring dockerfile: 2.79kB                                                                                                                                                                          0.0s
      => [internal] load metadata for docker.io/library/ubuntu:20.04                                                                                                                                                 1.0s
      => [auth] library/ubuntu:pull token for registry-1.docker.io                                                                                                                                                   0.0s
      => [internal] load .dockerignore                                                                                                                                                                               0.0s
      => => transferring context: 2B                                                                                                                                                                                 0.0s
      => [1/7] FROM docker.io/library/ubuntu:20.04@sha256:8e5c4f0285ecbb4ead070431d29b576a530d3166df73ec44affc1cd27555141b                                                                                           2.3s
      ...
      => [2/7] RUN   apt-get update &&   apt-get install -y software-properties-common &&   apt-get install -y git &&   apt-get update &&   apt-get install -y python3 python3-pip python-is-python3 &&   rm -rf   175.3s
      => [3/7] RUN useradd -ms /bin/bash -c "tracemop" tracemop && echo "tracemop:docker" | chpasswd && adduser tracemop sudo                                                                                        0.6s
      => [4/7] WORKDIR /home/tracemop/                                                                                                                                                                               0.3s
      => [5/7] RUN   wget http://mirrors.ibiblio.org/apache/maven/maven-3/3.8.8/binaries/apache-maven-3.8.8-bin.tar.gz &&   tar -xzf apache-maven-3.8.8-bin.tar.gz && mv apache-maven-3.8.8/ apache-maven/ &&   rm   2.9s
      => [6/7] RUN   cd /home/tracemop &&   wget https://github.com/async-profiler/async-profiler/releases/download/v2.9/async-profiler-2.9-linux-x64.tar.gz &&   tar xf async-profiler-2.9-linux-x64.tar.gz &&   r  1.1s
      => [7/7] COPY --chown=tracemop:tracemop scripts/setup.sh /home/tracemop/setup.sh                                                                                                                               0.1s
      => exporting to image                                                                                                                                                                                          9.9s
      => => exporting layers                                                                                                                                                                                         9.9s
      => => writing image sha256:7bf4db813c720a66f8a341ee953c9e1be42d30bf36e3f5383fa91b2153b910a4
      ```
   </details>

   b. `docker run -it tracemop /bin/bash`

   <details>
      <summary>Example Output</summary>
      
      ```
      To run a command as administrator (user "root"), use "sudo <command>".
      See "man sudo_root" for details.

      tracemop@b664e9fb97ab:~$
      ```
   </details>

   c. `bash setup.sh`  # Run inside Docker container. This command will download TraceMOP, and then build TraceMOP's Maven plugin and Java agents.
   
   <details>
      <summary>Example Output</summary>
   
      ```
      Cloning tracemop repository
      ~ ~
      Cloning into 'tracemop'...
      remote: Enumerating objects: 16507, done.
      ...
      .: [jar, cmf, ./output3519887151673394903/agent-jar6926975155458846089/META-INF/MANIFEST.MF, no-track-no-stats-agent.jar, -C, ./output3519887151673394903/agent-jar6926975155458846089, .]
      no-track-no-stats-agent.jar is generated.
      ~
      ```
   </details>

   Then, run the following commands in a new terminal window:

   d. `docker ps`  # get container id (if you see multiple container, then select the one from the top that uses the `tracemop` image)
   
    <details>
      <summary>Example Output</summary>
   
      ```
      CONTAINER ID   IMAGE      COMMAND       CREATED          STATUS          PORTS     NAMES
      47ad99b6e118   tracemop   "/bin/bash"   13 minutes ago   Up 13 minutes             nice_mcnulty
      ```
   </details>

   e. `docker commit <container-id> softengresearch/tracemop`  # This command will create a new Docker image with TraceMOP installed.

### Setting up without Docker

You can follow the instructions in [OutsideDocker.md](docs/OutsideDocker.md) to install TraceMOP outside the Docker container.

## Using TraceMOP's Maven plugin to monitor Maven projects

In this section, we will show your an example of how to run TraceMOP with trace-collection and TraceMOP without trace-collection. You should run the below example inside the Docker container, or in an environment with TraceMOP installed.

1. Clone a project. For example, you can run the below command:

   `cd ~ && git clone https://github.com/flowpowered/commons project`

   The above example will clone the project [`flowpowered/commons` (sha 0690efd)](https://github.com/flowpowered/commons/tree/0690efd).

2. **Running TraceMOP with trace collection.**

   a. `cd ~/project && git checkout 0690efd`

   b. `mvn edu.cornell:tracemop-maven-plugin:1.0:run`  # This command requires TraceMOP's Maven plugin to be installed, which you have already completed in the setup section above.

   The above example will collect traces for the project, and save results to `target/tracemop` directory.
   The structure of the output directory and where to find violations are explained [here](#output-structure).
   If you want to save traces to another directory instead, then add `-DoutputDirectory=<output-location>` to the above command:

   c. `mvn edu.cornell:tracemop-maven-plugin:1.0:run -DoutputDirectory=another-dir`

3. **Running TraceMOP without trace collection (i.e., only get JavaMOP functionality).** 

   a. `cd ~/project && git checkout 0690efd`

   b. `mvn edu.cornell:tracemop-maven-plugin:1.0:run -DcollectTraces=false`  # This command requires TraceMOP's Maven plugin to be installed, which you have already completed in the setup section above.

   The above example will run RV on the project.

If you would like to run TraceMOP using a shorter command (`mvn tracemop-maven-plugin:run`), you can add TraceMOP to your project's `pom.xml` file.
Check out the [instructions here](docs/AddPluginToPom.md) for more details.

## Using TraceMOP's Java agent to monitor Maven and non-Maven projects

You can follow the [instructions here](docs/BuildAgent.md) to learn how to build and use TraceMOP's Java agent. Also, follow the [instructions here](docs/Valg.md) to learn how to enable Valg (i.e., selective monitoring).

## Output Structure

If running with trace collection, the output directory `all-traces` directory contains 4 files:

| Directory           | Purpose                                             |
| --------------------| ----------------------------------------------------|
| locations.txt       | maps short location IDs to actual line of code      |
| specs-frequency.csv | maps trace IDs to specs and frequencies             |
| specs-test.csv      | maps trace IDs to tests that generated the traces   |
| unique-traces.txt   | list of unique traces (trace ID, frequench, trace) |

If TraceMOP raised violations during RV, TraceMOP will save the violations to file.
For the `flowpowered/commons` example above, when running in Docker, the violation file is located at: `~/project/violation-counts`

You can learn how to interpret these files in [TracesFiles.md](docs/TracesFiles.md).
[You can also watch our demo video for a more detailed explanation.](https://youtu.be/xxtUUBlsCJc?feature=shared&t=71)

## Configuration

Depending on a project’s characteristics (e.g., when there are very few tests), naively collecting all traces can outperform our new data structures. So, we provide an option to switch to this naive approach:

```bash
export TRACEMOP_DB=simple  // Store traces in simple data structure
export TRACEMOP_DB=trie  // Store traces in prefix tree like data structure
```

## Debug flaky violations

You can use TraceMOP to help debug flaky violations. Learn more about it [here](docs/FlakyViolations.md).

## Contributing

We are accepting issues and pull requests. We welcome all who are interested to help fix issues.
