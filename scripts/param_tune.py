import os
import re
import math
import optuna
import random
import sys
from collections import defaultdict

DECAY_RATE = 0.1
DEFAULT_THRESHOLD = 0.0001
DEFAULT_QC = 5.0
DEFAULT_QN = 0.0

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
                steps = re.findall(r"<(\d+):\s*(create|ncreate),\s*reward=([\d\.]+)", line)
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
    total_reward, num_tot, num_dup, converged = 0.0, 0, 0, False
    for t, true_action in enumerate(trace):
        if converged:
            continue
        action = decide_action(Qc, Qn, alpha, epsilon, t)
        if action:
            num_tot += 1
            reward = 1.0 if true_action == 1 else 0.0
            if true_action == 0: num_dup += 1
            Qc += alpha * (reward - Qc)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            Qn += alpha * (reward - Qn)
        total_reward += reward
        if abs(1.0 - abs(Qc - Qn)) < DEFAULT_THRESHOLD:
            converged = True
    return total_reward

def make_objective(spec, traces):
    def objective(trial):
        alpha = round(trial.suggest_float("alpha", 0.01, 0.99, step=0.01), 2)
        epsilon = round(trial.suggest_float("epsilon", 0.01, 0.99, step=0.01), 2)
        total = sum(simulate_trace(trace, alpha, epsilon) for trace in traces)
        avg_reward = total / len(traces)
        print(f"[{spec}] Trial {trial.number}: alpha={alpha:.2f}, epsilon={epsilon:.2f}, reward={avg_reward:.5f}")
        return avg_reward
    return objective

def main():
    if len(sys.argv) != 4:
        print("Usage: python script.py <trajectories_file> <spec_name> <n_trials>")
        sys.exit(1)
    traj_file, spec_name, n_trials = sys.argv[1], sys.argv[2], int(sys.argv[3])
    partial_traces = parse_trajectories_file(traj_file, spec_name)
    if not partial_traces:
        print(f"No traces found for spec '{spec_name}' in {traj_file}")
        sys.exit(1)
    traces = [fill_missing(trace) for trace in partial_traces]
    study = optuna.create_study(direction="maximize")
    study.optimize(make_objective(spec_name, traces), n_trials=n_trials, show_progress_bar=False)
    print(
        f"\nFinal optimal hyperparameters for '{spec_name}': "
        f"alpha={study.best_params['alpha']:.2f}, "
        f"epsilon={study.best_params['epsilon']:.2f}, "
        f"threshold={DEFAULT_THRESHOLD:.4f}, "
        f"Qc_init={DEFAULT_QC:.2f}, "
        f"Qn_init={DEFAULT_QN:.2f}"
    )

if __name__ == "__main__":
    main()
