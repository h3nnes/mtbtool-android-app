# Bulk EFS NV Import — File Format & Tools

mtbtool v2.0 uses a JSON file format for bulk EFS NV item imports. This
replaces the old shell-command `.txt` format. Both file types are accepted by
the app (MIME types `text/plain` and `application/json`).

---

## File structure

```json
{
  "dualsim": {
    "/nv/item_files/modem/nr5g/RRC/": {
      "nr5g_rrc_nv_item": { "op": "w", "data": "0100" }
    }
  },
  "sim0": {
    "/nv/item_files/modem/lte/RRC/": {
      "lte_rrc_nv_item": { "op": "w", "data": "ff00" },
      "another_item":    { "op": "d" }
    }
  },
  "sim1": {
    "/nv/item_files/modem/lte/RRC/": {
      "lte_rrc_nv_item": { "op": "w", "data": "aa01" }
    }
  }
}
```

### Top-level slot keys

| Key       | Meaning                                  |
|-----------|------------------------------------------|
| `sim0`    | Apply to SIM slot 0 only                 |
| `sim1`    | Apply to SIM slot 1 only                 |
| `dualsim` | Apply identically to both SIM slots      |

All three keys are optional. They are processed in document order; if the same
EFS path appears in multiple blocks, the last write wins.

### Path prefix blocks

Each slot key maps to an object whose keys are EFS directory paths — the
**path prefix** shared by all entries in that block. The path prefix must end
with a `/`.

```json
"/nv/item_files/modem/nr5g/RRC/": { ... }
```

### Entry objects

Each entry inside a path prefix block is keyed by the **filename** (the last
component of the EFS path). Concatenating path prefix + filename gives the
full EFS path that is written or deleted.

Each entry object has:

| Field  | Required | Value                                                   |
|--------|----------|---------------------------------------------------------|
| `"op"` | always   | `"w"` (write) or `"d"` (delete)                        |
| `"data"` | write only | Lowercase hex string of the raw byte content.      |

The `data` field must be a non-empty, even-length hex string, e.g. `"0100ff"`.
The app converts each hex byte pair to a decimal integer at execution time
(as required by the `mtb` command).

### Delete entries

For a delete operation, omit the `data` field entirely:

```json
"obsolete_item": { "op": "d" }
```

---

## Minimal example

Write one item to both SIMs and delete a stale item on SIM 0 only:

```json
{
  "dualsim": {
    "/nv/item_files/modem/mmode/": {
      "cm_mode_pref": { "op": "w", "data": "0500" }
    }
  },
  "sim0": {
    "/nv/item_files/modem/mmode/": {
      "old_mode_pref": { "op": "d" }
    }
  }
}
```

---

## Generating import files with `nv_import_tool.py`

The `tools/nv_import_tool.py` script provides three subcommands for
generating import files. Output defaults to `bulk-nv-import.json`; override
with `-o <file>`.

### 1. Convert an old-format `.txt` file

```
python3 nv_import_tool.py new-format <input.txt> [-o output.json]
```

Reads a file in the old shell-command format (one `mtb` invocation per line),
groups identical slot-0/slot-1 pairs as `dualsim`, and emits a JSON file.

```
python3 nv_import_tool.py new-format 17Uv4.txt
# → bulk-nv-import.json (153 entries, grouped automatically)
```

### 2. Generate from files in the current directory

```
python3 nv_import_tool.py from-dir \
    --efs-path /nv/item_files/modem/nr5g/RRC/ \
    --op w \
    --sim sim0 \
    [-o output.json]
```

Reads every non-hidden file in the current directory and writes them as NV
items under the given EFS path prefix and SIM slot.

| Argument      | Values                    | Description                    |
|---------------|---------------------------|--------------------------------|
| `--efs-path`  | any EFS directory path    | Prefix applied to all files    |
| `--op`        | `w` or `d`                | Operation for all files        |
| `--sim`       | `sim0`, `sim1`, `dualsim` | SIM slot target                |

### 3. Generate from an exported EFS tree

```
python3 nv_import_tool.py from-tree <root_dir> [--sim sim0|sim1|dualsim] [-o output.json]
```

Walks `<root_dir>` recursively. All operations are **write**. EFS paths are
reconstructed from the directory structure relative to `<root_dir>`.

**Automatic SIM slot detection** (when `--sim` is not given):

- Filename ends with `_Subscription01` → `sim1` (suffix is stripped from the
  EFS key name)
- No such suffix → `sim0`

Use `--sim dualsim` to write all discovered files to both SIM slots.

```
python3 nv_import_tool.py from-tree ./efs_backup/
python3 nv_import_tool.py from-tree ./efs_backup/ --sim dualsim -o both_sims.json
```

---

## Notes

- Entries are executed in document order. Later entries override earlier ones
  if they target the same EFS path and slot.
- Hex bytes are validated at parse time; the app rejects files with invalid or
  odd-length hex strings before starting the import.
- The `dualsim` block is expanded to two commands (slot 0 then slot 1) per
  entry.
