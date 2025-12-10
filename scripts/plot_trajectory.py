import sys
import matplotlib.pyplot as plt
import math
import os

def parse_trajectories(filename):
    trajectories = []
    current_header = None
    current_points = []
    converged_steps = []

    with open(filename, 'r') as f:
        for line in f:
            line = line.strip()

            if not line:
                continue

            if " @ " in line and not line.startswith("=>"):
                if current_header is not None:
                    trajectories.append((current_header, current_points, converged_steps))

                current_header = line.strip()
                current_points = []
                converged_steps = []
                continue

            if line.startswith("=>"):
                has_converged = "[converged]" in line
                line_clean = line.replace("[converged]", "").strip()

                segments = line_clean[3:].strip().split("> <")

                for seg in segments:
                    seg = seg.strip().lstrip("<").rstrip(">")
                    try:
                        _, rest = seg.split(": ", 1)
                        items = rest.split(", ")

                        R_value = None
                        for item in items:
                            k, v = item.split("=")
                            if k == "R":
                                R_value = float(v)
                        if R_value is not None:
                            current_points.append(int(round(R_value)))

                    except ValueError:
                        continue

                if has_converged:
                    converged_steps.append(len(current_points))

    if current_header is not None:
        trajectories.append((current_header, current_points, converged_steps))

    return trajectories

def build_overlay_map(overlay_trajectories):
    overlay_map = {}
    for header, pts, conv in overlay_trajectories:
        overlay_map[header] = (pts, conv)
    return overlay_map

def build_all_plots(main_trajs, overlay_trajs, target_location):
    overlay_map = build_overlay_map(overlay_trajs)

    output_dir = "plots"
    os.makedirs(output_dir, exist_ok=True)

    file_index = 1
    for header, points, converged_steps in main_trajs:
        if header != target_location:
            continue

        overlay_points, overlay_converged = overlay_map.get(header, ([], []))
        num_chunks = math.ceil(len(points) / 300)
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
                xs_one  = [x for x, y in zip(xs, ys) if y == 1]
                ys_zero = [0] * len(xs_zero)
                ys_one  = [1] * len(xs_one)
                ax.scatter(xs_zero, ys_zero, color="red", s=25)
                ax.scatter(xs_one,  ys_one,  color="blue", s=25)

                if overlay_points:
                    o_end = min(len(overlay_points), end)
                    o_ys = overlay_points[start:o_end]
                    o_xs = list(range(start, o_end))
                    if len(o_xs) > 1:
                        ax.plot(o_xs, o_ys, linewidth=2, linestyle='--', color='lightgray')
                    elif len(o_xs) == 1:
                        ax.scatter(o_xs, o_ys, s=25)

                padding = 0.5
                max_x = max(converged_steps, default=end - 1)
                ax.set_xlim(start - padding, max_x + padding)
                ax.set_xticks([])
                ax.set_xticklabels([])
                ax.set_ylim(-0.1, 1.1)
                ax.set_yticks([0, 1])
                ax.set_xlabel("Time Step", fontsize=12)
                ax.set_ylabel("Reward", fontsize=12)
                ax.tick_params(axis='x', labelsize=10)
                ax.tick_params(axis='y', labelsize=10)

                if chunk_i == start_chunk:
                    ax.set_title(f"{header}", fontsize=14)

                ax_index += 1

            last_ax = axes[-1]
            for c_step in converged_steps:
                last_ax.scatter(c_step, points[-1], color="black", s=25)
            for c_step in overlay_converged:
                last_ax.scatter(c_step, overlay_points[-1], marker='x', color="black", s=25)

            out_file = os.path.join(output_dir, f"{file_index}.png")
            plt.savefig(out_file, dpi=150)
            plt.close()
            file_index += 1

def main():
    if len(sys.argv) != 4:
        print("Usage: python plot_trajectory.py <main_traj.txt> <overlay_traj.txt> <location_name>")
        sys.exit(1)

    main_file = sys.argv[1]
    overlay_file = sys.argv[2]
    target_location = sys.argv[3] 

    main_trajs = parse_trajectories(main_file)
    overlay_trajs = parse_trajectories(overlay_file)

    build_all_plots(main_trajs, overlay_trajs, target_location)

if __name__ == "__main__":
    main()
