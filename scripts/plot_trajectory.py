import sys
import matplotlib.pyplot as plt
import math
import os
import random

INIT_C = 1.0
INIT_N = 1.0
DEFAULT_THRESHOLD = 0.01

def parse_main_series_file(filename):
    series_list = []
    current_loc = None
    current_points = []
    with open(filename, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if " @ " in line and not line.startswith("=>"):
                if current_loc is not None:
                    series_list.append((current_loc, current_points))
                current_loc = line
                current_points = []
                continue
            if line.startswith("=>"):
                steps = line[2:].strip().split("> <")
                for seg in steps:
                    val = 1 if "unique" in seg else 0
                    current_points.append(val)
    if current_loc is not None:
        series_list.append((current_loc, current_points))
    return series_list

def parse_trajectory(filename):
    trajectories = []
    current_header = None
    current_points = []
    converged_step = None
    with open(filename, 'r') as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            if " @ " in line and not line.startswith("=>"):
                if current_header is not None:
                    trajectories.append((current_header, current_points, converged_step))
                current_header = line
                current_points = []
                converged_step = None
                continue
            if line.startswith("=>"):
                steps = line[2:].strip().split("> <")
                for seg in steps:
                    seg = seg.strip().lstrip("<").rstrip(">")
                    try:
                        _, rest = seg.split(": ", 1)
                        items = rest.split(", ")
                        action = None
                        for item in items:
                            k, v = item.split("=")
                            if k == "A":
                                action = v.strip().lower() == "create"
                        if action is not None:
                            current_points.append(1 if action else 0)
                    except:
                        continue
                    if "[converged]" in seg and converged_step is None:
                        converged_step = len(current_points)
    if current_header is not None:
        trajectories.append((current_header, current_points, converged_step))
    return trajectories

def decide_action(Qc, Qn, alpha, epsilon, time_step):
    if time_step == 0:
        return INIT_N <= INIT_C
    if random.random() < epsilon:
        return random.choice([True, False])
    return Qn <= Qc

def simulate_series(series, alpha, epsilon):
    Qc, Qn = INIT_C, INIT_N
    num_tot, num_dup = 0, 0
    points = []
    converged_step = None
    for t, true_action in enumerate(series):
        if converged_step is not None:
            break
        action = decide_action(Qc, Qn, alpha, epsilon, t)
        points.append(1 if action else 0)
        if action:
            num_tot += 1
            num_dup += (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            Qc += alpha * (reward - Qc)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            Qn += alpha * (reward - Qn)
        if abs(1.0 - abs(Qc - Qn)) < DEFAULT_THRESHOLD:
            converged_step = t + 1
    return points, converged_step

def decide_action_ducb(sumC, sumN, countC, countN, time_step, C):
    if time_step == 0:
        return sumN <= sumC
    DUCBc = sumC / countC + C * math.sqrt(math.log(time_step) / countC)
    DUCBn = sumN / countN + C * math.sqrt(math.log(time_step) / countN)
    return DUCBn <= DUCBc

def simulate_series_ducb(series, gamma, C):
    sumC, sumN = INIT_C, INIT_N
    countC, countN = 1.0, 1.0
    num_tot, num_dup = 0, 0
    points = []
    converged_step = None
    for t, true_action in enumerate(series):
        if converged_step is not None:
            break
        action = decide_action_ducb(sumC, sumN, countC, countN, t, C)
        points.append(1 if action else 0)
        if action:
            num_tot += 1
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
            converged_step = t + 1
    return points, converged_step

def sample_beta(alpha, beta):
    return random.betavariate(alpha, beta)

def simulate_series_dsts(series, gamma):
    alphaC, alphaN = max(INIT_C, 1.0), max(INIT_N, 1.0)
    betaC, betaN = 1.0, 1.0
    num_tot, num_dup = 0, 0
    points = []
    converged_step = None
    for t, true_action in enumerate(series):
        if converged_step is not None:
            break
        if t == 0:
            action = alphaN <= alphaC
        else:
            thetaC = sample_beta(alphaC, betaC)
            thetaN = sample_beta(alphaN, betaN)
            action = thetaN <= thetaC
        points.append(1 if action else 0)
        if action:
            num_tot += 1
            num_dup += (true_action == 0)
            reward = 1.0 if true_action == 1 else 0.0
            alphaC += reward
            betaC += (1 - reward)
        else:
            reward = (num_dup / num_tot) if num_tot > 0 else 0.0
            alphaN += reward
            betaN += (1 - reward)

        alphaC = max(alphaC * (1.0 - gamma), 1e-6)
        betaC = max(betaC * (1.0 - gamma), 1e-6)
        alphaN = max(alphaN * (1.0 - gamma), 1e-6)
        betaN = max(betaN * (1.0 - gamma), 1e-6)

        MuC = alphaC / (alphaC + betaC)
        MuN = alphaN / (alphaN + betaN)
        if abs(1.0 - abs(MuC - MuN)) < DEFAULT_THRESHOLD:
            converged_step = t + 1
    return points, converged_step

def build_all_plots(main_series, overlay_map, target_location):
    output_dir = "plots"
    os.makedirs(output_dir, exist_ok=True)
    file_index = 1

    for header, points in main_series:
        if header != target_location:
            continue

        overlay_points, overlay_conv = overlay_map.get(header, ([], None))
        num_chunks = math.ceil(len(overlay_points) / 300)
        max_chunks_per_file = 25

        for start_chunk in range(0, num_chunks, max_chunks_per_file):
            end_chunk = min(start_chunk + max_chunks_per_file, num_chunks)
            chunks_in_fig = end_chunk - start_chunk
            fig_height = max(chunks_in_fig * 2, 6)
            fig, axes = plt.subplots(chunks_in_fig, 1, figsize=(30, fig_height), constrained_layout=True)
            if chunks_in_fig == 1:
                axes = [axes]

            ax_index = 0
            for chunk_i in range(start_chunk, end_chunk):
                ax = axes[ax_index]
                start = chunk_i * 300
                end = min(len(points), (chunk_i + 1) * 300)
                xs = list(range(start, end))
                ys = points[start:end]

                xs_zero = [x for x, y in zip(xs, ys) if y == 0]
                xs_one = [x for x, y in zip(xs, ys) if y == 1]
                ys_zero = [0] * len(xs_zero)
                ys_one = [1] * len(xs_one)

                ax.scatter(xs_zero, ys_zero, color="red", s=25)
                ax.scatter(xs_one, ys_one, color="blue", s=25)

                if overlay_points:
                    o_end = min(len(overlay_points), end)
                    o_ys = overlay_points[start:o_end]
                    o_xs = list(range(start, o_end))
                    if len(o_xs) > 1:
                        ax.plot(o_xs, o_ys, linewidth=2, linestyle='--', color='lightgray')
                    elif len(o_xs) == 1:
                        ax.scatter(o_xs, o_ys, s=25)

                if overlay_conv is not None:
                    ax.scatter(c, points[overlay_conv], color="black", s=25)
                    ax.scatter(c, overlay_points[overlay_conv-1], marker='x', color="black", s=25)

                padding = 0.5
                max_x = end - 1
                ax.set_xlim(start - padding, max_x + padding)
                ax.set_xticks([])
                ax.set_xticklabels([])
                ax.set_ylim(-0.1, 1.1)
                ax.set_yticks([0, 1])
                ax.set_xlabel("Time Step", fontsize=12)
                ax.set_ylabel("Action", fontsize=12)
                ax.tick_params(axis='x', labelsize=10)
                ax.tick_params(axis='y', labelsize=10)
                if chunk_i == start_chunk:
                    ax.set_title(f"{header}", fontsize=14)
                ax_index += 1

            out_file = os.path.join(output_dir, f"{file_index}.png")
            plt.savefig(out_file, dpi=150)
            plt.close()
            file_index += 1

def main():
    if len(sys.argv) < 3:
        print("Usage: python plot_series.py <main_series_file> <target_location> [trajectory_or_algo+params]")
        sys.exit(1)

    main_file = sys.argv[1]
    target_location = sys.argv[2]
    main_series = parse_main_series_file(main_file)

    overlay_series = None
    if len(sys.argv) > 3:
        third_arg = sys.argv[3]
        if os.path.isfile(third_arg):
            overlay_trajectories = parse_trajectory(third_arg)
            overlay_series = {loc: (points, conv) for loc, points, conv in overlay_trajectories}
        else:
            algorithm = third_arg.lower()
            params = [float(x) for x in sys.argv[4:]]
            overlay_series = {}
            for loc, points, _ in main_series:
                if algorithm == "default":
                    alpha, epsilon = params
                    sim_points, conv_step = simulate_series(points, alpha, epsilon)
                    overlay_series[loc] = (sim_points, conv_step)
                elif algorithm == "ducb":
                    gamma, C = params
                    sim_points, conv_step = simulate_series_ducb(points, gamma, C)
                    overlay_series[loc] = (sim_points, conv_step)
                elif algorithm == "dsts":
                    gamma = params[0]
                    sim_points, conv_step = simulate_series_dsts(points, gamma)
                    overlay_series[loc] = (sim_points, conv_step)
                else:
                    raise ValueError(f"Unknown algorithm: {algorithm}")

    build_all_plots(main_series, overlay_series, target_location)

if __name__ == "__main__":
    main()

