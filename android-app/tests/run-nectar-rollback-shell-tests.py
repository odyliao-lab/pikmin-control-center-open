#!/usr/bin/env python3
"""Offline checks of the compiled production rollback parser and generated shell.
Uses only Python stdlib, the caller's existing Java/classes and an existing sh.
No root, adb, game process query, install or network call is performed.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile

MAIN = "dev.ody.pikmincontrol.NectarRollbackReceiptTest"
VIRTUAL = "/pcc-rollback-fixture"
SENTINEL = "PCC_ROLLBACK_EFFECT_REACHED"


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def source_contract(root):
    for relative in ("android-app/src/dev/ody/pikmincontrol", "targets/mumu/android/override"):
        directory = root / relative
        updater = (directory / "CoreUpdater.java").read_text(encoding="utf-8")
        rollback = updater.split("static void rollback(Context c,boolean finish)", 1)[1]
        require(rollback.index("NectarModuleGateway.requireRollbackSafe()") < rollback.index("CoreFreshInstall.rollback"), relative + ": early rollback/fresh guard missing")
        require(rollback.index("NectarModuleGateway.requireRollbackSafe()") < rollback.index("CoreMigrationFlow.finishRollback"), relative + ": finish guard missing")
        port = (directory / "CoreMigrationRootPort.java").read_text(encoding="utf-8")
        stage = port.split("public void stage(boolean old)", 1)[1].split("public void verifyStaged", 1)[0]
        compact = "".join(stage.split())
        require('old?NectarModuleGateway.rollbackProof():""' in compact, relative + ": old-core proof missing")
        require('+nectarRollbackGuard+"magisk--install-module' in compact, relative + ": proof not concatenated immediately before install")
        require(stage.index("NectarModuleGateway.rollbackProof()") < stage.index('"magisk --install-module'), relative + ": late proof order differs")
    print("PASS: phone/MuMu early, finish and actual-stage source wiring")


def locate_sh(explicit):
    if explicit:
        path = Path(explicit).resolve()
        require(path.is_file(), "Requested sh does not exist")
        return path
    if os.name == "nt":
        candidates = []
        git = shutil.which("git")
        if git:
            candidates.append(Path(git).resolve().parent.parent / "usr/bin/sh.exe")
        for variable in ("ProgramFiles", "ProgramFiles(x86)"):
            if os.environ.get(variable):
                candidates.append(Path(os.environ[variable]) / "Git/usr/bin/sh.exe")
        for path in candidates:
            if path.is_file():
                return path
    found = shutil.which("sh")
    require(found is not None, "Existing POSIX sh is required; no dependency is installed by this test")
    return Path(found).resolve()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    parser.add_argument("--root", required=True, type=Path)
    parser.add_argument("--work-dir", required=True, type=Path)
    parser.add_argument("--sh", default="")
    args = parser.parse_args()
    root = args.root.resolve()
    source_contract(root)
    require(args.work_dir.is_dir(), "Caller-owned work directory must already exist")
    parent = args.work_dir.resolve()
    work = Path(tempfile.mkdtemp(prefix="nectar-rollback-shell-", dir=parent)).resolve()
    require(work.parent == parent, "Fixture escaped caller-owned work directory")
    # Retain only new test-owned artifacts for diagnostics; do not remove caller files.
    private = work / "private-files"
    private.mkdir()
    sh = locate_sh(args.sh)
    env = dict(os.environ)
    env["PATH"] = str(sh.parent) + os.pathsep + env.get("PATH", "")
    env["LC_ALL"] = "C"
    records = []

    def run(name, command, expected=0, cwd=None):
        result = subprocess.run([str(x) for x in command], cwd=cwd, env=env,
                                capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
        records.append(dict(name=name, exit=result.returncode, stdout=result.stdout, stderr=result.stderr))
        (work / "results.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
        require(result.returncode == expected, name + ": unexpected exit\n" + result.stdout + result.stderr)
        return result.stdout

    run("shell-tools", [sh, "-c", "for p in stat sha256sum grep ls cat cut; do command -v \"$p\" >/dev/null || exit 1; done"])
    actual = run("fixture-shell-path", [sh, "-c", "pwd -P"], cwd=private).strip()
    if os.name == "nt":
        # MSYS may render a Windows TEMP directory as /tmp, whose mapping can
        # differ in a child tool. Use the explicit drive mount for shell proofs.
        windows = private.as_posix()
        require(len(private.drive) == 2 and private.drive[1] == ":", "Local Windows fixture drive required")
        actual = "/" + private.drive[0].lower() + windows[2:]
    require(actual.startswith("/") and "\n" not in actual, "Shell fixture path is not absolute")

    def java(name, *values, expected=0):
        return run(name, [args.java, "-cp", args.classpath, MAIN, *values], expected)

    java("compiled-parser")
    java("emit-production-command", work, VIRTUAL)
    read = (work / "read-command.sh").read_text(encoding="utf-8")
    valid = (work / "valid-receipt.tsv").read_bytes()
    receipt = private / "nectar_intent.tsv"

    def shell(name, command, expected=0, overrides="", effect=False):
        # Only fixed path literals are replaced. Guard logic remains production output.
        command = command.replace("'" + VIRTUAL + "/nectar_intent.tsv'", shlex.quote(actual + "/nectar_intent.tsv"))
        command = command.replace("'" + VIRTUAL + "'", shlex.quote(actual))
        fake = "pidof(){ return 1; }; " + overrides
        if effect:
            command += "printf '%s\\n' '" + SENTINEL + "'"
        output = run(name, [sh, "-c", fake + command], expected)
        require((SENTINEL in output) == (effect and expected == 0), name + ": effect sentinel differs")
        return output

    def proof(name, observed, expected=0):
        observed_path = work / (name + "-observed.txt")
        target = work / (name + "-proof.sh")
        observed_path.write_text(observed, encoding="utf-8", newline="\n")
        java(name + "-parse-proof", "proof", observed_path, VIRTUAL, target, expected=expected)
        if expected:
            require(not target.exists(), name + ": rejected proof was emitted")
            return ""
        return target.read_text(encoding="utf-8")

    absent = proof("absent", shell("read-absent", read))
    shell("stage-absent", absent, effect=True)
    quarantined = private / ("nectar-unconfirmed-" + "a" * 64 + ".tsv")
    quarantined.write_text("original unresolved evidence\n", encoding="utf-8")
    shell("read-quarantine-denied", read, expected=1)
    shell("stage-quarantine-denied", absent, expected=1, effect=True)
    quarantined.unlink()  # only this test's fixture, never production data
    shell("listing-error", absent, 1, "ls(){ return 2; }; ", True)
    shell("grep-error", absent, 1, "grep(){ return 2; }; ", True)
    receipt.write_bytes(valid)
    shell("late-creation", absent, 1, effect=True)
    shell("getattr-unknown-entry-listed", read, 1,
          'test(){ if [ "$1:$2" = "!:-e" ]; then return 0; fi; command test "$@"; }; ')
    success = proof("success", shell("read-success", read))
    shell("stage-success", success, effect=True)
    changed = bytearray(valid)
    changed[15] = ord("d") if changed[15] != ord("d") else ord("e")
    receipt.write_bytes(changed)
    shell("same-inode-size-changed", success, 1, effect=True)
    receipt.write_bytes(valid)
    replacement = private / "replacement"
    replacement.write_bytes(valid)
    os.replace(replacement, receipt)
    shell("new-inode-same-success", success, 1, effect=True)
    shell("game-running-read", read, 1, "pidof(){ printf 123; return 0; }; ")
    shell("pidof-error-read", read, 1, "pidof(){ return 127; }; ")
    shell("game-running-stage", success, 1, "pidof(){ printf 123; return 0; }; ", True)
    shell("stat-error-read", read, 1, "stat(){ return 2; }; ")
    shell("cat-error-read", read, 1, "cat(){ return 2; }; ")
    body = valid.decode("ascii").rstrip("\n").rsplit("\t", 1)[0].replace("\tsuccess\t", "\tpending\t")
    pending = (body + "\t" + hashlib.sha256(body.encode("ascii")).hexdigest() + "\n").encode("ascii")
    receipt.write_bytes(pending)
    proof("pending", shell("read-pending", read), 1)
    receipt.write_bytes(b"corrupt\n")
    proof("malformed", shell("read-malformed", read), 1)
    receipt.write_bytes(valid)
    os.link(receipt, private / "hardlink")
    shell("hardlink-read", read, 1)
    if os.name == "nt":
        print("SKIP: Windows symlink/FIFO creation requires host-specific privileges; no device coverage claimed")
        records.append(dict(name="symlink-fifo", result="SKIP-Windows"))
    else:
        # Remove only the exact test-owned receipt; keep the hardlink as fixture evidence.
        receipt.unlink()
        receipt.symlink_to(private / "hardlink")
        shell("symlink-read", read, 1)
        receipt.unlink()
        os.mkfifo(receipt)
        shell("fifo-read", read, 1)
    (work / "results.json").write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding="utf-8")
    print("PASS: production rollback receipt parser and generated shell proofs; " + str(sum("exit" in item for item in records)) + " subprocess checks; " + str(sum("result" in item for item in records)) + " explicit skips")
    print("Evidence: " + str(work / "results.json"))
    print("Scope: HOST fixtures only; fake pidof, no root/device/API/installation")


if __name__ == "__main__":
    main()
