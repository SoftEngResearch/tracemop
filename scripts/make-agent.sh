#!/bin/bash
#
# Create Java agent for TraceMOP
# Usage: make-agent.sh <property-directory> <output-directory> <verbose-mode> <tracking-mode> <trace-dir> <agent-name> <db-conf> <stats> <series> <violation-from-ajc> <valg> <traj> [spec...]
# Source: https://github.com/SoftEngResearch/tracemop/blob/master/scripts/make-agent.sh
#
SCRIPT_DIR=$(cd $(dirname $0) && pwd)

if [[ $# -lt 12 ]]; then
    echo "Usage: $0 property-directory output-directory verbose-mode tracking-mode trace-dir agent-name db-conf stats series valg traj [spec...]"
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
series=$9
violation_from_ajc=${10}
valg=${11}
shift 11

valg_value=""
if [[ $# -gt 0 && "$1" =~ ^\{[0-9.+-]+(,[0-9.+-]+){4}\}$ ]]; then
    valg_value="$1"
    shift
fi
traj="$1"
shift

spec_args=()
native=false
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
    elif [[ "$1" == "-nativeindexingtree" ]]; then
        native=true
        shift 1
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
  
    if [[ "$series" == "true" ]]; then
        rv_monitor_flag+=("-series")
        if [[ "${track}" != "track" ]]; then
            rv_monitor_flag+=("-internalBehaviorObserving" "-trackEventLocations" "-artifactsDir" "$trace_dir" "-dbConfigFile" "$db_conf")
        fi
    fi

    if [[ "$valg" == "true" ]]; then
        rv_monitor_flag+=("-valg")
        if [[ -n "$valg_value" ]]; then
            rv_monitor_flag+=("-default" "$valg_value")
        fi
    fi    
    
    if [[ "$traj" == "true" ]]; then
        rv_monitor_flag+=("-traj")
    fi
    if [[ "$native" == "true" ]]; then
        rv_monitor_flag+=("-nativeindexingtree")
    fi
    rv_monitor_flag+=("${spec_args[@]}")
    cp ${SCRIPT_DIR}/BaseAspect_new.aj ${props_dir}/BaseAspect.aj
    
    if [[ "${valg}" != "true" ]]; then
        echo "Flags for javamop: ${javamop_flag}"
    fi

    for spec in ${prop_files}; do
        spec_basename=$(basename "${spec}" .mop)
        disable_valg_for_this_spec=false
        spec_flag=""

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

        if [[ "$disable_valg_for_this_spec" == false && ( "$valg" == "true" || "$traj" == "true" ) ]]; then
            spec_flag="-valg"
        fi

        if [[ "$valg" == "true" ]]; then
            echo "Flags for javamop [${spec_basename}]: ${javamop_flag} ${spec_flag}"  
    	fi
    	javamop -baseaspect ${props_dir}/BaseAspect.aj -emop "${spec}" ${javamop_flag} ${spec_flag} 
    done

    rm -rf ${props_dir}/classes/mop; mkdir -p ${props_dir}/classes/mop
    
    echo "Flags for rv-monitor: ${rv_monitor_flag[@]}"
    rv-monitor -merge -d "${props_dir}/classes/mop/" ${props_dir}/*.rvm "${rv_monitor_flag[@]}"

    # Native codegen references java.lang.rv.* (added by the patched JDK)
    # and org.aspectj.lang.JoinPoint.StaticPart (from aspectjrt). Use the
    # patched JDK's javac for the first; pass aspectjrt.jar explicitly for
    # the second. Stock codegen doesn't need either, but the extras are
    # harmless.
    local javac_cmd="javac"
    if [[ -n "${PATCHED_JDK}" && -x "${PATCHED_JDK}/bin/javac" ]]; then
        javac_cmd="${PATCHED_JDK}/bin/javac"
    fi
    local extra_cp=""
    if [[ -n "${ASPECTJRT_JAR}" && -f "${ASPECTJRT_JAR}" ]]; then
        extra_cp="${ASPECTJRT_JAR}"
    else
        local found_aj=$(find "${HOME}/.m2/repository/org/aspectj/aspectjrt" -name "aspectjrt-*.jar" 2>/dev/null | sort -V | tail -1)
        if [[ -n "${found_aj}" ]]; then extra_cp="${found_aj}"; fi
    fi
    ${javac_cmd} -cp "${extra_cp}:${CLASSPATH:-}" ${props_dir}/classes/mop/*.java
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
