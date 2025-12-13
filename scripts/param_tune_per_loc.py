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

DEFAULT_THRESHOLD = 0.0001
INIT_C = 5.0
INIT_N = 0.0

def parse_locations(file_path):
    locations = {}
    current_loc = None
    header_re = re.compile(r"^(.*?) @ (.*?:\d+)")
    step_re = re.compile(r"<\d+:\s*(unique|redundant)\s*>")
    with open(file_path) as f:
        for line in f:
            line = line.rstrip("\n")
            m = header_re.match(line)
            if m:
                current_loc = m.group(1) + " @ " + m.group(2)
                continue
            line = line.strip()
            if line.startswith("=>") and current_loc is not None:
                rs = [1 if r == "unique" else 0 for r in step_re.findall(line)]
                if rs:
                    locations[current_loc] = rs
    return locations

def decide_action(Qc, Qn, alpha, epsilon, time_step):
    if time_step == 0:
        return INIT_N <= INIT_C
    if random.random() < epsilon:
        return random.choice([True, False])
    return Qn <= Qc

def simulate_series(series, alpha, epsilon):
    Qc, Qn = INIT_C, INIT_N
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(series):
        if converged:
            if converged_action:
                num_tot += 1
                num_uniq += (true_action == 1)
                num_dup += (true_action == 0)
            continue
        else:
            action = decide_action(Qc, Qn, alpha, epsilon, time_step)
        if action:
            num_tot += 1
            num_uniq += (true_action == 1)
            num_dup += (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            Qc += alpha * (reward - Qc)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            Qn += alpha * (reward - Qn)
        if abs(1.0 - abs(Qc - Qn)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = Qn <= Qc
    return num_tot, num_uniq, num_dup

def decide_action_ducb(sumC, sumN, countC, countN, time_step, C):
    if time_step == 0:
        return sumN <= sumC
    DUCBc = sumC / countC + C * math.sqrt(math.log(time_step) / countC)
    DUCBn = sumN / countN + C * math.sqrt(math.log(time_step) / countN)
    return DUCBn <= DUCBc

def simulate_series_ducb(series, gamma, C):
    sumC, sumN = INIT_C, INIT_N
    countC, countN = 1.0, 1.0
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(series):
        if converged:
            if converged_action:
                num_tot += 1
                num_uniq += (true_action == 1)
                num_dup += (true_action == 0)
            continue
        else:
            action = decide_action_ducb(sumC, sumN, countC, countN, time_step, C)
        if action:
            num_tot += 1
            num_uniq += (true_action == 1)
            num_dup += (true_action == 0)
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
    return num_tot, num_uniq, num_dup

def sample_beta(alpha, beta, rng):
    y1 = sample_gamma(alpha, rng)
    y2 = sample_gamma(beta, rng)
    return y1 / (y1 + y2)

def sample_gamma(shape, rng):
    if shape < 1.0:
        u = rng.random()
        y = sample_gamma(1.0 + shape, rng)
        result = y * (u ** (1.0 / shape))
        return max(result, 1e-12)
    d = shape - 1.0 / 3.0
    c = 1.0 / math.sqrt(9.0 * d)
    while True:
        x = rng.gauss(0, 1)
        v = 1 + c * x
        if v <= 0:
            continue
        v = v**3
        u = rng.random()
        if u < 1 - 0.0331 * x**4:
            return d * v
        if math.log(u) < 0.5 * x**2 + d * (1 - v + math.log(v)):
            return d * v

def simulate_series_dsts(series, gamma):
    rng = random.Random()
    alphaC, alphaN = max(INIT_C, 1.0), max(INIT_N, 1.0)
    betaC, betaN = 1.0, 1.0
    num_tot, num_uniq, num_dup, converged, converged_action = 0, 0, 0, False, None
    for time_step, true_action in enumerate(series):
        if converged:
            if converged_action:
                num_tot += 1
                num_uniq += (true_action == 1)
                num_dup += (true_action == 0)
            continue
        else:
            if time_step == 0:
                action = alphaN <= alphaC
            else:
                thetaC = sample_beta(alphaC, betaC, rng)
                thetaN = sample_beta(alphaN, betaN, rng)
                action = thetaN <= thetaC
        if action:
            num_tot += 1
            num_uniq += (true_action == 1)
            num_dup += (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            alphaC += reward
            betaC += (1 - reward)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            alphaN += reward
            betaN += (1 - reward)

        alphaC = max(alphaC * (1.0 - gamma), 1e-6)
        betaC  = max(betaC  * (1.0 - gamma), 1e-6)
        alphaN = max(alphaN * (1.0 - gamma), 1e-6)
        betaN  = max(betaN  * (1.0 - gamma), 1e-6)

        MuC = alphaC / (alphaC + betaC)
        MuN = alphaN / (alphaN + betaN)
        if abs(1.0 - abs(MuC - MuN)) < DEFAULT_THRESHOLD:
            converged = True
            converged_action = MuN <= MuC
    return num_tot, num_uniq, num_dup

def make_objective(series, algorithm):
    def objective(trial):
        if algorithm == "default":
            alpha = round(trial.suggest_float("alpha", 0.01, 0.99, step=0.01), 2)
            epsilon = round(trial.suggest_float("epsilon", 0.01, 0.99, step=0.01), 2)
            _, num_uniq, _ = simulate_series(series, alpha, epsilon)
        elif algorithm == "ducb":
            gamma = round(trial.suggest_float("gamma", 0.01, 0.99, step=0.01), 2)
            C = round(trial.suggest_float("C", 0.01, 2.0, step=0.01), 2)
            _, num_uniq, _ = simulate_series_ducb(series, gamma, C)
        elif algorithm == "dsts":
            gamma = round(trial.suggest_float("gamma", 0.01, 0.99, step=0.01), 2)
            _, num_uniq, _ = simulate_series_dsts(series, gamma)
        else:
            raise ValueError(f"Unknown algorithm: {algorithm}")
        return num_uniq
    return objective

def tune_hyperparams(series, algorithm, n_trials=100):
    study = optuna.create_study(direction="maximize")
    study.optimize(make_objective(series, algorithm), n_trials=n_trials, show_progress_bar=False)
    if algorithm == "default":
        return round(study.best_params["alpha"], 2), round(study.best_params["epsilon"], 2)
    elif algorithm == "ducb":
        return round(study.best_params["gamma"], 2), round(study.best_params["C"], 2)
    elif algorithm == "dsts":
        return round(study.best_params["gamma"], 2)
    else:
        raise ValueError(f"Unknown algorithm: {algorithm}")

def main():
    n_trials = 100
    series_dirs = [
        "btree4j/project/time_series",
        "Jaba/project/time_series",
        "kiara/project/time_series",
        "rtree-multi/project/time_series",
        "stringsearchalgorithms/project/time_series"
    ]

    for series_dir in series_dirs:
        project_name = series_dir.split("/")[0]
        output_file = f"results_{project_name}.csv"

        locations = parse_locations(series_dir)

        csv_header = "Loc,Total,Default,Default(tuned),DUCB,DUCB(tuned),DSTS,DSTS(tuned)"
        print(f"\nProcessing {project_name} ({len(locations)} locations)...", flush=True)
        print(csv_header, flush=True)
        output = []

        total_locs = len(locations)
        loc_idx = 0

        for loc, series in locations.items():
            loc_idx += 1
            start_time = time.time()

            total_unique = sum(series)
            total_redundant = len(series) - total_unique

            default_alpha, default_epsilon = 0.9, 0.1
            ducb_gamma, ducb_C = 0.9, 0.2
            dsts_gamma = 0.9

            num_tot, default_unique_default, default_redundant_default = simulate_series(series, default_alpha, default_epsilon)
            num_tot, ducb_unique_default, ducb_redundant_default = simulate_series_ducb(series, ducb_gamma, ducb_C)
            num_tot, dsts_unique_default, dsts_redundant_default = simulate_series_dsts(series, dsts_gamma)

            default_alpha_tuned, default_epsilon_tuned = tune_hyperparams(series, "default", n_trials)
            ducb_gamma_tuned, ducb_C_tuned = tune_hyperparams(series, "ducb", n_trials)
            dsts_gamma_tuned = tune_hyperparams(series, "dsts", n_trials)

            num_tot, default_unique_tuned, default_redundant_tuned = simulate_series(series, default_alpha_tuned, default_epsilon_tuned)
            num_tot, ducb_unique_tuned, ducb_redundant_tuned = simulate_series_ducb(series, ducb_gamma_tuned, ducb_C_tuned)
            num_tot, dsts_unique_tuned, dsts_redundant_tuned = simulate_series_dsts(series, dsts_gamma_tuned)

            redundant_row = [
                loc,
                total_redundant,
                default_redundant_default,
                default_redundant_tuned,
                ducb_redundant_default,
                ducb_redundant_tuned,
                dsts_redundant_default,
                dsts_redundant_tuned
            ]
            unique_row = [
                "",
                total_unique,
                default_unique_default,
                default_unique_tuned,
                ducb_unique_default,
                ducb_unique_tuned,
                dsts_unique_default,
                dsts_unique_tuned
            ]

            elapsed = time.time() - start_time
            print(f"[{loc_idx}/{total_locs}] {loc} (time: {elapsed:.2f}s)", flush=True)
            print(f"Tuned hyperparams -> Default: alpha={default_alpha_tuned}, epsilon={default_epsilon_tuned}; "
                  f"DUCB: gamma={ducb_gamma_tuned}, C={ducb_C_tuned}; "
                  f"DSTS: gamma={dsts_gamma_tuned}", flush=True)

            print(",".join(str(v) for v in redundant_row[1:]), flush=True)
            print(",".join(str(v) for v in unique_row[1:]), flush=True)

            output.append(redundant_row)
            output.append(unique_row)

        with open(output_file, "w") as f:
            f.write(csv_header + "\n")
            for row in output:
                f.write(",".join(str(v) for v in row) + "\n")

        print(f"Finished {project_name}, results saved to {output_file}\n", flush=True)

if __name__ == "__main__":
    main()
