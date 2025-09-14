#!/bin/bash
#
# Create Java agent for TraceMOP
# Usage: make-agent.sh <property-directory> <output-directory> <verbose-mode> <tracking-mode> <trace-dir> <agent-name> <db-conf> [stats] [violation-from-ajc]
# Source: https://github.com/SoftEngResearch/tracemop/blob/master/scripts/make-agent.sh
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

if [[ $# -lt 7 ]]; then
    echo "Usage: $0 property-directory output-directory verbose-mode tracking-mode trace-dir agent-name db-conf stats violation-from-ajc"
    echo "       verbose-mode: {verbose|quiet}"
    echo "       tracking-mode: {track|no-track}"
    echo "       db-conf: file containing the database configurations to use"
    echo "       stats: {stats|no-stats}, optional default to no-stats"
    echo "       violation-from-ajc: {true|false}, optional default to true"
    echo "       valg: {true|false}, optional default to disabling valg"
    echo "       spec: hyperparameters for a spec, or disabling"
    exit
fi

props_dir=$1
out_dir=$2
mode=$3
track=$4
trace_dir=$5
agent_name=$6
db_conf=$7
stats=$8
violation_from_ajc=$9
valg=${10}
shift 10

traj="false"

if [[ "$#" -gt 0 && ( "${!#}" == "true" || "${!#}" == "false" ) ]]; then
  traj="${!#}"
  set -- "${@:1:$(($#-1))}"
fi

spec_args=()
while [[ $# -gt 0 ]]; do
    if [[ "$1" == "-spec" ]]; then
        if [[ $# -lt 3 ]]; then
            echo "[make-agent.sh] Missing arguments for -spec"
            exit 1
        fi
        spec_name="$2"
        spec_value="$3"
        spec_args+=("-spec" "$spec_name" "$spec_value")
        shift 3
    else
        break
    fi
done

function build_agent() {
    local agent_name=$1
    local prop_files=${props_dir}/*.mop
    local javamop_flag=""
    local rv_monitor_flag=()

    if [[ ${stats} == "stats" ]]; then
        # statistics: add -s flag to both javamop and rv-monitor
        javamop_flag="-s"
        rv_monitor_flag+=("-s")
    fi
    
    if [[ ${track} == "track" ]]; then
        # collect traces, add flags to rv-monitor, and add -internalBehaviorObserving to javamop
        javamop_flag="${javamop_flag} -internalBehaviorObserving" # this basically add AspectJ's thisJoinPointStaticPart to method signatures, because to collect traces, we must get location from ajc
        rv_monitor_flag+=("-trackEventLocations" "-computeUniqueTraceStats" "-storeEventLocationMapFile" "-artifactsDir" "$trace_dir" "-dbConfigFile" "$db_conf")
    fi
    
    if [[ ${violation_from_ajc} != "false" ]]; then
        # get location from AspectJ (default), add -locationFromAjc flag to both javamop and rv-monitor
        javamop_flag="${javamop_flag} -locationFromAjc"
        rv_monitor_flag+=("-locationFromAjc")
    fi
   
    if [[ "$valg" == "true" ]]; then
        rv_monitor_flag+=("-valg")
    fi

    rv_monitor_flag+=("${spec_args[@]}")

    if [[ "$traj" == "true" ]]; then
        rv_monitor_flag+=("-traj")
    fi
    cp ${SCRIPT_DIR}/BaseAspect_new.aj ${props_dir}/BaseAspect.aj
    
    if [[ "${valg}" != "true" ]]; then
        echo "Flags for javamop: ${javamop_flag}"
    fi

    for spec in ${prop_files}; do
        spec_basename=$(basename "${spec}" .mop)
        spec_flag=""

        if [[ "${valg}" == "true" ]]; then
            disable_valg_for_this_spec=false
            for ((i = 0; i < ${#spec_args[@]}; i += 3)); do
                if [[ "${spec_args[i]}" == "-spec" ]]; then
                    spec_name="${spec_args[i+1]}"
                    spec_config="${spec_args[i+2]}"
                    if [[ "$spec_name" == "$spec_basename" && "$spec_config" == "off" ]]; then
                        disable_valg_for_this_spec=true
                        break
                    fi
                fi
            done
            if [[ "$disable_valg_for_this_spec" == false ]]; then
                spec_flag="-valg"
            fi
            echo "Flags for javamop [${spec_basename}]: ${javamop_flag} ${spec_flag}"
        fi
        javamop -baseaspect ${props_dir}/BaseAspect.aj -emop "${spec}" ${javamop_flag} ${spec_flag}
    done

    rm -rf ${props_dir}/classes/mop; mkdir -p ${props_dir}/classes/mop
    
    echo "Flags for rv-monitor: ${rv_monitor_flag[@]}"
    rv-monitor -merge -d "${props_dir}/classes/mop/" ${props_dir}/*.rvm "${rv_monitor_flag[@]}"

    javac ${props_dir}/classes/mop/*.java
    if [ "${mode}" == "verbose" ]; then
        echo "AGENT IS VERBOSE!"
        javamopagent -m -emop ${props_dir}/ ${props_dir}/classes -n ${agent_name} -v
    elif [ "${mode}" == "quiet" ]; then
        echo "AGENT IS QUIET!"
        javamopagent -emop ${props_dir}/ ${props_dir}/classes -n ${agent_name} -v
    fi

    if [[ ${out_dir} != "." ]]; then
        mv ${agent_name}.jar ${out_dir}
    fi
}

mkdir -p ${out_dir}
build_agent ${agent_name}
