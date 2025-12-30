# Building a TraceMOP Java Agent

TraceMOP’s Java agent allows to integrate it with arbitrary Java programs.
You can download a pre-built agent from [this link](https://github.com/SoftEngResearch/tracemop/releases).

Alternatively, you can build the agent yourself by running the following commands **inside TraceMOP's directory** to build TraceMOP's Java agent.

## Build a TraceMOP agent that can track traces
```bash
cd scripts
bash install.sh true false   # This will generate a track-no-stats-agent.jar file
```

## Build a TraceMOP agent that will not track traces
```bash
cd scripts
bash install.sh false false  # This will generate a no-track-no-stats-agent.jar
```

## Enabling time-series collection

The time-series file records, for each monitor creation location and each time step, whether the monitor observed a unique or redundant trace. Time-series collection can be enabled when building the agent, and TraceMOP generates a file at `~/project/time-series`:
```bash
cd scripts
bash install.sh true false true  # This will generate a track-no-stats-agent.jar with time-series enabled
bash install.sh false false true # This will generate a no-track-no-stats-agent.jar with time-series enabled
```

An example time-series for `Automaton.java:408` is shown below, where `Collection_UnsafeIterator` monitors are created. The monitors created at the time steps 1, 2, 4, 5, and 16 observed a unique trace (check the indices with the value of 1).
```
Collection_UnsafeIterator @ Automaton.java:408
 => [1x2, 0, 1x2, 0x10, 1, 0x161023]
```

If your project uses Maven build system, then you can follow the instructions in [AddAgent.md](AddAgent.md) to add TraceMOP agent to your project.

If your project does not use Maven, your can monitor a Java program using TraceMOP whose main method is in Main.java like so (after compiling Main.java):
```bash
# If you are using the TraceMOP Java agent that track traces, you must also run the below commands:
export OUTPUT_DIRECTORY="set your output directory here"  # specify where to store the traces
export RVMLOGGINGLEVEL=UNIQUE  # skip this command if you want to show all violations in STDOUT. Otherwise, TraceMOP will generate a violation-counts file in the project's directory that contains only the unique violations
export TRACEDB_PATH=${OUTPUT_DIRECTORY}/all-traces
export TRACEDB_CONFIG_PATH=${OUTPUT_DIRECTORY}/.trace-db.config
export TRACEDB_RANDOM=1 && export COLLECT_MONITORS=1 && export COLLECT_TRACES=1
mkdir -p ${TRACEDB_PATH}

# In your project directory, run Main.java with TraceMOP
java -javaagent:${PATH-TO-AGENT}/tracemop-agent.jar Main  # replace ${PATH-TO-AGENT} with the absolute path to TraceMOP's Java agent. 
```
