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
subprocess.run([javac, "-d", str(output), str(root / "src/com/android/launcher3/model/ShortcutAvailabilityJournal.java"), str(root / "tests/host/ShortcutAvailabilityJournalTest.java"), str(root / "tests/host/ShortcutReviewRegressionTest.java")], check=True)
subprocess.run([java, "-ea", "-cp", str(output), "com.android.launcher3.model.ShortcutAvailabilityJournalTest"], check=True)
subprocess.run([java, "-ea", "-cp", str(output), "com.android.launcher3.model.ShortcutReviewRegressionTest"], check=True)
sqlite_jars = list((Path.home() / ".gradle/caches/modules-2/files-2.1/org.xerial/sqlite-jdbc").glob("**/sqlite-jdbc-*.jar"))
sqlite_jar = os.environ.get("SQLITE_JDBC_JAR")
if sqlite_jar is None and not sqlite_jars:
    raise SystemExit("SQLite integration requires SQLITE_JDBC_JAR or a cached org.xerial:sqlite-jdbc jar")
if sqlite_jar is None:
    sqlite_jar = str(sqlite_jars[0])
sources = [root / "src/com/android/launcher3/model/WorkspaceDatabaseIdentity.java", root / "src/com/android/launcher3/model/WorkspaceLayoutStore.java", root / "tests/host/WorkspaceLayoutStoreIntegrationTest.java", *list((root / "tests/host/sqlite").glob("**/*.java"))]
subprocess.run([javac, "-cp", str(output) + os.pathsep + sqlite_jar, "-d", str(output), *map(str, sources)], check=True)
subprocess.run([java, "-Djava.io.tmpdir=" + str(output), "-ea", "-cp", str(output) + os.pathsep + sqlite_jar, "com.android.launcher3.model.WorkspaceLayoutStoreIntegrationTest", str(output)], check=True)
delivery_sources = [root / "src/com/android/launcher3/model/WorkspaceInsertCommit.java", root / "src/com/android/launcher3/model/WorkspaceBindGuard.java", root / "tests/host/WorkspaceDeliveryRegressionTest.java"]
subprocess.run([javac, "-d", str(output), *map(str, delivery_sources)], check=True)
subprocess.run([java, "-ea", "-cp", str(output), "com.android.launcher3.model.WorkspaceDeliveryRegressionTest"], check=True)
