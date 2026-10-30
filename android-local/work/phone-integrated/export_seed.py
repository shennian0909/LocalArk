#!/usr/bin/env python3
"""Offline mysqldump -> complete private arklocal-snapshot v1. Stdlib only.

Never executes SQL, connects to a database, overwrites an output, or prints row data.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import sys
import tempfile
from dataclasses import dataclass


COLUMNS = {
    "account": ("uid", "phone", "password", "secret", "user", "mails", "assistCharList", "friend", "ban"),
    "mail": ("id", "name", "from", "subject", "content", "items"),
    "orders": ("id", "secret", "order_id", "store_id", "times", "price", "create_at", "good_id", "product_name"),
}
NUMERIC = {"account": {"uid", "ban"}, "mail": {"id"}, "orders": {"id", "store_id", "times", "price"}}
JSON_COLUMNS = {"user", "mails", "assistCharList", "friend", "items"}
MAX_LONG = (1 << 63) - 1


class SeedError(ValueError):
    """Messages must be fixed text: never include source literals or credentials."""


@dataclass(frozen=True)
class Token:
    kind: str
    value: str


def statements(text: str):
    """SQL lexer: comments and semicolons inside quoted strings are not delimiters."""
    i, n = 0, len(text)
    current = []
    escapes = {"0": "\0", "b": "\b", "n": "\n", "r": "\r", "t": "\t", "Z": "\x1a"}
    while i < n:
        c = text[i]
        if c.isspace() or c == "\ufeff":
            i += 1
        elif c == "#" or (text.startswith("--", i) and (i + 2 == n or text[i + 2].isspace())):
            end = text.find("\n", i)
            i = n if end < 0 else end + 1
        elif text.startswith("/*", i):
            end = text.find("*/", i + 2)
            if end < 0:
                raise SeedError("Unterminated dump comment")
            # NO_BACKSLASH_ESCAPES changes string decoding; fail rather than guess.
            if "NO_BACKSLASH_ESCAPES" in text[i:end].upper():
                raise SeedError("Unsupported dump string-escape mode")
            i = end + 2
        elif c in "'\"`":
            quote, result = c, []
            i += 1
            while True:
                if i >= n:
                    raise SeedError("Unterminated quoted dump value")
                c = text[i]
                i += 1
                if c == quote:
                    if i < n and text[i] == quote:
                        result.append(quote)
                        i += 1
                    else:
                        break
                elif c == "\\" and quote != "`":
                    if i >= n:
                        raise SeedError("Unterminated dump escape")
                    escaped = text[i]
                    i += 1
                    # MySQL preserves slash before LIKE wildcard escapes.
                    result.append("\\" + escaped if escaped in "%_" else escapes.get(escaped, escaped))
                else:
                    result.append(c)
            current.append(Token("id" if quote == "`" else "string", "".join(result)))
        elif c == ";":
            if current:
                yield current
            current = []
            i += 1
        elif c.isalnum() or c in "_$":
            start = i
            while i < n and (text[i].isalnum() or text[i] in "_$"):
                i += 1
            current.append(Token("word", text[start:i]))
        else:
            current.append(Token("symbol", c))
            i += 1
    if current:
        raise SeedError("Dump ends before a statement delimiter")


def keyword(token: Token, value: str) -> bool:
    return token.kind == "word" and token.value.upper() == value


def name(tokens, index):
    if index >= len(tokens) or tokens[index].kind not in {"word", "id"}:
        raise SeedError("Expected a dump identifier")
    value = tokens[index].value
    index += 1
    if index < len(tokens) and tokens[index].value == ".":
        return name(tokens, index + 1)
    return value, index


def groups(tokens, start):
    if start >= len(tokens) or tokens[start].value != "(":
        raise SeedError("Expected a parenthesized dump list")
    depth, group, result = 1, [], []
    i = start + 1
    while i < len(tokens):
        token = tokens[i]
        if token.kind == "symbol" and token.value == "(":
            depth += 1
        elif token.kind == "symbol" and token.value == ")":
            depth -= 1
            if depth == 0:
                if group:
                    result.append(group)
                return result, i + 1
        if token.kind == "symbol" and token.value == "," and depth == 1:
            if not group:
                raise SeedError("Empty dump list element")
            result.append(group)
            group = []
        else:
            group.append(token)
        i += 1
    raise SeedError("Unterminated dump list")


def literal(tokens):
    if len(tokens) == 1 and keyword(tokens[0], "NULL"):
        return None
    if len(tokens) == 1 and tokens[0].kind == "string":
        return tokens[0].value
    if len(tokens) == 2 and tokens[0].kind == "word" and tokens[0].value.lower() in {"_utf8", "_utf8mb4", "_binary"} and tokens[1].kind == "string":
        return tokens[1].value
    if len(tokens) == 1 and re.fullmatch(r"0[xX][0-9a-fA-F]+", tokens[0].value):
        encoded = tokens[0].value[2:]
    elif len(tokens) == 2 and keyword(tokens[0], "X") and tokens[1].kind == "string":
        encoded = tokens[1].value
    else:
        numeric = "".join(token.value for token in tokens)
        plain_integer = len(tokens) == 1 and tokens[0].kind == "word"
        signed_integer = len(tokens) == 2 and tokens[0] == Token("symbol", "-") and tokens[1].kind == "word"
        if (plain_integer or signed_integer) and re.fullmatch(r"-?\d+", numeric):
            number = int(numeric)
            if not -(1 << 63) <= number <= MAX_LONG:
                raise SeedError("Dump integer exceeds SQLite range")
            return number
        raise SeedError("Unsupported dump expression; no output created")
    try:
        return bytes.fromhex(encoded).decode("utf-8", errors="strict")
    except (ValueError, UnicodeError):
        raise SeedError("Dump binary value is not lossless UTF-8 text") from None


def canonical_columns(table, names):
    canonical = {c.lower(): c for c in COLUMNS[table]}
    try:
        result = [canonical[c.lower()] for c in names]
    except KeyError:
        raise SeedError("Dump has unknown storage columns") from None
    if len(result) != len(set(result)):
        raise SeedError("Dump has duplicate storage columns")
    return result


def convert_dump(text):
    tables = {table: [] for table in COLUMNS}
    schema = {}
    next_ids = {"account": 10000000, "mail": 1, "orders": 1}
    seen_ids = {table: set() for table in COLUMNS}
    for tokens in statements(text):
        if keyword(tokens[0], "SET") and any("NO_BACKSLASH_ESCAPES" in t.value.upper() for t in tokens):
            raise SeedError("Unsupported dump string-escape mode")
        if len(tokens) >= 3 and keyword(tokens[0], "CREATE") and keyword(tokens[1], "TABLE"):
            start = 5 if len(tokens) > 4 and [t.value.upper() for t in tokens[2:5]] == ["IF", "NOT", "EXISTS"] else 2
            table, start = name(tokens, start)
            table = table.lower()
            if table not in COLUMNS:
                continue
            if table in schema:
                raise SeedError("Repeated storage table definition")
            definitions, end = groups(tokens, start)
            names = []
            for definition in definitions:
                if definition[0].kind == "id" or (definition[0].kind == "word" and definition[0].value.upper() not in {"PRIMARY", "KEY", "UNIQUE", "CONSTRAINT", "CHECK", "FOREIGN", "INDEX"}):
                    names.append(definition[0].value)
            columns = canonical_columns(table, names)
            if set(columns) != set(COLUMNS[table]):
                raise SeedError("Storage dump schema is incomplete")
            schema[table] = columns
            for i in range(end, len(tokens) - 2):
                if keyword(tokens[i], "AUTO_INCREMENT") and tokens[i + 1].value == "=":
                    amount = literal(tokens[i + 2:i + 3])
                    if not isinstance(amount, int) or not 1 <= amount < MAX_LONG:
                        raise SeedError("Invalid dump next ID")
                    next_ids[table] = max(next_ids[table], amount)
        elif keyword(tokens[0], "INSERT"):
            if len(tokens) < 3 or not keyword(tokens[1], "INTO"):
                raise SeedError("Unsupported dump insert mode")
            table, start = name(tokens, 2)
            table = table.lower()
            if table not in COLUMNS:
                continue
            if table not in schema:
                raise SeedError("Storage rows precede table definition")
            columns = schema[table]
            if tokens[start].value == "(":
                raw_columns, start = groups(tokens, start)
                if any(len(c) != 1 or c[0].kind not in {"id", "word"} for c in raw_columns):
                    raise SeedError("Invalid dump insert columns")
                columns = canonical_columns(table, [c[0].value for c in raw_columns])
            if start >= len(tokens) or not (keyword(tokens[start], "VALUES") or keyword(tokens[start], "VALUE")):
                raise SeedError("Unsupported dump insert syntax")
            start += 1
            row_count = 0
            while start < len(tokens):
                cells, start = groups(tokens, start)
                if len(cells) != len(columns):
                    raise SeedError("Dump row column count differs from schema")
                row = {column: None for column in COLUMNS[table]}
                row.update(zip(columns, (literal(cell) for cell in cells)))
                for column in COLUMNS[table]:
                    value = row[column]
                    if value is None:
                        continue
                    if column in NUMERIC[table]:
                        if isinstance(value, str) and re.fullmatch(r"-?\d+", value):
                            value = int(value)
                        if not isinstance(value, int) or not -(1 << 63) <= value <= MAX_LONG:
                            raise SeedError("Dump numeric column is not a SQLite integer")
                    elif not isinstance(value, str):
                        value = str(value)
                    if column in JSON_COLUMNS:
                        try:
                            document = json.loads(value, parse_constant=lambda _: (_ for _ in ()).throw(ValueError()))
                            if not isinstance(document, (dict, list)):
                                raise ValueError()
                        except (ValueError, TypeError, RecursionError):
                            raise SeedError("Dump contains an invalid JSON document") from None
                    row[column] = value
                key = row[COLUMNS[table][0]]
                if not isinstance(key, int) or not 0 <= key < MAX_LONG - 1:
                    raise SeedError("Missing or invalid primary key")
                if key in seen_ids[table]:
                    raise SeedError("Duplicate dump primary key; refusing to replace data")
                seen_ids[table].add(key)
                next_ids[table] = max(next_ids[table], key + 1)
                tables[table].append(row)
                row_count += 1
                if start < len(tokens):
                    if tokens[start].value != ",":
                        raise SeedError("Unsupported trailing insert clause")
                    start += 1
                    if start == len(tokens):
                        raise SeedError("Incomplete dump row list")
            if not row_count:
                raise SeedError("Dump insert contains no rows")
        elif keyword(tokens[0], "REPLACE") or keyword(tokens[0], "UPDATE") or keyword(tokens[0], "DELETE"):
            raise SeedError("Mutable SQL is not supported in a snapshot dump")
    if set(schema) != set(COLUMNS):
        raise SeedError("Dump does not define all three storage tables")
    for table in tables:
        tables[table].sort(key=lambda row: row[COLUMNS[table][0]])
    return {"format": "arklocal-snapshot", "version": 1, "tables": tables, "nextIds": next_ids}


def write_new(path: Path, snapshot):
    """Publish a complete file with an exclusive hard link; never replace an existing path."""
    path = path.resolve()
    if path.exists():
        raise SeedError("Output already exists; refusing to overwrite")
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", encoding="utf-8", newline="\n", dir=path.parent, prefix=".seed-", suffix=".tmp", delete=False) as output:
            temporary = Path(output.name)
            json.dump(snapshot, output, ensure_ascii=False, separators=(",", ":"), allow_nan=False)
            output.write("\n")
            output.flush()
            os.fsync(output.fileno())
        os.link(temporary, path)  # Atomic create; fails on collision or unsupported filesystem.
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)


def main(argv=None):
    base = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=base.parents[1] / "outputs/Arknights-Cloud/private/save-export.sql")
    parser.add_argument("--output", type=Path, default=base / "storage-src/assets/seed.json")
    args = parser.parse_args(argv)
    try:
        if args.input.resolve() == args.output.resolve():
            raise SeedError("Input and output must be different files")
        snapshot = convert_dump(args.input.read_text(encoding="utf-8-sig", errors="strict"))
        write_new(args.output, snapshot)
    except SeedError as error:
        print("Seed export failed: " + str(error), file=sys.stderr)
        return 1
    except (OSError, UnicodeError, IndexError, RecursionError):
        # Do not expose SQL fragments, paths containing user data, or parser tracebacks.
        print("Seed export failed: file, encoding, or dump structure error; source preserved", file=sys.stderr)
        return 1
    print("Seed export OK: " + ", ".join(f"{table}={len(rows)}" for table, rows in snapshot["tables"].items()))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
