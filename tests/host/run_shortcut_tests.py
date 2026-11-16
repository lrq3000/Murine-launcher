"""Compile and execute the production availability policy; no Android stubs or mirrored policy."""
import os
from pathlib import Path
import subprocess
import tempfile
import shutil

root = Path(__file__).resolve().parents[2]
java_home = os.environ.get("JAVA_HOME")
windows_jdk = Path("C:/Program Files/Java/jdk-21")
if java_home is None and windows_jdk.exists():
    java_home = str(windows_jdk)
javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac")
java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java")
if javac is None or java is None:
    raise SystemExit("A JDK (normally JAVA_HOME pointing to JDK21) is required")
preferred_temp = Path("C:/Users/33632/AppData/Local/Temp/opencode")
temp_root = os.environ.get("OPENCODE_TEMP", str(preferred_temp) if preferred_temp.exists() else tempfile.gettempdir())
output = Path(tempfile.mkdtemp(prefix="murine-shortcut-tests-", dir=temp_root))
print(f"Generated classes retained at {output}", flush=True)
subprocess.run([javac, "-d", str(output), str(root / "src/com/android/launcher3/model/ShortcutAvailabilityJournal.java"), str(root / "tests/host/ShortcutAvailabilityJournalTest.java")], check=True)
subprocess.run([java, "-ea", "-cp", str(output), "com.android.launcher3.model.ShortcutAvailabilityJournalTest"], check=True)
