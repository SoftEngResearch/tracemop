#!/bin/bash
# compare-codegen.sh <SpecName> — regenerate native indexing-tree monitor code
# from a .mop spec with the CURRENT codegen, and diff it against the OLD
# hand-written reference in aspect-specs-modified-hotspot/.
#
#   bash compare-codegen.sh UnsafeIterator
#   bash compare-codegen.sh UnsafeMapIterator
#
# Available specs: HasNext UnsafeIterator UnsafeMapIterator UnsafeSyncCollection UnsafeSyncMap
#
# Structural note: the OLD reference keeps aspect+monitor+indexing in ONE
# <Spec>MonitorAspect.aj. The CURRENT pipeline splits it: javamop emits the
# aspect (<Spec>MonitorAspect.aj = pointcuts/advice); rv-monitor -nativeindexingtree
# emits the monitor/indexing (classes/mop/<Spec>RuntimeMonitor.java). So compare
# the OLD .aj's indexing section against the CURRENT RuntimeMonitor.java.
set -u
TM=$( cd "$( dirname "$0" )/.." && pwd )
SPEC=${1:?usage: compare-codegen.sh <SpecName>}
REFDIR=$TM/aspect-specs-modified-hotspot
[ -f "$REFDIR/$SPEC.mop" ] || { echo "no $SPEC.mop in $REFDIR"; exit 1; }

export PATH=$TM/rv-monitor/target/release/rv-monitor/bin:$TM/javamop/target/release/javamop/javamop/bin:$PATH
export CLASSPATH=$TM/rv-monitor/target/release/rv-monitor/lib/rv-monitor-rt.jar:$TM/rv-monitor/target/release/rv-monitor/lib/rv-monitor.jar

W=/tmp/codegen-cmp-$SPEC; rm -rf "$W"; mkdir -p "$W/classes/mop"; cd "$W"
cp "$REFDIR/$SPEC.mop" .
cp "$TM/scripts/BaseAspect_new.aj" BaseAspect.aj
javamop -baseaspect BaseAspect.aj -emop "$SPEC.mop" -s >/dev/null 2>&1
rv-monitor -merge -d ./classes/mop/ *.rvm -nativeindexingtree -s >/dev/null 2>&1

NEW_ASPECT="$W/${SPEC}MonitorAspect.aj"
NEW_MON="$W/classes/mop/${SPEC}RuntimeMonitor.java"
OLD="$REFDIR/${SPEC}MonitorAspect.aj"

echo "OLD reference (combined) : $OLD"
echo "NEW aspect   (javamop)   : $NEW_ASPECT"
echo "NEW monitor  (rv-monitor): $NEW_MON"
echo ""
echo "=== key indexing/dispatch/coenable markers ==="
markers='IndexingTree|new IndexingTree|getOrCreate[0-9]|\.get[0-9]\(|terminateInternal|RVM_terminated|alive_parameters|WeakReference|ReferenceQueue|MapOf'
echo "--- OLD ($OLD) ---";       grep -nE "$markers" "$OLD"
echo "--- NEW monitor ($NEW_MON) ---"; grep -nE "$markers" "$NEW_MON"
echo ""
echo "=== full unified diff: OLD .aj  vs  (NEW aspect + NEW monitor concatenated) ==="
echo "    (saved to $W/full.diff — structural reorder/format noise expected)"
diff -u "$OLD" <(cat "$NEW_ASPECT" "$NEW_MON") > "$W/full.diff" 2>&1
echo "    diff lines: $(wc -l < "$W/full.diff")"
