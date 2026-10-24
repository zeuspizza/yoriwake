#!/bin/sh
# Prints the machine's memory, swap, free disk and largest processes every INTERVAL seconds (default
# 30), each line starting `[machine]`, until killed.
#
# A runner that runs out of memory or disk is shut down whole: later steps never run and nothing is
# uploaded, so the only evidence left is what the running step already wrote to the job log. Started
# in the background by the step that measures, it leaves that evidence. It never fails the job: a
# tool it needs that is missing is said once, and it exits 0.
interval=${1:-30}
prefix="[machine]"
# Linux's; read from elsewhere only by this script's test.
meminfo=${MACHINE_SAMPLER_MEMINFO:-/proc/meminfo}
for tool in ps df awk sort; do
  command -v "$tool" >/dev/null 2>&1 || { echo "$prefix not sampling: $tool is not on PATH"; exit 0; }
done

memory() {
  if [ -r "$meminfo" ]; then
    awk '/^MemTotal:/{t=$2} /^MemAvailable:/{a=$2} /^SwapTotal:/{st=$2} /^SwapFree:/{sf=$2}
         END {printf "memory used %dM available %dM, swap used %dM", (t-a)/1024, a/1024, (st-sf)/1024}' "$meminfo"
  else
    printf "memory unknown (no %s)" "$meminfo"
  fi
}

free_on() {
  df -Pk "$1" 2>/dev/null | awk 'NR==2 {printf "%dM", $4/1024}'
}

# Named by role: every JVM is `java` to ps, and which one grew is the question.
largest() {
  ps -eo rss=,args= | sort -rn | awk 'NR<=3 {
    name=$2; sub(".*/", "", name)
    if ($0 ~ /GradleDaemon/) name="gradle-daemon"
    else if ($0 ~ /KotlinCompileDaemon/) name="kotlin-daemon"
    else if ($0 ~ /GradleWorkerMain|Gradle Test Executor/) name="test-worker"
    printf "%s%s %dM", (NR>1 ? ", " : ""), name, $1/1024
  }'
}

total=$( [ -r "$meminfo" ] && awk '/^MemTotal:/{printf "%dM", $2/1024}' "$meminfo" || echo unknown)
# Waited for in the background, so a stop ends the pause too and no sleep outlives the step.
trap 'kill $! 2>/dev/null; exit 0' TERM
echo "$prefix cpus $(getconf _NPROCESSORS_ONLN 2>/dev/null || echo unknown), memory total $total"
while true; do
  echo "$prefix $(date -u +%H:%M:%S) $(memory); free on / $(free_on /), on the workspace $(free_on "${GITHUB_WORKSPACE:-.}"); largest: $(largest)"
  sleep "$interval" &
  wait $!
done
