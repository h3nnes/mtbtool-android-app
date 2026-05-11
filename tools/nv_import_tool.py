#!/usr/bin/env python3
"""
nv_import_tool.py — mtbtool NV import JSON generator / converter

Subcommands:
  new-format   Convert old shell-command .txt files to the new JSON format
  from-dir     Generate JSON from NV item files in the current directory (flat, no subdirs)
  from-tree    Generate JSON by walking an exported EFS root tree (recursive)

Usage examples:
  python3 nv_import_tool.py new-format 17Uv4.txt
  python3 nv_import_tool.py new-format 17Uv4.txt -o my_import.json

  # In a directory containing NV item files:
  python3 nv_import_tool.py from-dir --efs-path /nv/item_files/modem/nr5g/RRC/ --op w --sim sim0
  python3 nv_import_tool.py from-dir --efs-path /nv/item_files/modem/nr5g/RRC/ --op d --sim dualsim

  python3 nv_import_tool.py from-tree /path/to/efs/root --sim sim0
  python3 nv_import_tool.py from-tree /path/to/efs/root --sim dualsim -o output.json
"""

import argparse
import json
import os
import sys
from collections import OrderedDict
from pathlib import Path


DEFAULT_OUTPUT = "bulk-nv-import.json"


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def decimal_list_to_hex(decimals: list[str]) -> str:
    """Convert a list of decimal byte strings to a compact hex string."""
    return "".join(f"{int(d):02x}" for d in decimals)


def bytes_to_hex(data: bytes) -> str:
    """Convert raw bytes to a compact lowercase hex string."""
    return data.hex()


def write_json(data: OrderedDict, output_path: str) -> None:
    with open(output_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2)
    print(f"Written: {output_path}")


# ---------------------------------------------------------------------------
# Subcommand: new-format
# ---------------------------------------------------------------------------

