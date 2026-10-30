"""Host-only regression tests. Does not launch Android or open the source database."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sqlite3
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("export_seed", ROOT / "export_seed.py")
seed = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = seed
spec.loader.exec_module(seed)
VERIFY = (ROOT / "storage-src/.verify").resolve()
VERIFY.mkdir(exist_ok=True)
assert VERIFY.is_relative_to((ROOT / "storage-src").resolve())


def schema(order=None):
    result = []
    for table, columns in seed.COLUMNS.items():
        columns = list(columns)
        if order and table == "account":
            columns = order
        result.append("CREATE TABLE `" + table + "` (" + ",".join("`" + c + "` text" for c in columns)
                      + ", PRIMARY KEY (`" + columns[0] + "`)) AUTO_INCREMENT=3;")
    return "\n".join(result)


class DumpTests(unittest.TestCase):
    def test_multiline_escape_comments_and_column_names(self):
        sql = schema() + r'''
        -- ignored INSERT INTO account VALUES ('not data');
        INSERT INTO `db`.`mail` (`content`,`id`,`name`,`items`) VALUE
        ('line1\nline2; quote\' and ''double'' \\ \0\b\r\t\Z \%\_ 中文',7,'a,b','[]'),
        ('actual
        newline',8,'other','[]');
        /* INSERT INTO mail VALUES (99); */
        '''
        result = seed.convert_dump(sql)["tables"]["mail"]
        self.assertEqual([r["id"] for r in result], [7, 8])
        self.assertEqual(result[0]["content"], "line1\nline2; quote' and 'double' \\ \0\b\r\t\x1a \\%\\_ 中文")
        self.assertIn("\n", result[1]["content"])
        self.assertIsNone(result[0]["from"])

    def test_implicit_column_order_comes_from_create(self):
        columns = list(reversed(seed.COLUMNS["account"]))
        values = {c: "NULL" for c in columns}
        values.update(uid="10000000", user="'{}'", ban="0")
        result = seed.convert_dump(schema(columns) + "INSERT INTO account VALUES (" + ",".join(values[c] for c in columns) + ");")
        self.assertEqual(result["tables"]["account"][0]["uid"], 10000000)
        self.assertEqual(result["nextIds"]["account"], 10000001)

    def test_complete_snapshot_preserves_private_fields_without_printing(self):
        document = json.dumps({"status": {"gold": 123}, "access_token": "sentinel-auth", "nested": [{"password": "sentinel-pass"}]})
        escaped = document.replace("\\", "\\\\").replace("'", "\\'")
        sql = schema() + "INSERT INTO account (uid,password,secret,user) VALUES (1,'sentinel-pass','sentinel-auth','" + escaped + "');"
        result = seed.convert_dump(sql)
        row = result["tables"]["account"][0]
        self.assertEqual(row["password"], "sentinel-pass")
        self.assertEqual(row["secret"], "sentinel-auth")
        self.assertEqual(json.loads(row["user"])["status"]["gold"], 123)
        self.assertEqual(json.loads(row["user"])["access_token"], "sentinel-auth")

    def test_json_without_credentials_preserves_original_text(self):
        result = seed.convert_dump(schema() + "INSERT INTO mail (id,items) VALUES (0,'[  {\"count\":1234567890123456789012345} ]');")
        self.assertEqual(result["tables"]["mail"][0]["items"], '[  {"count":1234567890123456789012345} ]')

    def test_null_introducer_and_hex_text(self):
        rows = seed.convert_dump(schema() + "INSERT INTO mail (id,name,subject,content) VALUES (1,_utf8mb4'a',X'6162',NULL),(2,0x6162,'x','y');")["tables"]["mail"]
        self.assertEqual(rows[0]["subject"], "ab")
        self.assertEqual(rows[1]["name"], "ab")
        self.assertIsNone(rows[0]["content"])

    def test_fail_closed_on_unsupported_or_incomplete_input(self):
        bad = ["INSERT INTO mail (id) VALUES (1+2);", "INSERT INTO mail (id) VALUES (1 2);",
               "INSERT INTO mail (id) VALUES (1),(1);", "INSERT INTO mail (id) VALUES (NULL);",
               "INSERT INTO mail (id,items) VALUES (1,'bad json');", "INSERT INTO mail (unknown) VALUES ('x');",
               "INSERT INTO mail (id) VALUES (1) ON DUPLICATE KEY UPDATE id=1;",
               "INSERT INTO mail (id) VALUES (1),;", "INSERT INTO mail (id) VALUES (1)",
               "INSERT INTO mail (id) VALUES (9223372036854775808);", "/* never closes",
               "SET SQL_MODE='NO_BACKSLASH_ESCAPES';"]
        for suffix in bad:
            with self.subTest(case=bad.index(suffix)), self.assertRaises(seed.SeedError):
                seed.convert_dump(schema() + suffix)

    def test_no_replacement_of_existing_output(self):
        with tempfile.TemporaryDirectory(dir=VERIFY) as folder:
            path = Path(folder) / "snapshot.json"
            seed.write_new(path, seed.convert_dump(schema()))
            original = path.read_bytes()
            with self.assertRaises(seed.SeedError):
                seed.write_new(path, {"replacement": True})
            self.assertEqual(path.read_bytes(), original)
            self.assertEqual(len(list(Path(folder).iterdir())), 1)

    def test_cli_failures_never_print_literals(self):
        with tempfile.TemporaryDirectory(dir=VERIFY) as folder:
            path = Path(folder) / "bad.sql"
            path.write_text(schema() + "INSERT INTO mail (id) VALUES ('sentinel-private-value');", encoding="utf-8")
            capture = io.StringIO()
            with contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
                result = seed.main(["--input", str(path), "--output", str(Path(folder) / "out.json")])
            self.assertEqual(result, 1)
            self.assertNotIn("sentinel", capture.getvalue())

    def test_real_dump_is_read_only_and_export_is_complete(self):
        source = ROOT.parents[1] / "outputs/Arknights-Cloud/private/save-export.sql"
        before = hashlib.sha256(source.read_bytes()).digest()
        result = seed.convert_dump(source.read_text(encoding="utf-8-sig"))
        self.assertEqual(set(result["tables"]), {"account", "mail", "orders"})
        self.assertGreater(len(result["tables"]["account"]), 0)
        for table, rows in result["tables"].items():
            for row in rows:
                self.assertEqual(set(row), set(seed.COLUMNS[table]))
        self.assertEqual(hashlib.sha256(source.read_bytes()).digest(), before)

    def test_successful_cli_prints_counts_only(self):
        with tempfile.TemporaryDirectory(dir=VERIFY) as folder:
            path = Path(folder) / "private.sql"
            path.write_text(schema() + "INSERT INTO account (uid,password,secret) VALUES (1,'sentinel-pass','sentinel-token');", encoding="utf-8")
            capture = io.StringIO()
            output = Path(folder) / "out.json"
            with contextlib.redirect_stdout(capture), contextlib.redirect_stderr(capture):
                result = seed.main(["--input", str(path), "--output", str(output)])
            self.assertEqual(result, 0)
            self.assertNotIn("sentinel", capture.getvalue())
            self.assertEqual(json.loads(output.read_text(encoding="utf-8"))["tables"]["account"][0]["secret"], "sentinel-token")


class SqliteSchemaTests(unittest.TestCase):
    def test_actual_java_ddl_and_sqlite_binding_rollback(self):
        import re
        source = (ROOT / "storage-src/com/codex/arklocal/storage/StoreSchema.java").read_text(encoding="utf-8")
        ddl = re.findall(r'"(CREATE TABLE IF NOT EXISTS [^"]+)"', source)
        self.assertEqual(len(ddl), 3)
        with sqlite3.connect(":memory:") as db:
            for statement in ddl:
                db.execute(statement)
            for table, columns in seed.COLUMNS.items():
                self.assertEqual([c[1] for c in db.execute("PRAGMA table_info(`" + table + "`)")], list(columns))
            # Opaque JSON, quotes, NULL and NUL are data, not SQL text.
            payload = "quoted '\"; \0 中文"
            db.execute("INSERT INTO mail(name,items) VALUES (?,?)", (payload, "[]"))
            self.assertEqual(db.execute("SELECT name FROM mail").fetchone()[0], payload)
            db.commit()
            db.execute("BEGIN")
            db.execute("UPDATE mail SET content=?", ("temporary",))
            db.rollback()
            self.assertIsNone(db.execute("SELECT content FROM mail").fetchone()[0])
            # All JSON-filter rewritten queries retain aliases on an empty result.
            cursor = db.execute("SELECT uid AS uid,? AS status,? AS chars,? AS socialAssistCharList,assistCharList AS assistCharList,friend AS friend FROM account WHERE uid=? AND 0", (None, None, None, 1))
            self.assertEqual([c[0] for c in cursor.description], ["uid", "status", "chars", "socialAssistCharList", "assistCharList", "friend"])


if __name__ == "__main__":
    unittest.main(verbosity=2)
