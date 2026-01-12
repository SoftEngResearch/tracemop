import os
import re
import math
import optuna
import random
import sys
import json
import logging
import argparse
logging.getLogger("optuna").setLevel(logging.WARNING)

DECAY_RATE = 0.9
DEFAULT_THRESHOLD = 0.0001
DEFAULT_QC = 5.0
DEFAULT_QN = 0.0

def parse_all_specs(file_path):
    specs = set()
    with open(file_path, "r") as f:
        for line in f:
            if "@" in line:
                specs.add(line.split("@")[0].strip())
    return sorted(specs)

def parse_trajectories_file(file_path, spec_name):
    traces = []
    current_spec = None
    with open(file_path, "r") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if "@" in line:
                current_spec = line.split("@")[0].strip()
            elif line.startswith("=>") and current_spec == spec_name:
                steps = re.findall(r"<(\d+):\s*A=(create|ncreate),\s*R=([\d\.]+),", line)
                trace = [(int(t), int(float(r))) for t, a, r in steps if a == "create"]
                if trace:
                    traces.append(trace)
    return traces

def fill_missing(trace, decay=DECAY_RATE):
    if not trace:
        return []
    max_time = max(t for t, _ in trace)
    true_values = {t: v for t, v in trace}
    inferred, weights = [], {0: 0.0, 1: 0.0}
    for t in range(max_time + 1):
        for v in (0, 1):
            weights[v] *= math.exp(-decay)
        if t in true_values:
            v = true_values[t]
            weights[v] += 1.0
            inferred.append(v)
        else:
            inferred.append(1 if weights[1] >= weights[0] else 0)
    return inferred

def decide_action(Qc, Qn, alpha, epsilon, time_step):
    if time_step == 0:
        return True
    if random.random() < epsilon:
        return random.choice([True, False])
    return Qn <= Qc

def simulate_trace(trace, alpha, epsilon):
    Qc, Qn = DEFAULT_QC, DEFAULT_QN
    num_tot, num_dup, converged, converged_action = 0, 0, False, None
    for t, true_action in enumerate(trace):
        if converged:
            if converged_action:
                num_tot += 1
                num_dup += (true_action == 0)
            continue
        action = decide_action(Qc, Qn, alpha, epsilon, t)
        if action:
            num_tot += 1
            reward = 1.0 if true_action == 1 else 0.0
            if true_action == 0:
                num_dup += 1
            Qc += alpha * (reward - Qc)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            Qn += alpha * (reward - Qn)
        if abs(1.0 - abs(Qc - Qn)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = Qn <= Qc
    return num_tot - num_dup

def make_objective(spec, traces):
    def objective(trial):
        alpha = round(trial.suggest_float("alpha", 0.01, 0.99, step=0.01), 2)
        epsilon = round(trial.suggest_float("epsilon", 0.01, 0.99, step=0.01), 2)
        total = sum(simulate_trace(trace, alpha, epsilon) for trace in traces)
        return total
    return objective

def tune_all_specs(traj_file, n_trials):
    specs = parse_all_specs(traj_file)
    total = len(specs)
    processed = 0

    results = {}
    for spec in specs:
        partial_traces = parse_trajectories_file(traj_file, spec)
        if not partial_traces:
            continue
        traces = [fill_missing(trace) for trace in partial_traces]
        study = optuna.create_study(direction="maximize")
        study.optimize(make_objective(spec, traces), n_trials=n_trials, show_progress_bar=False)
        best = {
            "alpha": round(study.best_params["alpha"], 2),
            "epsilon": round(study.best_params["epsilon"], 2),
            "threshold": DEFAULT_THRESHOLD,
            "Qc_init": DEFAULT_QC,
            "Qn_init": DEFAULT_QN,
        }
        results[spec] = best
        processed += 1
        print(f"[{spec} ({processed}/{total})] alpha={best['alpha']}, epsilon={best['epsilon']}, "
              f"threshold={best['threshold']}, Qc_init={best['Qc_init']}, Qn_init={best['Qn_init']}", flush=True)
    return results

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("traj_file", help="Path to trajectories file")
    parser.add_argument("n_trials", type=int, help="Number of trials")
    parser.add_argument("out_file", help="Output JSON file")

    args = parser.parse_args()

    traj_file = args.traj_file
    n_trials = args.n_trials
    out_file = args.out_file

    if not os.path.isfile(traj_file):
        sys.exit(f"[ERROR] Missing trajectories file: {traj_file}")

    print(f"[INFO] Tuning from {traj_file}", flush=True)
    results = tune_all_specs(traj_file, n_trials)

    with open(out_file, "w") as f:
        json.dump(results, f, indent=4)

    print(f"[INFO] Wrote {out_file}", flush=True)

if __name__ == "__main__":
    main()
