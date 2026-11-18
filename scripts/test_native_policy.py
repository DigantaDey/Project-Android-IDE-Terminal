"""Run the shipped native child's syscall policy in fresh Linux subprocesses."""
from pathlib import Path
import os
import platform
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent.parent


@unittest.skipUnless(platform.system() == "Linux" and platform.machine() in ("x86_64", "aarch64") and shutil.which("cc"), "requires a supported Linux host and C compiler")
class NativePolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = tempfile.TemporaryDirectory(prefix="forge-native-policy-")
        cls.executable = Path(cls.directory.name) / "policy-test"
        subprocess.run(["cc", "-std=c11", "-Wall", "-Wextra", "-Werror", str(ROOT / "scripts/native-policy-test.c"), "-o", str(cls.executable)], check=True, capture_output=True, text=True)

    @classmethod
    def tearDownClass(cls):
        cls.directory.cleanup()

    def test_network_off_blocks_inet_ipv6_unix_proxy_and_address_sending(self):
        result = subprocess.run([str(self.executable), "network-off"], check=True, capture_output=True, text=True, timeout=10)
        self.assertIn("NETWORK_OFF_AND_PROCESS_POLICY_OK", result.stdout)

    def test_network_on_allows_sockets_but_cannot_remove_parent_binder_policy(self):
        result = subprocess.run([str(self.executable), "network-on"], check=True, capture_output=True, text=True, timeout=10)
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
            subprocess.run(["cc", "-std=c11", "-shared", "-fPIC", "-Wall", "-Wextra", "-Werror", "-I" + str(home / "include"), "-I" + str(home / "include/linux"), str(ROOT / "native-runtime/src/main/jni/pty.c"), "-o", str(output / "libforge-pty.so")], check=True, capture_output=True, text=True)
            command = [str(compiler), "--release", "17"] if compiler.exists() else [java, "-jar", str(ecj), "-17"]
            command += ["-d", str(classes), str(ROOT / "native-runtime/src/main/java/com/forgeide/runtime/NativePty.java"), str(ROOT / "scripts/fixtures/NativePtyHostTest.java")]
            subprocess.run(command, check=True, capture_output=True, text=True, timeout=30)
            result = subprocess.run([java, "--add-opens", "java.base/java.io=ALL-UNNAMED", "-Djava.library.path=" + folder, "-cp", str(classes), "com.forgeide.runtime.NativePtyHostTest"], check=True, capture_output=True, text=True, timeout=35)
            self.assertIn("10 real PTY cold starts, resize, pipes, foreground Ctrl-C and subsequent commands passed", result.stdout)


if __name__ == "__main__":
    unittest.main()
