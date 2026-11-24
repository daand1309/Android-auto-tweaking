#!/usr/bin/env python3
"""Decide whether the emulator suite passed, and say why when it did not.

Kept out of connected-tests.sh so the decision can be tested without an emulator (`--selftest`):
a pre-push gate that can fresh-pass on stale XML or on a Gradle failure is worse than no gate.

Exit 0 only when tests actually ran, none failed, no process crashed, and Gradle succeeded.
Otherwise non-zero: 1 = our tests failed or the cause is unknown, 3 = the package manager killed
the process while installing (a re-run on a clean device is the answer, not a code change).
"""
import argparse
import glob
import os
import shutil
import sys
import tempfile
import xml.etree.ElementTree as ET

APP = "com.daand.drivemusic"


def scan(paths):
    """tests, skipped, failed test names, and whether any report says the process crashed.

    A failure element with no text is the artifact of a process that died mid-run (Gradle writes an
    empty <failure/>); one with text is a real assertion. They are counted apart, because a real
    assertion failure outranks the crash classification: it is our bug whatever else happened.
    """
    tests, skipped, failed, messageLess, crashed = 0, 0, [], [], []
    for p in paths:
        root = ET.parse(p).getroot()
        tests += int(root.get("tests", 0) or 0)
        skipped += len(list(root.iter("skipped")))      # one <skipped/> child per skipped testcase
        for tc in root.iter("testcase"):
            for fl in tc:
                if fl.tag in ("failure", "error"):
                    if (fl.text or "").strip():
                        failed.append(tc.get("name") or p)
                    else:
                        messageLess.append(tc.get("name") or p)
        if "Process crashed" in (root.findtext("system-err") or ""):
            crashed.append(p)
    return tests, skipped, failed, messageLess, crashed


def cause(log):
    """1 = ours (a stack trace), 3 = the process was torn down from outside (re-run), 1 = unknown."""
    fatal = [l for l in log.splitlines() if "FATAL EXCEPTION" in l or "Fatal signal" in l]
    # Two things are needed, not one: our package (another app's install says nothing about why we
    # died) *and* an actual termination line. "installPackageLI com.daand.drivemusic" on its own is a
    # package operation, not proof that the process went away (Luna's finding 25).
    installer = [l for l in log.splitlines()
                 if ("installPackageLI" in l or "due to start instr" in l)
                 and APP in l
                 and ("Killing " in l or "has died" in l or "Force stopping" in l)]
    # Any other death of our process that is not a Java crash: an outside kill (package manager, low
    # memory, a reboot, the runner tearing the app down). Still not a defect in our code.
    # The gate uninstalls both packages before it runs, and Gradle uninstalls them when it is done:
    # those lines are the cleanup, not a reason to call a red run "someone else's fault".
    cleanup = ("deletePackageX", "pkg removed", "start instr", "finished inst", "due to finished")
    killed = [l for l in log.splitlines()
              if ("Killing " in l or "has died" in l) and APP in l
              and not any(c in l for c in cleanup)]
    if fatal:
        return 1, "the app crashed - this is ours", fatal[-25:]
    if installer:
        return 3, "the package manager killed the process while installing, not an app defect", installer[-4:]
    if killed:
        return 3, "the process was torn down from outside (not a Java crash) - re-run", killed[-4:]
    return 1, "unknown - no app crash and no kill in logcat", []


def decide(results, gradle_status, log):
    """Return (exit code, lines to print)."""
    tests, skipped, failed, messageLess, crashed = scan(results)
    out = [f"instrumented: {tests} tests, {len(failed) + len(crashed) + len(messageLess)} problems"]
    if tests and skipped >= tests:
        out.append(f"  skipped: all {skipped} of {tests} - a run where nothing executed proves nothing")
    out += [f"  failed: {n}" for n in failed]
    out += [f"  crashed: {os.path.basename(p)}" for p in crashed]
    out += [f"  failed (no message): {n}" for n in messageLess]
    problems = bool(failed or crashed or messageLess)

    if not problems and gradle_status == 0 and tests > 0 and skipped < tests:
        return 0, out
    if not problems and gradle_status != 0:
        # Gradle failed with no failing test in the XML: no results, stale results, or a compile
        # error. Either way the gate did not pass.
        out.append("")
        out.append(f"CAUSE: gradle exited {gradle_status} without a failing test in the reports "
                   f"({tests} tests found) - see /tmp/connected-tests.log")
        return 1, out
    if not problems and tests > 0 and skipped >= tests:
        out.append("")
        out.append("CAUSE: every test was skipped - nothing was executed, so nothing is proven")
        return 1, out
    if not problems and tests == 0:
        out.append("")
        out.append("CAUSE: gradle succeeded but ran no tests - see /tmp/connected-tests.log")
        return 1, out

    # A crashed instrumentation first: its XML holds an empty <failure/> that is an artifact of the
    # process dying, so the logcat reason is the answer. Then a named failing test, which needs no logcat.
    if crashed and not failed:
        code, why, evidence = cause(log)
        out.append("")
        out.append(f"CAUSE: {why}:")
        out += evidence
        if code == 3:
            out.append("Re-run on the cleaned device; if it repeats, treat it as ours.")
        return code, out
    if failed:
        out.append("")
        out.append(f"CAUSE: {len(failed)} test(s) failed - read the XML for the assertion")
        return 1, out
    if messageLess:
        # A failure node with no message means the run was cut short (the process went away before it
        # could be written). That is never a pass, whatever the exit code says (Luna's finding 23).
        out.append("")
        out.append(f"CAUSE: {len(messageLess)} test(s) failed with no message - the run was cut short")
        return 1, out

    code, why, evidence = cause(log)
    out.append("")
    out.append(f"CAUSE: {why}:")
    out += evidence
    if code == 3:
        out.append("Re-run on the cleaned device; if it repeats, treat it as ours.")
    return code, out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", help="results directory or file")
    ap.add_argument("--log", help="file with `adb logcat -d` output (optional)")
    ap.add_argument("--gradle-status", type=int, default=0)
    ap.add_argument("--selftest", action="store_true")
    a = ap.parse_args()
    if a.selftest:
        return selftest()

    results = sorted(glob.glob(os.path.join(a.dir or "", "**", "*.xml"), recursive=True)) if a.dir else []
    log = open(a.log, errors="replace").read() if a.log and os.path.exists(a.log) else ""
    code, lines = decide(results, a.gradle_status, log)
    print("\n".join(lines))
    return code