def cmd_new_format(args) -> None:
    """Convert old shell-command .txt format to new JSON format."""
    input_path = args.input
    output_path = args.output or DEFAULT_OUTPUT

    with open(input_path, "r", encoding="utf-8") as f:
        lines = f.readlines()

    # Parse every non-blank, non-comment line into (slot, path, op, hex_data|None)
    # Old format tokens: [/vendor/bin/mtb,] 4, <op_code>, <slot>, <efs_path>, [bytes...]
    # op_code: 5 = write, 6 = delete
    entries = []  # list of (slot_int, efs_path, op, hex_str|None)

    for lineno, raw in enumerate(lines, 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        tokens = line.split()
        # Strip leading binary path if present
        if tokens[0] == "/vendor/bin/mtb":
            tokens = tokens[1:]
        # Expect: 4 <op_code> <slot> <path> [bytes...]
        if len(tokens) < 4 or tokens[0] != "4":
            print(f"  Warning: skipping unrecognised line {lineno}: {line[:80]}", file=sys.stderr)
            continue
        op_code = tokens[1]
        slot_str = tokens[2]
        efs_path = tokens[3]
        rest = tokens[4:]

        try:
            slot = int(slot_str)
        except ValueError:
            print(f"  Warning: invalid slot on line {lineno}, skipping.", file=sys.stderr)
            continue

        if op_code == "5":
            if not rest:
                print(f"  Warning: write with no data on line {lineno}, skipping.", file=sys.stderr)
                continue
            hex_str = decimal_list_to_hex(rest)
            entries.append((slot, efs_path, "w", hex_str))
        elif op_code == "6":
            entries.append((slot, efs_path, "d", None))
        else:
            print(f"  Warning: unknown op_code '{op_code}' on line {lineno}, skipping.", file=sys.stderr)

    if not entries:
        print("No valid entries found.", file=sys.stderr)
        sys.exit(1)

    # Group entries: detect pairs where slot-0 and slot-1 have identical
    # (path, op, data) — those become "dualsim". Solo entries stay as sim0/sim1.
    #
    # Strategy: collect all (path, op, hex) per slot. Then compare.
    # Build ordered list of (slot_key, path, filename, op, hex_str|None).

    # Index by (path, filename, op, hex_str) → set of slots seen
    from collections import defaultdict

    # Preserve entry order: use list + dict for dedup tracking
    # order_key → (efs_path, op, hex_str, first_seen_index)
    order_keys = []  # (full_path, op, hex_str_or_None) in first-seen order
    slot_map = defaultdict(set)  # order_key → {0, 1, ...}

    seen_order = {}  # order_key → index in order_keys

    for slot, full_path, op, hex_str in entries:
        key = (full_path, op, hex_str)
        if key not in seen_order:
            seen_order[key] = len(order_keys)
            order_keys.append(key)
        slot_map[key].add(slot)

    # Build output structure
    # We emit in document order:
    #   1. dualsim block (entries present in both slot 0 and slot 1, identical)
    #   2. sim0 block (entries only in slot 0, or differing)
    #   3. sim1 block (entries only in slot 1, or differing)
    #
    # "Flat structure" means one JSON entry per (full_path, op) — no path grouping.
    # But the JSON format requires path prefix blocks. We still group by directory
    # automatically (the path prefix is everything up to and including the last /).

    def path_prefix(full_path: str) -> tuple[str, str]:
        """Split /a/b/c/filename into ('/a/b/c/', 'filename')."""
        idx = full_path.rfind("/")
        if idx == -1:
            return ("", full_path)
        return (full_path[: idx + 1], full_path[idx + 1 :])

    # Separate into groups
    dualsim_keys = [k for k in order_keys if slot_map[k] == {0, 1}]
    sim0_keys    = [k for k in order_keys if slot_map[k] == {0}]
    sim1_keys    = [k for k in order_keys if slot_map[k] == {1}]
    # Entries that appear on both slots but with different data stay per-slot
    multi_slot_diff = [k for k in order_keys if len(slot_map[k]) > 1 and slot_map[k] != {0, 1}]
    # (shouldn't happen with integer slots 0/1, but be safe)

    def build_flat_block(keys):
        """Build a flat JSON block: {efs_prefix: {filename: {op, data?}}}."""
        block = OrderedDict()
        for full_path, op, hex_str in keys:
            prefix, filename = path_prefix(full_path)
            if prefix not in block:
                block[prefix] = OrderedDict()
            entry = OrderedDict()
            entry["op"] = op
            if op == "w":
                entry["data"] = hex_str
            block[prefix][filename] = entry
        return block

    output = OrderedDict()
    if dualsim_keys:
        output["dualsim"] = build_flat_block(dualsim_keys)
    if sim0_keys:
        output["sim0"] = build_flat_block(sim0_keys)
    if sim1_keys:
        output["sim1"] = build_flat_block(sim1_keys)

    if not output:
        print("No output generated.", file=sys.stderr)
        sys.exit(1)

    print(f"Converted {len(entries)} entries → "
          f"{len(dualsim_keys)} dualsim, {len(sim0_keys)} sim0-only, {len(sim1_keys)} sim1-only")
    write_json(output, output_path)


# ---------------------------------------------------------------------------
# Subcommand: from-dir
# ---------------------------------------------------------------------------

def cmd_from_dir(args) -> None:
    """
    Generate JSON from NV item files in the current directory (no subdirs).
    Files with _Subscription01 suffix → sim1; no suffix → sim0.
    --sim overrides: sim0, sim1, dualsim.
    """
    efs_path: str = args.efs_path
    op: str = args.op          # "w" or "d"
    sim: str = args.sim        # "sim0", "sim1", "dualsim"
    output_path: str = args.output or DEFAULT_OUTPUT

    if not efs_path.endswith("/"):
        efs_path += "/"

    cwd = Path(".")
    files = sorted(f for f in cwd.iterdir() if f.is_file() and not f.name.startswith("."))

    if not files:
        print("No files found in current directory.", file=sys.stderr)
        sys.exit(1)

    entries = OrderedDict()  # filename → entry dict

    for f in files:
        name = f.name
        entry = OrderedDict()
        entry["op"] = op
        if op == "w":
            data = f.read_bytes()
            entry["data"] = bytes_to_hex(data)
        entries[name] = entry

    if not entries:
        print("No entries generated.", file=sys.stderr)
        sys.exit(1)

    block = OrderedDict()
    block[efs_path] = entries

    output = OrderedDict()
    output[sim] = block

    print(f"Generated {len(entries)} entries under '{sim}' → '{efs_path}'")
    write_json(output, output_path)


# ---------------------------------------------------------------------------
# Subcommand: from-tree
# ---------------------------------------------------------------------------

def cmd_from_tree(args) -> None:
    """
    Walk an exported EFS root tree and generate JSON.
    All operations are write ("w").
    Slot is determined by _Subscription01 suffix in filename:
      - _Subscription01 suffix → sim1 (suffix stripped from key name)
      - no suffix              → sim0
    --sim overrides: sim0, sim1, dualsim (ignores per-file suffix detection).
    """
    root: str = args.root
    sim_override: str | None = args.sim   # None = use per-file detection
    output_path: str = args.output or DEFAULT_OUTPUT

    root_path = Path(root).resolve()
    if not root_path.is_dir():
        print(f"Error: '{root}' is not a directory.", file=sys.stderr)
        sys.exit(1)

    SUB01_SUFFIX = "_Subscription01"

    # Collect: per sim_key → per efs_prefix → {filename: entry}
    sim0_block: dict[str, OrderedDict] = {}
    sim1_block: dict[str, OrderedDict] = {}

    for file_path in sorted(root_path.rglob("*")):
        if not file_path.is_file():
            continue
        if file_path.name.startswith("."):
            continue

        name = file_path.name

        # Determine sim slot from filename suffix (unless --sim is set)
        if sim_override:
            effective_sim = sim_override
            clean_name = name
        else:
            if name.endswith(SUB01_SUFFIX):
                effective_sim = "sim1"
                clean_name = name[: -len(SUB01_SUFFIX)]
            else:
                effective_sim = "sim0"
                clean_name = name

        # EFS prefix = relative directory from root, re-rooted to "/"
        rel_dir = file_path.parent.relative_to(root_path)
        rel_dir_str = str(rel_dir)
        if rel_dir_str == ".":
            efs_prefix = "/"
        else:
            efs_prefix = "/" + rel_dir_str.replace(os.sep, "/") + "/"

        data = file_path.read_bytes()
        hex_str = bytes_to_hex(data)

        entry = OrderedDict([("op", "w"), ("data", hex_str)])

        if effective_sim in ("sim0", "dualsim"):
            if efs_prefix not in sim0_block:
                sim0_block[efs_prefix] = OrderedDict()
            sim0_block[efs_prefix][clean_name] = entry

        if effective_sim in ("sim1", "dualsim"):
            if efs_prefix not in sim1_block:
                sim1_block[efs_prefix] = OrderedDict()
            sim1_block[efs_prefix][clean_name] = entry

    if not sim0_block and not sim1_block:
        print("No files found under the specified root.", file=sys.stderr)
        sys.exit(1)

    output = OrderedDict()

    if sim_override == "dualsim":
        output["dualsim"] = OrderedDict(sorted(sim0_block.items()))
    else:
        if sim0_block:
            output["sim0"] = OrderedDict(sorted(sim0_block.items()))
        if sim1_block:
            output["sim1"] = OrderedDict(sorted(sim1_block.items()))

    total = sum(len(v) for v in sim0_block.values()) + sum(len(v) for v in sim1_block.values())
    print(f"Generated {total} entries from tree '{root_path}'")
    write_json(output, output_path)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="mtbtool NV import JSON generator / converter",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    sub = parser.add_subparsers(dest="command", required=True)

    # ── new-format ──────────────────────────────────────────────────────────
    p_new = sub.add_parser(
        "new-format",
        help="Convert old shell-command .txt file to new JSON format",
    )
    p_new.add_argument("input", help="Path to the old-format .txt file")
    p_new.add_argument("-o", "--output", help=f"Output JSON file (default: {DEFAULT_OUTPUT})")

    # ── from-dir ────────────────────────────────────────────────────────────
    p_dir = sub.add_parser(
        "from-dir",
        help="Generate JSON from NV item files in the current directory (flat, no subdirs)",
    )
    p_dir.add_argument(
        "--efs-path",
        required=True,
        help="EFS path prefix for all files, e.g. /nv/item_files/modem/nr5g/RRC/",
    )
    p_dir.add_argument(
        "--op",
        required=True,
        choices=["w", "d"],
        help="Operation to apply to all files: w=write, d=delete",
    )
    p_dir.add_argument(
        "--sim",
        required=True,
        choices=["sim0", "sim1", "dualsim"],
        help="SIM slot target",
    )
    p_dir.add_argument("-o", "--output", help=f"Output JSON file (default: {DEFAULT_OUTPUT})")

    # ── from-tree ───────────────────────────────────────────────────────────
    p_tree = sub.add_parser(
        "from-tree",
        help="Generate JSON by walking an exported EFS root tree (recursive, always write)",
    )
    p_tree.add_argument("root", help="Path to the EFS root directory")
    p_tree.add_argument(
        "--sim",
        choices=["sim0", "sim1", "dualsim"],
        default=None,
        help=(
            "Override SIM slot for all files. "
            "If omitted, slot is detected per file: "
            "_Subscription01 suffix → sim1, no suffix → sim0."
        ),
    )
    p_tree.add_argument("-o", "--output", help=f"Output JSON file (default: {DEFAULT_OUTPUT})")

    args = parser.parse_args()

    if args.command == "new-format":
        cmd_new_format(args)
    elif args.command == "from-dir":
        cmd_from_dir(args)
    elif args.command == "from-tree":
        cmd_from_tree(args)


if __name__ == "__main__":
    main()
