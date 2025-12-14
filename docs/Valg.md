# Valg: Feedback-Guided Selective Monitoring

Valg is an extension of TraceMOP that enables selective monitoring based on feedback from prior monitoring. Instead of creating a monitor at every time step at a monitor creation point, 
Valg learns when monitor creation is likely to observe a unique trace and suppresses redundant monitor creation. The goal of Valg is to reduce runtime overhead while preserving unique traces.

Valg can be enabled when building the TraceMOP Java agent by passing the `-valg` flag to the build script.

## Build a Valg-enabled TraceMOP agent that tracks traces
```bash
cd scripts
bash install.sh true false [optional:true|false] -valg
```

## Build a Valg-enabled TraceMOP agent that does not track traces
```bash
cd scripts
bash install.sh false false [optional:true|false] -valg
```

The optional third argument enables time-series collection (as described in [BuildAgent.md](docs/BuildAgent.md)).

## Per-Spec Hyperparameter Configuration

Valg allows different hyperparameter configurations for different specifications. 
This is controlled using the `-spec` flag. Valg can also disable RL agents for specific specifications using `off`. 
The syntax is `(-spec <spec-name> ["{<alpha>,<epsilon>,<threshold>,<initial-values>}" | off])*`, where the parameters are:

- `<alpha>`: learning rate
- `<epsilon>`: exploration probability
- `<threshold>`: convergence threshold
- `<initial-values>`: initial action values

In the example below, Valg is enabled for `Iterator_HasNext` with custom hyperparameters 
and is disabled for `ArrayDeque_NonNull`, which uses standard monitoring:
```
bash install.sh false false false -spec Iterator_HasNext "{0.5,0.5,0.0001,5.0,5.0}" -spec ArrayDeque_NonNull off
```

## Trajectory Saving
Valg can record trajectories that describe how monitor creation decisions evolve over time. 
Trajectory saving can be enabled using the `-traj` flag. When enabled, TraceMOP generates a trajectories file at `~/project/trajectories`, e.g.,:
```
bash install.sh false false false -spec Iterator_HasNext "{0.5,0.5,0.0001,5.0,5.0}" -traj
```

In the example below, the agent creates monitors for the first three time steps, 
but only the first monitor observes a unique trace, and the agent switches to the `ncreate` action at the time step `t=3`.
```
<0: A=create, R=1.00, Qc=5.00, Qn=0.00> <1: A=create, R=0.00, Qc=1.40, Qn=0.00> <2: A=create, R=0.00, Qc=0.14, Qn=0.00>
<3: A=ncreate, R=0.67, Qc=0.01, Qn=0.00> <4: A=ncreate, R=0.67, Qc=0.01, Qn=0.60> ...
```