# --- selftest: the failure paths, without an emulator -------------------------------------------

GREEN = ('<?xml version="1.0"?><testsuite name="x" tests="3" failures="0" errors="0">'
         '<testcase name="a"/><testcase name="b"/><testcase name="c"/></testsuite>')
RED = ('<?xml version="1.0"?><testsuite name="x" tests="3" failures="1" errors="0">'
       '<testcase name="a"/><testcase name="b"><failure>boom</failure></testcase>'
       '<testcase name="c"/></testsuite>')
CRASHED = ('<?xml version="1.0"?><testsuite name="x" tests="1" failures="0" errors="0">'
           '<testcase name="a"><failure></failure></testcase>'
           '<system-err>Test run failed to complete. Instrumentation run failed due to Process crashed.</system-err>'
           '</testsuite>')
ALL_SKIPPED = ('<?xml version="1.0"?><testsuite name="x" tests="3" failures="0" errors="0" skipped="3">'
               '<testcase name="a"><skipped/></testcase><testcase name="b"><skipped/></testcase>'
               '<testcase name="c"><skipped/></testcase></testsuite>')
EMPTY_FAILURE = ('<?xml version="1.0"?><testsuite name="x" tests="1" failures="1" errors="0">'
                 '<testcase name="only"><failure></failure></testcase></testsuite>')
INSTALLER = "I ActivityManager: Killing 3983:com.daand.drivemusic (adj 0): stop com.daand.drivemusic due to installPackageLI"
OTHER_INSTALL = "I PackageManager: installPackageLI com.other.app (uid 10123) v1.2 of user 0"
OUR_INSTALL_ONLY = "I PackageManager: installPackageLI com.daand.drivemusic (uid 10211) v1.30 of user 0"
FATAL = "E AndroidRuntime: FATAL EXCEPTION: main\nE AndroidRuntime: java.lang.IllegalStateException: boom"


def selftest():
    cases = [
        ("green run passes", [GREEN], 0, "", 0),
        ("a red test fails the gate", [RED], 0, "", 1),
        ("a red test is reported as a failing test, not as an outside kill",
         [RED], 1, "I ActivityManager: Killing 4242:com.daand.drivemusic (adj 0): low memory", 1),
        ("crashed instrumentation with an installer kill needs a re-run", [CRASHED], 0, INSTALLER, 3),
        ("crashed instrumentation with a java stack is ours", [CRASHED], 0, FATAL, 1),
        ("a red run with the usual teardown uninstall is still ours",
         [RED], 0, "I ActivityManager: Killing 4242:com.daand.drivemusic/u0a211 (adj 900): stop com.daand.drivemusic due to deletePackageX", 1),
        ("a low-memory kill of our app also means re-run",
         [CRASHED], 0, "I ActivityManager: Killing 4242:com.daand.drivemusic/u0a211 (adj 900): empty #17", 3),
        # The false pass Luna found: Gradle failed, the old green XML was still on disk.
        ("a gradle failure never passes on stale XML", [GREEN], 1, "", 1),
        ("a gradle failure with no reports fails", [], 1, "", 1),
        ("no tests at all fails", [], 0, "", 1),
        # Luna #20: a report where everything was skipped executed nothing.
        ("an all-skipped report does not pass", [ALL_SKIPPED], 0, "", 1),
        # Luna #23: a failure with no message and no crash marker is still a red run.
        ("a message-less failure fails closed", [EMPTY_FAILURE], 0, "", 1),
        # Luna #21: another app being installed says nothing about why our run died.
        ("an unrelated package install is not why ours died", [CRASHED], 0, OTHER_INSTALL, 1),
        # Luna #25: installing our package is not the same as killing its process.
        ("our package being installed is not a kill either", [CRASHED], 0, OUR_INSTALL_ONLY, 1),
        # Luna #18: a real assertion failure outranks the crash classification.
        ("a real failure with a crash and an installer kill is still ours", [RED, CRASHED], 1, INSTALLER, 1),
    ]
    bad = 0
    for name, reports, status, log, want in cases:
        d = tempfile.mkdtemp()
        try:
            for i, r in enumerate(reports):
                open(os.path.join(d, f"t{i}.xml"), "w").write(r)
            got, lines = decide(sorted(glob.glob(os.path.join(d, "*.xml"))), status, log)
        finally:
            shutil.rmtree(d)
        ok = got == want
        bad += 0 if ok else 1
        print(("ok   " if ok else "FAIL ") + name + f" (want {want}, got {got})")
        if not ok:
            print("\n".join("     " + l for l in lines))
    print(f"selftest: {len(cases) - bad}/{len(cases)}")
    return 0 if bad == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
