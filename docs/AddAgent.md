# Adding TraceMOP’s Java Agent to a Maven project

Another way to run TraceMOP is to add TraceMOP's Java agent to project's `pom.xml` file.

If the surefire plugin is not in the `pom.xml` file, then add the following code to the `<plugins>` block:
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.1.2</version>
    <configuration>
        <argLine>-javaagent:${PATH-TO-AGENT}/tracemop-agent.jar</argLine>
    </configuration>
</plugin>
```

If surefire plugin is in the `pom.xml` file, then simply add the following to surefire plugin
```xml
<configuration>
    <argLine>-javaagent:${PATH-TO-AGENT}/tracemop-agent.jar</argLine>
</configuration>
```

Next, you have to replace `${PATH-TO-AGENT}` with the absolute path to TraceMOP's Java agent. 

If you are using the TraceMOP Java agent that track traces, you must also run the below commands:
```bash
export OUTPUT_DIRECTORY="set your output directory here"  # specify where to store the traces
export RVMLOGGINGLEVEL=UNIQUE  # skip this command if you want to show all violations in STDOUT. Otherwise, TraceMOP will generate a violation-counts file in the project's directory that contains only the unique violations
export TRACEDB_PATH=${OUTPUT_DIRECTORY}/all-traces
export TRACEDB_CONFIG_PATH=${OUTPUT_DIRECTORY}/.trace-db.config
export TRACEDB_RANDOM=1 && export COLLECT_MONITORS=1 && export COLLECT_TRACES=1
mkdir -p ${TRACEDB_PATH}
```

Lastly, run `mvn test` inside your project's directory to run test with TraceMOP.
