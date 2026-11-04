#!/usr/bin/env python3
"""Keep the retired cashier/staff RPC contract generated from migration history."""
from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MIGRATIONS = ROOT / "supabase" / "migrations"
CONTRACT = ROOT / "supabase" / "tests" / "20261029010000_admin_only_role_contract.sql"
RETIREMENT_FILES = (
    "20261023050000_retire_cashier_feature.sql",
    "20261023070000_remove_cashier_customer_sync_rpcs.sql",
)
GENERATED_START = "  -- BEGIN GENERATED RETIRED FUNCTION ASSERTIONS. Regenerate with scripts/check_retired_functions.py --write."
GENERATED_END = "  -- END GENERATED RETIRED FUNCTION ASSERTIONS."
STAGED_FILES = (
    "list_admin_cashiers.sql",
    "verify_and_restore_staff_by_phone.sql",
    "fetch_cashier_restoration.sql",
    "fetch_cashier_metadata.sql",
    "fetch_cashier_financial_envelopes.sql",
)
# This private trigger is the new ADMIN-only guard, not a retired staff RPC.
ALLOWED_RETIREMENT_GUARDS = {"prevent_new_cashier_memberships"}


def uncomment(sql: str) -> str:
    sql = re.sub(r"/\*.*?\*/", "", sql, flags=re.S)
    return re.sub(r"--[^\n]*", "", sql)


def extract_drops(path: Path) -> list[str]:
    source = uncomment(path.read_text(encoding="utf-8"))
    drops: list[str] = []
    for match in re.finditer(r"\bDROP\s+FUNCTION\s+(?:IF\s+EXISTS\s+)?", source, re.I):
        end = source.find(";", match.end())
        if end < 0:
            raise ValueError(f"Unterminated DROP FUNCTION in {path}")
        statement = source[match.end():end].strip()
        target = re.match(
            r'(?P<name>(?:"?[A-Za-z_][\w$]*"?\.)?"?[A-Za-z_][\w$]*"?)\s*\((?P<args>.*)\)\s*$',
            statement,
            re.S,
        )
        if not target:
            raise ValueError(f"Cannot parse DROP FUNCTION target in {path}: {statement}")
        name = target.group("name").replace('"', "").lower()
        if "." not in name:
            raise ValueError(f"DROP FUNCTION must be schema-qualified in {path}: {name}")
        args = re.sub(r"\s+", " ", target.group("args").strip())
        drops.append(f"{name}({args})")
    return drops


def generated_lines(functions: list[str]) -> str:
    lines = [GENERATED_START]
    for signature in functions:
        escaped = signature.replace("'", "''")
        lines.extend((
            f"  IF to_regprocedure('{escaped}') IS NOT NULL THEN",
            f"    RAISE EXCEPTION 'FAIL: retired function remains installed: {escaped}';",
            "  END IF;",
        ))
    lines.append(GENERATED_END)
    return "\n".join(lines)


def replace_generated(contract: str, block: str) -> str:
    pattern = re.compile(
        re.escape(GENERATED_START) + r".*?" + re.escape(GENERATED_END),
        re.S,
    )
    if not pattern.search(contract):
        raise ValueError("Generated assertion markers are missing from SQL contract")
    return pattern.sub(lambda _: block, contract, count=1)


def scan_recreations(functions: list[str]) -> list[str]:
    retired_names = {item.split("(", 1)[0].split(".")[-1] for item in functions}
    offenders: list[str] = []
    create_re = re.compile(
        r"\bCREATE\s+(?:OR\s+REPLACE\s+)?FUNCTION\s+"
        r'(?P<name>(?:"?[A-Za-z_][\w$]*"?\.)?"?[A-Za-z_][\w$]*"?)\s*\(',
        re.I,
    )
    for path in sorted(MIGRATIONS.glob("*.sql")):
        stamp = re.match(r"(\d+)", path.name)
        if not stamp or int(stamp.group(1)) <= 20261023050000:
            continue
        source = uncomment(path.read_text(encoding="utf-8"))
        for match in create_re.finditer(source):
            name = match.group("name").replace('"', "").split(".")[-1].lower()
            if (
                name in retired_names
                or name.startswith("cashier_")
                or name.startswith("staff_")
                or "cashier" in name
                or "staff" in name
                or "permission_request" in name
            ) and name not in ALLOWED_RETIREMENT_GUARDS:
                offenders.append(f"{path.relative_to(ROOT)}: CREATE FUNCTION {name}")
    return offenders


def check_staged_headers() -> list[str]:
    folder = ROOT / "BOARDLOCK" / "staged_sql"
    errors: list[str] = []
    for name in STAGED_FILES:
        path = folder / name
        if not path.is_file():
            errors.append(f"missing staged SQL file: {path.relative_to(ROOT)}")
            continue
        header = "\n".join(path.read_text(encoding="utf-8-sig").splitlines()[:8]).lower()
        if not all(token in header for token in ("retired feature", "do not apply", "historical evidence")):
            errors.append(f"missing retirement warning header: {path.relative_to(ROOT)}")
    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--write", action="store_true", help="regenerate the embedded SQL assertions")
    args = parser.parse_args()

    functions: list[str] = []
    for filename in RETIREMENT_FILES:
        path = MIGRATIONS / filename
        if not path.is_file():
            print(f"FAIL: missing retirement migration {filename}")
            return 1
        functions.extend(extract_drops(path))
    functions = sorted(set(functions))
    if not functions:
        print("FAIL: no DROP FUNCTION targets extracted")
        return 1

    expected = generated_lines(functions)
    contract = CONTRACT.read_text(encoding="utf-8")
    updated = replace_generated(contract, expected)
    if args.write:
        CONTRACT.write_text(updated, encoding="utf-8")
        print(f"UPDATED: embedded assertions for {len(functions)} retired functions")
    elif updated != contract:
        print("FAIL: generated SQL assertions are stale; run python3 scripts/check_retired_functions.py --write")
        return 1
    else:
        print(f"PASS: {len(functions)} retired function signatures are asserted in the SQL contract")

    errors = scan_recreations(functions) + check_staged_headers()
    if errors:
        for error in errors:
            print(f"FAIL: {error}")
        return 1
    print("PASS: no retired function recreation found in post-retirement migrations")
    print(f"PASS: all {len(STAGED_FILES)} staged cashier SQL files carry retirement warnings")
    return 0


if __name__ == "__main__":
    sys.exit(main())
