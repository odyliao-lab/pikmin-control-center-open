#!/usr/bin/env python3
"""Run actual APK stop/read-back commands against temporary files, never ADB/root."""
import argparse
import importlib.util
import os
from pathlib import Path
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java", required=True)
    parser.add_argument("--classpath", required=True)
    args = parser.parse_args()
    spec = importlib.util.spec_from_file_location("rollback_shell", Path(__file__).with_name("run-nectar-rollback-shell-tests.py"))
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    shell = helper.locate_sh("")
    env = dict(os.environ)
    env["PATH"] = str(shell.parent) + os.pathsep + env.get("PATH", "")
    commands = {}
    for op in ("stop", "check", "start"):
        commands[op] = subprocess.check_output(
            [args.java, "-Dfile.encoding=UTF-8", "-cp", args.classpath, "dev.ody.pikmincontrol.NectarFirstInstallStopTest", op],
            text=True, encoding="utf-8")
    count = 0
    with tempfile.TemporaryDirectory(prefix="pcc-first-install-") as tmp:
        fixture = Path(tmp)
        data = fixture / "data"
        data.mkdir()
        module = data / "adb/modules/pikmin_nectar_rpc"
        game = data / "user/0/com.nianticlabs.pikmin/files"
        mode = game / "nectar_rpc_mode.txt"
        control = module / "nectar_control_mode.txt"
        shellpath = fixture.as_posix()
        if os.name == "nt":
            shellpath = "/" + fixture.drive[0].lower() + shellpath[2:]
        # Production paths contain no whitespace. Keep the fixture equally constrained.
        assert not any(c.isspace() or c in "'\"$`" for c in shellpath)

        def run(name, op, ok=True, inject=""):
            nonlocal count
            script = "id(){ printf '0\\n'; }; " + inject + commands[op].replace("/data", shellpath + "/data")
            result = subprocess.run([str(shell), "-c", script], env=env, capture_output=True,
                                    text=True, encoding="utf-8", errors="replace", timeout=10)
            assert (result.returncode == 0) == ok, (name, result.returncode, result.stdout, result.stderr)
            count += 1

        run("bare fresh phone stop", "stop")
        run("bare fresh phone read-back", "check")
        assert list(data.iterdir()) == [], "Stopping must not invent a core/game directory"
        run("fresh phone cannot start", "start", False)
        run("root denied on fresh phone", "stop", False, "id(){ printf '2000\\n'; }; ")
        run("listing error is not absence", "stop", False, "ls(){ return 13; }; ")
        run("listing parser failure is not absence", "stop", False, "grep(){ return 2; }; ")
        (data / "adb").write_text("invalid parent")
        run("non-directory parent", "stop", False)
        (data / "adb").unlink()
        module.parent.mkdir(parents=True)
        game.mkdir(parents=True)
        run("game installed but no core", "stop")
        run("fresh setup read-back", "check")
        mode.write_text("auto\n")
        run("orphan active runtime not absence", "stop", False)
        run("orphan active runtime read-back", "check", False)
        module.mkdir()
        run("listed module with failed attributes is not absence", "stop", False,
            'test(){ if [ "$1" = -e ]; then case "$2" in */pikmin_nectar_rpc) return 1;; esac; fi; command test "$@"; }; ')
        run("partial module not fresh", "stop", False)
        (module / "module.prop").write_text("id=pikmin_nectar_rpc\n")
        run("create missing module control when stopping", "stop")
        assert mode.read_text() == control.read_text() == "diag\n"
        run("installed stopped read-back", "check")
        mode.write_text("auto\n")
        control.write_text("auto\n")
        run("active is not stopped", "check", False)
        run("installed stop", "stop")
        assert mode.read_text() == control.read_text() == "diag\n"
        run("idempotent installed stop", "stop")
        run("cat fails after printing expected value", "check", False,
            "cat(){ printf 'diag\\n'; return 1; }; ")
        mode.write_text("auto\n")
        run("failed write permission", "stop", False, "chmod(){ return 1; }; ")
        assert mode.read_text() == "auto\n"
        run("failed atomic replacement", "stop", False, "mv(){ return 1; }; ")
        run("write succeeds but read-back fails", "stop", False, "cat(){ return 1; }; ")
        run("late rearm detected", "check", False, "cat(){ printf 'auto\\n'; }; ")
        mode.unlink()
        run("installed core missing runtime not fresh", "stop", False)
        run("installed core missing runtime read-back", "check", False)
        mode.mkdir()
        run("directory runtime rejected", "stop", False)
        mode.rmdir()
        mode.write_text("diag\n")
        control.unlink()
        run("missing installed control read-back", "check", False)
        control.mkdir()
        run("directory module control rejected", "stop", False)
        control.rmdir()
        control.write_text("diag\n")
        # Inject shell's link predicate for portable dangling-link coverage on Windows CI.
        run("linked mode rejected", "stop", False,
            'test(){ if [ "$1:$2" = "!:-L" ]; then case "$3" in */nectar_rpc_mode.txt) return 1;; esac; fi; command test "$@"; }; ')
        run("linked temporary file rejected", "stop", False,
            'test(){ if [ "$1:$2" = "!:-L" ]; then case "$3" in *.tmp) return 1;; esac; fi; command test "$@"; }; ')
    print(f"PASS: {count} first-install/existing-core production-shell cases (host only)")


if __name__ == "__main__":
    main()
