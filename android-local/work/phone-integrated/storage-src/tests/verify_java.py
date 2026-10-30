"""Compile new Android classes, real DAO/POJO sources and parent API contract.

Small compile-only placeholders replace unrelated Fastjson/application dependencies.
They are temporary and are NEVER packaged with the storage implementation.
The one executable host test exercises the real pure-Java JSON text patcher only.
"""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT.parent
JDK = Path("C:/Program Files/Eclipse Adoptium/jdk-25.0.4.101-hotspot/bin")
VERIFY = (ROOT / "storage-src/.verify").resolve()
VERIFY.mkdir(exist_ok=True)
assert VERIFY.is_relative_to((ROOT / "storage-src").resolve())

FIXTURES = {
    "com/alibaba/fastjson/JSON.java": "package com.alibaba.fastjson; public class JSON { public static String toJSONString(Object o, com.alibaba.fastjson.serializer.SerializerFeature[] f) { throw new UnsupportedOperationException(); } }",
    "com/alibaba/fastjson/JSONObject.java": "package com.alibaba.fastjson; public class JSONObject { public JSONObject() {} public JSONObject(java.util.Map<String,Object> m) {} }",
    "com/alibaba/fastjson/JSONArray.java": "package com.alibaba.fastjson; public class JSONArray {}",
    "com/alibaba/fastjson/serializer/SerializerFeature.java": "package com.alibaba.fastjson.serializer; public enum SerializerFeature { WriteMapNullValue }",
    "com/hypergryph/arknights/ArknightsApplication.java": "package com.hypergryph.arknights; public class ArknightsApplication { public static org.springframework.jdbc.core.JdbcTemplate jdbcTemplate; }",
    "ParentStorageContract.java": """
        import android.content.Context;
        import android.database.sqlite.SQLiteDatabase;
        import java.io.InputStream;
        import com.codex.arklocal.storage.LocalStore;
        import org.springframework.jdbc.core.JdbcTemplate;
        class ParentStorageContract {
          void integrate(Context context, InputStream seed) throws Exception {
            LocalStore store = LocalStore.open(context, seed);
            SQLiteDatabase db = store.database();
            JdbcTemplate jdbc = new JdbcTemplate(db);
            String snapshot = store.exportSnapshot();
            String summary = store.summary();
            store.refillResources();
            store.close();
          }
        }
    """,
    "com/codex/arklocal/storage/CounterPatchTest.java": r'''
        package com.codex.arklocal.storage;
        public class CounterPatchTest {
          static void check(boolean ok) { if (!ok) throw new AssertionError("Counter patch regression"); }
          static void reject(String text) {
            try {
              JsonCounterPatch.set(text,"status",new String[]{"androidDiamond"},new long[]{1000000},false);
              throw new AssertionError("Expected refusal of ambiguous resource document");
            } catch (IllegalStateException expected) { }
          }
          public static void main(String[] args) {
            String before = "{ \"status\": {\"androidDiamond\" : 7, \"iosDiamond\":8, \"diamondShard\":9, \"gachaTicket\":10, \"tenGachaTicket\":11, \"keep\":123456789012345678901234567890},\n\"troop\":{\"chars\":{\"1\":{\"exp\":567}}},\"untouched\":1.234567890123456789e100 }";
            String after = JsonCounterPatch.set(before,"status",
                new String[]{"androidDiamond","iosDiamond","diamondShard","gachaTicket","tenGachaTicket"},
                new long[]{1000000,1000000,99992520,99999,9987},false);
            String expected = before.replace(" : 7", " : 1000000").replace("\"iosDiamond\":8", "\"iosDiamond\":1000000")
                .replace("\"diamondShard\":9", "\"diamondShard\":99992520").replace("\"gachaTicket\":10", "\"gachaTicket\":99999")
                .replace("\"tenGachaTicket\":11", "\"tenGachaTicket\":9987");
            check(after.equals(expected));
            check(JsonCounterPatch.set("{\"status\":{}}","status",new String[]{"gachaTicket","tenGachaTicket"},new long[]{99999,9987},false)
                .equals("{\"status\":{\"gachaTicket\":99999,\"tenGachaTicket\":9987}}"));
            check(JsonCounterPatch.set("{\"status\":{\"android\\u0044iamond\":7}}","status",new String[]{"androidDiamond"},new long[]{1000000},true)
                .equals("{\"status\":{\"android\\u0044iamond\":1000000}}"));
            reject("{\"status\":{\"androidDiamond\":1,\"androidDiamond\":2}}");
            reject("{\"status\":{\"androidDiamond\":\"1\"}}");
            reject("{\"status\":{\"androidDiamond\":[]}}");
            reject("{\"other\":{}}");
            reject("{\"status\":{}} trailing");
            reject("{\"status\":{\"other\":01}}");
            System.out.println("JSON counter patch: preservation and refusal checks passed");
          }
        }
    ''',
}


def main():
    with tempfile.TemporaryDirectory(dir=VERIFY) as folder:
        folder = Path(folder)
        classes = folder / "classes"
        classes.mkdir()
        files = []
        for relative, text in FIXTURES.items():
            path = folder / "fixtures" / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(text, encoding="utf-8")
            files.append(path)
        production = ROOT / "storage-src"
        files += list((production / "com").rglob("*.java"))
        files += list((production / "org").rglob("*.java"))
        real_sources = WORK / "phone-local/server-src/src/main/java/com/hypergryph/arknights/core"
        files += list((real_sources / "dao").glob("*.java"))
        files += list((real_sources / "pojo").glob("*.java"))
        command = [str(JDK / "javac.exe"), "--release", "8", "-Xlint:-options", "-encoding", "UTF-8", "-classpath", str(WORK / "phone-local/android.jar"), "-d", str(classes)]
        subprocess.run(command + [str(path) for path in files], check=True)
        for path in classes.rglob("*.class"):
            assert int.from_bytes(path.read_bytes()[6:8], "big") == 52
        print("Java 8 compilation: new classes, 3 original DAOs, 5 original POJOs, parent API contract passed")
        # This program never loads Android SQLite classes or starts a server/application.
        subprocess.run([str(JDK / "java.exe"), "-classpath", str(classes), "com.codex.arklocal.storage.CounterPatchTest"], check=True)


if __name__ == "__main__":
    main()
