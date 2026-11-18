"""Run the shipped native child's syscall policy in fresh Linux subprocesses."""
from pathlib import Path
import os
import platform
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


def checked(command, **options):
    result = subprocess.run(command, capture_output=True, text=True, **options)
    if result.returncode:
        raise AssertionError("Native host test command failed (" + str(result.returncode) + "): " + " ".join(command) + "\n" + result.stdout + "\n" + result.stderr)
    return result


@unittest.skipUnless(platform.system() == "Linux" and platform.machine() in ("x86_64", "aarch64") and shutil.which("cc"), "requires a supported Linux host and C compiler")
class NativePolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="forge-native-policy-")
        cls.executable = Path(cls.directory.name) / "policy-test"
        checked(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(ROOT / "scripts/native-policy-test.c"), "-o", str(cls.executable)])

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_network_off_blocks_inet_ipv6_unix_proxy_and_address_sending(self):
        result = checked([str(self.executable), "network-off"], timeout=10)
        self.assertIn("NETWORK_OFF_AND_PROCESS_POLICY_OK", result.stdout)

    def test_network_on_allows_sockets_but_cannot_remove_parent_binder_policy(self):
        result = checked([str(self.executable), "network-on"], timeout=10)
        self.assertIn("NETWORK_ON_AND_PROCESS_POLICY_OK", result.stdout)


@unittest.skipUnless(platform.system() == "Linux" and platform.machine() in ("x86_64", "aarch64") and shutil.which("cc"), "requires a supported Linux host and C compiler")
class NativePtyHostTests(unittest.TestCase):
    def test_actual_jni_cold_pty_resize_foreground_interrupt_pipes_and_reuse(self):
        java = os.environ.get("JAVA") or shutil.which("java")
        local_java = ROOT / ".local/python/jdk4py/java-runtime/bin/java"
        if not java and local_java.exists():
            java = str(local_java)
        if not java:
            self.skipTest("requires a JDK (provided by CI)")
        home = Path(java).resolve().parent.parent
        compiler = home / "bin/javac"
        ecj = ROOT / ".local/tools/ecj.jar"
        if not compiler.exists() and not ecj.exists():
            self.skipTest("requires javac or the local ECJ test harness")
        with tempfile.TemporaryDirectory(prefix="forge-native-pty-") as folder:
            output = Path(folder)
            classes = output / "classes"
            classes.mkdir()
            checked(["cc", "-std=c11", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-I" + str(home / "include"), "-I" + str(home / "include/linux"), str(ROOT / "native-runtime/src/main/jni/pty.c"), "-o", str(output / "libforge-pty.so")])
            command = [str(compiler), "--release", "17"] if compiler.exists() else [java, "-jar", str(ecj), "-17"]
            command += ["-d", str(classes), str(ROOT / "native-runtime/src/main/java/com/forgeide/runtime/NativePty.java"), str(ROOT / "scripts/fixtures/NativePtyHostTest.java")]
            checked(command, timeout=30)
            user = os.environ.get("FORGE_NATIVE_HOST_TEST_USER")
            prefix = ["sudo", "-n", "-u", user, "--"] if user else []
            if user:
                # Only public compiled fixtures are exposed to this disposable UID.
                # Keep the production launcher's exact 64-task limit unchanged.
                output.chmod(0o755)
                for path in output.rglob("*"):
                    path.chmod(0o755 if path.is_dir() or path.suffix == ".so" else 0o644)
            result = checked(prefix + [java, "-XX:ActiveProcessorCount=2", "-XX:+UseSerialGC", "--add-opens", "java.base/java.io=ALL-UNNAMED", "-Djava.library.path=" + folder, "-cp", str(classes), "com.forgeide.runtime.NativePtyHostTest"], timeout=35)
            self.assertIn("10 real PTY cold starts, resize, pipes, foreground Ctrl-C and subsequent commands passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
