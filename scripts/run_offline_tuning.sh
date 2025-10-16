#!/bin/bash
set -e

PROJECTS=(
"agarciadom/xeger,f3b8a33b0f4438d639150b57b9a0257d50c71bc2"
"albfernandez/javadbf,395265f33bcf9080b02f2102c4b6284921cefae2"
"alibaba/QLExpress,456476288a9c0691b8890ff361605b1f7357acde"
"almondtools/stringsearchalgorithms,19f26f1c06192816b1ff2fb3b86740898d50a44d"
"almson/almson-refcount,ded7fe38d1e84f2af98f1d845d30fcc46aad197b"
"Antibrumm/jackson-antpathfilter,40f3af16e9a32fec910fadfde144c4b58217d5e7"
"asterisk-java/asterisk-java,84d890b8b852b6d3f14768ec651dbb710eacf57b"
"attoparser/attoparser,e1049dcd8261fe315b679029e711a9f5ea03f1cc"
"awslabs/route53-infima,cabce497698e41d610a949e8a5e4a0528170382b"
"brettwooldridge/SparseBitSet,8b32633706533a1fb828e05415dbdbc2c32f3a31"
"chocoteam/choco-graph,f4e7be389df244d4dd9138b81862e98efc9f4603"
"romix/java-concurrent-hash-trie-map,03335a9caa6fc867344c8c015d6dfc2d11ab062f"
"octavian-h/time-series-math,4fad82278e42307458a9faa46c2f13e2ac9816c6"
"codelion/gramtest,355cf41dcf0904e8d28aeb220241df0137c789ca"
"conveyal/osm-lib,4ff7fef141296ffe1d47a89ad40cefda7311566d"
"cowtowncoder/java-uuid-generator,31408f5c088d27766269f905896efe383b38a46e"
"danieldk/dictomaton,b23b48ba03ec43c0ce2ef1be85c0cbb8203a87b2"
"davidmoten/bplustree,761c1da3772772260520c41d9e9be8405931b4e5"
"davidmoten/rtree,f1da3d62dbdab20654b63da91510c51a32e8b8a8"
"davidmoten/rtree-multi,858da976228add017a4c9180e1e3968a26dedb4c"
"davidmoten/rtree2,7da3d606a192321217f6b780b6ab4fdcd8e3e1ed"
"dperezcabrera/jpoker,e771da71c3c5dc25b99355e41491933e78732e3e"
"eightbitjim/cassette-nibbler,788a04b49fe3d4c0905bd26109994d4952ad5db2"
"ElectronicChartCentre/java-vector-tile,f564aa95050f89e97aad4a0e93d7a9fbce3fcd42"
"f4b6a3/uuid-creator,f9e6e6d02ecc8dac16a56e4fab1bd24caec8171c"
"flipkart-incubator/databuilderframework,d739c964a4dda5fa212a5c52da61bc39d62ebe3a"
"fraunhoferfokus/Fuzzino,75d20f05db8dfbfe035a6df0b9eb7c88703886fc"
"fusion-jena/JaroWinklerSimilarity,03266936fa5350724979d78fef4213a20189ed0a"
"ghaffarian/progex,b8c75255305ba45dbcf7d895f81f415375edcd5e"
"Grundlefleck/ASM-NonClassloadingExtensions,8eb992f35817b2ef884a8dcaa502f829d85e5f90"
"hlavki/jlemmagen,43f7c636811a1f7fea7ae7db403ec6f3ec60e9b7"
"houbb/sensitive-word,344edf54f635c24e036dff4862d19a52603347c7"
"huaban/jieba-analysis,e46e44ba2c7b8534a9b489801f4ce0d38378ad1a"
"indeedeng/vowpal-wabbit-java,3a9a92ac11a69d265656806e84bc9c05d138ef88"
"jahlborn/jackcess,22bf8a8642c84fc500f5c66ef59de1f7211f93a3"
"kapoorlabs/kiara,40d11fb7e8d295c374f63d0e3b83d553d5374736"
"lexburner/consistent-hash-algorithm,a8e712fee24a1982f263d2f58ec770bfe34ac7e8"
"LiveRamp/HyperMinHash-java,9f5267dbaab4d82dcaf2eff4ceb4d9a1599bfeae"
"MezereonXP/AnomalyDetectTool,03db9e457cb9d1866c82c5d24618fbabcbdf48a8"
"mocnik-science/geogrid,702f29dffa36d82edf9744a79400a63fa7dde4db"
)

for entry in "${PROJECTS[@]}"; do
    repo="${entry%,*}"
    sha="${entry#*,}"
    proj_name=$(basename "$repo")
    outdir="offline-tuning/${proj_name}"

    echo "=== [${proj_name}] Step 1: install"
    bash install.sh false false -valg-create -traj > /dev/null 2>&1

    echo "=== [${proj_name}] Step 2: non-collect traces"
    bash not_collect_traces.sh "$repo" "$sha" "${outdir}/output-traj" > /dev/null 2>&1

    traj_file="${outdir}/output-traj/project/trajectories"
    if [[ ! -f "$traj_file" ]]; then
        echo "Trajectories file missing for ${proj_name}, skipping..."
        continue
    fi

    echo "=== [${proj_name}] Step 3: parameter tuning"
    results_json=$(python3 param_tune.py "$traj_file" 300 | tee /dev/tty)
    echo "$results_json" > "${outdir}/params.json"

    echo "=== [${proj_name}] Step 4: reinstall with tuned specs"
    specs_args=()
    for spec in $(echo "$results_json" | jq -r 'keys[]'); do
        alpha=$(echo "$results_json" | jq -r ".\"$spec\".alpha")
        epsilon=$(echo "$results_json" | jq -r ".\"$spec\".epsilon")
        threshold=$(echo "$results_json" | jq -r ".\"$spec\".threshold")
        Qc_init=$(echo "$results_json" | jq -r ".\"$spec\".Qc_init")
        Qn_init=$(echo "$results_json" | jq -r ".\"$spec\".Qn_init")
        specs_args+=(-spec "$spec" "{$alpha,$epsilon,$threshold,$Qc_init,$Qn_init}")
    done

    echo " -> Running: bash install.sh true false -valg ${specs_args[*]}"
    bash install.sh true false -valg "${specs_args[@]}" > /dev/null 2>&1

    echo "=== [${proj_name}] Step 5: collect traces"
    bash collect_traces.sh "$repo" "$sha" "${outdir}/output-traces" > "${outdir}/output-traces/log" 2>&1 &
done
