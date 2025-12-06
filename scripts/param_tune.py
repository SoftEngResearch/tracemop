import os
import re
import math
import optuna
import random
import sys
import json
import logging
import time
logging.getLogger("optuna").setLevel(logging.WARNING)

# DECAY_RATE = 0.1
DEFAULT_THRESHOLD = 0.0001
INIT_C = 5.0
INIT_N = 0.0

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

"""
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
"""

def decide_action(Qc, Qn, alpha, epsilon, time_step):
    if time_step == 0:
        return INIT_N <= INIT_C
    if random.random() < epsilon:
        return random.choice([True, False])
    return Qn <= Qc

def simulate_trace(trace, alpha, epsilon):
    Qc, Qn = INIT_C, INIT_N
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(trace):
        if converged:
            if converged_action and true_action == 1:
                num_uniq += 1
            continue
        else:
            action = decide_action(Qc, Qn, alpha, epsilon, time_step)
        if action:
            num_tot += 1
            num_uniq, num_dup = num_uniq + (true_action == 1), num_dup + (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            Qc += alpha * (reward - Qc)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            Qn += alpha * (reward - Qn)
        if abs(1.0 - abs(Qc - Qn)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = Qn <= Qc
    return num_uniq 

def decide_action_ducb(sumC, sumN, countC, countN, time_step, C):
    if time_step == 0:
        return sumN <= sumC
    DUCBc = sumC / countC + C * math.sqrt(math.log(time_step) / countC)
    DUCBn = sumN / countN + C * math.sqrt(math.log(time_step) / countN)
    return DUCBn <= DUCBc

def simulate_trace_ducb(trace, gamma, C):
    sumC, sumN = INIT_C, INIT_N
    countC, countN = 1.0, 1.0
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(trace):
        if converged:
            if converged_action and true_action == 1:
                num_uniq += 1
            continue
        else:
            action = decide_action_ducb(sumC, sumN, countC, countN, time_step, C)
        if action:
            num_tot += 1
            num_uniq, num_dup = num_uniq + (true_action == 1), num_dup + (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            sumC = gamma * sumC + reward
            countC = gamma * countC + 1.0
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            sumN = gamma * sumN + reward
            countN = gamma * countN + 1.0
        MuC = sumC / countC
        MuN = sumN / countN
        if abs(1.0 - abs(MuC - MuN)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = MuN <= MuC
    return num_uniq 

def sample_beta(alpha, beta):
    return random.betavariate(alpha, beta)

def simulate_trace_dsts(trace, gamma):
    alphaC, alphaN = INIT_C, INIT_N
    betaC, betaN = 1.0, 1.0
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(trace):
        if converged:
            if converged_action and true_action == 1:
                num_uniq += 1
            continue
        else:
            if time_step == 0:
                action = alphaN <= alphaC
            else:
                thetaC = sample_beta(alphaC, betaC)
                thetaN = sample_beta(alphaN, betaN)
                action = thetaN <= thetaC
        if action:
            num_tot += 1
            num_uniq, num_dup = num_uniq + (true_action == 1), num_dup + (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            alphaC += reward
            betaC  += (1 - reward)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            alphaN += reward
            betaN  += (1 - reward)

        alphaC = max(alphaC * (1.0 - gamma), 1e-6)
        betaC  = max(betaC  * (1.0 - gamma), 1e-6)
        alphaN = max(alphaN * (1.0 - gamma), 1e-6)
        betaN  = max(betaN  * (1.0 - gamma), 1e-6)

        MuC = alphaC / (alphaC + betaC)
        MuN = alphaN / (alphaN + betaN)
        if abs(1.0 - abs(MuC - MuN)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = MuN <= MuC
    return num_uniq

def make_objective(traces, algorithm):
    def objective(trial):
        if algorithm == "default":
            alpha = round(trial.suggest_float("alpha", 0.01, 0.99, step=0.01), 2)
            epsilon = round(trial.suggest_float("epsilon", 0.01, 0.99, step=0.01), 2)
            total = sum(simulate_trace(trace, alpha, epsilon) for trace in traces)
        elif algorithm == "ducb":
            gamma = round(trial.suggest_float("gamma", 0.01, 0.99, step=0.01), 2)
            C = round(trial.suggest_float("C", 0.01, 2.0, step=0.01), 2)
            total = sum(simulate_trace_ducb(trace, gamma, C) for trace in traces)
        elif algorithm == "dsts":
            gamma = round(trial.suggest_float("gamma", 0.01, 0.99, step=0.01), 2)
            total = sum(simulate_trace_dsts(trace, gamma) for trace in traces)
        else:
            raise ValueError(f"Unknown algorithm: {algorithm}")
        return total
    return objective

def tune_all_specs(traj_file, n_trials, algorithm):
    specs = parse_all_specs(traj_file)
    total_specs = len(specs)
    results = {}
    for idx, spec in enumerate(specs, start=1):
        start_time = time.time()
        traces = parse_trajectories_file(traj_file, spec)
        study = optuna.create_study(direction="maximize")
        study.optimize(make_objective(traces, algorithm), n_trials=n_trials, show_progress_bar=False)
        elapsed = time.time() - start_time

        best = {
            "initC": INIT_C,
            "initN": INIT_N,
            "threshold": DEFAULT_THRESHOLD
        }
        if algorithm == "default":
            best.update({
                "alpha": round(study.best_params["alpha"], 2),
                "epsilon": round(study.best_params["epsilon"], 2)
            })
            print(f"[{idx}/{total_specs}] {spec}: alpha={best['alpha']}, epsilon={best['epsilon']}, "
                  f"threshold={DEFAULT_THRESHOLD}, initC={INIT_C}, initN={INIT_N}, time={elapsed:.2f}s", flush=True)
        elif algorithm == "ducb":
            best.update({
                "gamma": round(study.best_params["gamma"], 2),
                "C": round(study.best_params["C"], 2)
            })
            print(f"[{idx}/{total_specs}] {spec}: gamma={best['gamma']}, C={best['C']}, "
                  f"threshold={DEFAULT_THRESHOLD}, initC={INIT_C}, initN={INIT_N}, time={elapsed:.2f}s", flush=True)
        elif algorithm == "dsts":
            best.update({
                "gamma": round(study.best_params["gamma"], 2)
            })
            print(f"[{idx}/{total_specs}] {spec}: gamma={best['gamma']}, "
                  f"threshold={DEFAULT_THRESHOLD}, initC={INIT_C}, initN={INIT_N}, time={elapsed:.2f}s", flush=True)
        else:
            raise ValueError(f"Unknown algorithm: {algorithm}")

        results[spec] = best
    return results

def main():
    if len(sys.argv) != 4:
        sys.exit("Usage: python param_tune.py <trajectories_file> <n_trials> <algorithm>")
    traj_file, n_trials, algorithm = sys.argv[1], int(sys.argv[2]), sys.argv[3].lower()
    results = tune_all_specs(traj_file, n_trials, algorithm)
    with open("tuned_hyperparameters.json", "w") as f:
        json.dump(results, f, indent=4)

if __name__ == "__main__":
    main()
