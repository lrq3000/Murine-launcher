"""Compile and execute the production availability policy; no Android stubs or mirrored policy."""
import os
from pathlib import Path
import subprocess
import tempfile

root = Path(__file__).resolve().parents[2]
java_home = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21"))
output = Path(tempfile.mkdtemp(prefix="murine-shortcut-tests-", dir=os.environ.get("OPENCODE_TEMP", "C:/Users/33632/AppData/Local/Temp/opencode")))
print(f"Generated classes retained at {output}", flush=True)
subprocess.run([str(java_home / "bin/javac"), "-d", str(output), str(root / "src/com/android/launcher3/model/ShortcutAvailabilityJournal.java"), str(root / "tests/host/ShortcutAvailabilityJournalTest.java")], check=True)
subprocess.run([str(java_home / "bin/java"), "-ea", "-cp", str(output), "com.android.launcher3.model.ShortcutAvailabilityJournalTest"], check=True)
