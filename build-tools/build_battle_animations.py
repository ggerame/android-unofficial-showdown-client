# -*- coding: utf-8 -*-
"""
build_battle_animations.py

Downloads and extracts move animation data from the Pokémon Showdown web client
(battle-animations-moves.ts) plus the shared BattleOtherAnims / BattleStatusAnims
tables (battle-animations.ts), outputting a JSON file consumable by the Android
app's animation engine.

Source licensing (see the repo-root NOTICE file for attribution):
  - battle-animations-moves.ts  -> CC0-1.0 (public domain; the "moves" section)
  - battle-animations.ts        -> MIT     (the "other"/"status" sections + the
                                            BattleScene projection math)
Both are compatible with this app's Apache-2.0 license. Only animation *data* is
redistributed; the fx/ graphics it references are fetched at runtime.

Requires Node.js to be installed (used to execute the stripped animation script
with mock objects and record all animation calls).

Output is namespaced into three sections:
  {
    "moves":  { <moveId>:  { "anim": [...], "prepareAnim": [...], "residualAnim": [...] } },
    "other":  { <animName>: { "anim": [...] } },   // BattleOtherAnims
    "status": { <animName>: { "anim": [...] } }    // BattleStatusAnims
  }

A move whose only call is an "otherAnim" delegates to the matching entry in the
"other" section; the Android engine resolves the reference at runtime.

Call types:
  showEffect   – spawn a particle sprite from fx/ with position keyframes
  spriteAnim   – tween attacker or defender sprite to a new position
  spriteDelay  – delay a sprite's animation queue
  backgroundEffect – flash the background color
  wait         – advance the scene timer
  otherAnim    – delegate to a named BattleOtherAnim (e.g. "shake", "dance")

Effect names are fx/ particle basenames (e.g. "fireball"). Two special forms:
  "{attacker}" / "{defender}" – the participant's own Pokémon sprite is used.
  a full "https://..." URL     – a custom remote sprite (e.g. orderup's tatsugiri).

Positions are expressed relative to the attacker or defender sprite
(positionally: "attacker" = participant 0, "defender" = participant 1):
  {"rel": "attacker"|"defender", "axis": "x"|"y"|"z", "offset": <number>}
Scale, opacity, and time are kept as plain numbers.
"""

import os
import re
import sys
import json
import subprocess
from common import get_remote_data, write_into_file, finish

TS_URL = (
    "https://raw.githubusercontent.com/smogon/pokemon-showdown-client"
    "/master/play.pokemonshowdown.com/src/battle-animations-moves.ts"
)
# battle-animations.ts holds the shared BattleOtherAnims / BattleStatusAnims tables.
ENGINE_TS_URL = (
    "https://raw.githubusercontent.com/smogon/pokemon-showdown-client"
    "/master/play.pokemonshowdown.com/src/battle-animations.ts"
)
OUTPUT_DIR = "./psclient/src/main/res/raw"
OUTPUT_FILE = "battle_animations.json"

# Base coordinates assigned to each sprite when executing the animations.
# All position values in the captured output will be near one of these bases,
# allowing post-processing to reconstruct relative expressions.
# Bases are chosen far apart so the ±500-unit offsets used in animations
# don't overlap across different axes or sprites.
ATK_X, ATK_Y, ATK_Z = 10000, 20000, 30000
DEF_X, DEF_Y, DEF_Z = 40000, 50000, 60000
COORD_THRESHOLD = 5000  # maximum expected offset magnitude

COORD_BASES = [
    (ATK_X, "attacker", "x"),
    (ATK_Y, "attacker", "y"),
    (ATK_Z, "attacker", "z"),
    (DEF_X, "defender", "x"),
    (DEF_Y, "defender", "y"),
    (DEF_Z, "defender", "z"),
]

# ---------------------------------------------------------------------------
# Coordinate decoding
# ---------------------------------------------------------------------------

def decode_coord(v):
    """Convert an encoded coordinate value back to a relative position dict."""
    if not isinstance(v, (int, float)):
        return v
    for base, sprite, axis in COORD_BASES:
        if abs(v - base) < COORD_THRESHOLD:
            offset = v - base
            # Round away floating-point dust
            offset = int(offset) if offset == int(offset) else round(offset, 4)
            return {"rel": sprite, "axis": axis, "offset": offset}
    return v


def decode_pos(pos):
    """Decode a position object, converting x/y/z fields to relative form."""
    if not isinstance(pos, dict):
        return pos
    result = {}
    for k, v in pos.items():
        result[k] = decode_coord(v) if k in ("x", "y", "z") else v
    return result


def _decode_section(entries):
    """Decode coordinates for every entry (move/anim) within one section."""
    decoded = {}
    for entry_id, phases in entries.items():
        decoded_entry = {}
        for phase, calls in phases.items():
            decoded_phase = []
            for call in calls:
                c = dict(call)
                if c["type"] == "showEffect":
                    c["start"] = decode_pos(c.get("start"))
                    c["end"] = decode_pos(c.get("end"))
                elif c["type"] == "spriteAnim" and "pos" in c:
                    c["pos"] = decode_pos(c["pos"])
                decoded_phase.append(c)
            if decoded_phase:
                decoded_entry[phase] = decoded_phase
        if decoded_entry:
            decoded[entry_id] = decoded_entry
    return decoded


def decode_calls(raw):
    """Post-process all recorded animation calls, decoding coordinates.

    ``raw`` is namespaced: {"moves": {...}, "other": {...}, "status": {...}}.
    """
    return {section: _decode_section(entries) for section, entries in raw.items()}


# ---------------------------------------------------------------------------
# TypeScript stripping
# ---------------------------------------------------------------------------

def strip_typescript(ts_code):
    """
    Remove TypeScript-specific syntax from the animation file, producing
    JavaScript that Node.js can execute directly.
    """
    # Remove import lines entirely
    ts_code = re.sub(r"^import\b.*\n", "", ts_code, flags=re.MULTILINE)

    # Remove 'export' keyword (export const -> const)
    ts_code = re.sub(r"\bexport\s+", "", ts_code)

    # Remove top-level type annotation: "const BattleMoveAnims: AnimTable ="
    ts_code = re.sub(
        r"(const\s+BattleMoveAnims)\s*:\s*\w+\s*=",
        r"\1 =",
        ts_code,
    )

    # Remove type annotations on variable declarations ONLY.
    # Target: "let/const/var identifier: Type" – never bare "key: value" pairs.
    #
    # Case 1 – simple type or generic: "let x: JQuery" / "const x: Parameters<...>"
    ts_code = re.sub(
        r"(\b(?:let|const|var)\s+\w+)\s*:\s*\w[\w.<>\[\]|()\s\'\"\.`]*?(?=\s*[=,;\n])",
        r"\1",
        ts_code,
    )
    # Case 2 – remaining typed identifiers in multi-declarations like
    #   "let ball, wisp: JQuery" (after case 1 already stripped "let ball: JQuery")
    #   Only strip when the type starts with an uppercase letter (TS convention).
    ts_code = re.sub(r"(,\s*\w+)\s*:\s*[A-Z]\w*\b(?=\s*[,;\n])", r"\1", ts_code)

    # Remove "as [Type, ...]" cast at end of array spread expressions
    ts_code = re.sub(r"\s+as\s+\[[^\]]*\]", "", ts_code)

    # Remove non-null assertion "!" (but not "!=" or "!==")
    ts_code = re.sub(r"(\w)!(?![=])", r"\1", ts_code)

    return ts_code


def extract_other_status_tables(engine_ts):
    """
    Extract the BattleOtherAnims and BattleStatusAnims table definitions from
    battle-animations.ts.

    The definitions are renamed to RealOtherAnims / RealStatusAnims so they don't
    clash with the runner's mock ``BattleOtherAnims`` Proxy (which records move
    delegations rather than executing them). Returns stripped JavaScript.
    """
    start = engine_ts.find("export const BattleOtherAnims")
    if start == -1:
        raise ValueError("BattleOtherAnims definition not found in battle-animations.ts")
    # Both tables (and the trailing focuspunch alias) run from here to EOF.
    block = engine_ts[start:]
    block = block.replace("BattleOtherAnims", "RealOtherAnims")
    block = block.replace("BattleStatusAnims", "RealStatusAnims")
    return strip_typescript(block)


# ---------------------------------------------------------------------------
# Node.js runner template
# The __PLACEHOLDER__ markers are replaced with Python str.replace() to
# avoid conflicts with the many '{' and '}' characters in the JavaScript.
# ---------------------------------------------------------------------------

NODE_RUNNER = """\
'use strict';

// Freeze randomness so the generated JSON is reproducible across runs.
// (Several animations use Math.random() for visual jitter.)
Math.random = () => 0.5;

// Coordinate bases – must match the values in build_battle_animations.py
const ATK_X = __ATK_X__, ATK_Y = __ATK_Y__, ATK_Z = __ATK_Z__;
const DEF_X = __DEF_X__, DEF_Y = __DEF_Y__, DEF_Z = __DEF_Z__;

// ── Recording ────────────────────────────────────────────────

// Output is namespaced: { moves: {}, other: {}, status: {} }.
const results = { moves: {}, other: {}, status: {} };
let currentSection = null;
let currentMove = null;
let currentPhase = null;

function record(call) {
    if (!currentSection || !currentMove || !currentPhase) return;
    results[currentSection][currentMove][currentPhase].push(call);
}

// ── Mock sprite ────────────────────────────────────────────────────────────

function makeSprite(name, bx, by, bz, isFront) {
    const sprite = {
        x: bx, y: by, z: bz,
        isFrontSprite: isFront,
        // Tagged so effectName() can recognise a Pokémon's own sprite when it
        // is passed to showEffect (e.g. doubleteam, teleport).
        sp: { w: 96, h: 96, url: null, _spriteRef: name },

        anim(pos, transition) {
            record({ type: 'spriteAnim', sprite: name,
                     pos: pos ? { ...pos } : {},
                     transition: transition || null });
            return this;
        },
        delay(time) {
            record({ type: 'spriteDelay', sprite: name, time });
            return this;
        },
        behind(n)  { return this.z + (isFront ?  1 : -1) * n; },
        behindx(n) { return this.x + (isFront ?  1 : -1) * n; },
        behindy(n) { return this.y + (isFront ? -1 :  1) * n; },
        leftof(n)  { return this.x + (isFront ?  1 : -1) * n; },
        animReset() { return this; },
        // Stub fields accessed by some animations
        left: 0, top: 0,
    };
    return sprite;
}

const attacker = makeSprite('attacker', ATK_X, ATK_Y, ATK_Z, true);
const defender  = makeSprite('defender',  DEF_X, DEF_Y, DEF_Z, false);

// ── Mock jQuery element (returned by animateEffect) ─────────────────────

function makeFakeEl() {
    const el = {
        animate() { return this; },
        delay()   { return this; },
        queue()   { return [1]; },
        promise() { return { done(fn) { fn(); return this; } }; },
        css()     { return this; },
        remove()  { return this; },
        append()  { return this; },
    };
    return el;
}

// ── Mock BattleScene ───────────────────────────────────────────────────────

function effectName(e) {
    if (typeof e === 'string') return e;
    // A Pokémon's own sprite (attacker.sp / defender.sp) used as the effect.
    if (e && e._spriteRef) return '{' + e._spriteRef + '}';
    if (e && e.url) {
        const m = e.url.match(/\\/fx\\/([^/.]+)\\./);
        if (m) return m[1];          // fx/ particle basename
        return e.url;                // custom remote sprite (keep full URL)
    }
    return '?';
}

const scene = {
    showEffect(effect, start, end, transition, after) {
        record({ type: 'showEffect', effect: effectName(effect),
                 start: start ? { ...start } : null,
                 end:   end   ? { ...end }   : null,
                 transition: transition || null,
                 after: after || null });
        return makeFakeEl();
    },
    // animateEffect updates an existing element – record like showEffect
    animateEffect(el, effect, start, end, transition, after) {
        record({ type: 'showEffect', effect: effectName(effect),
                 start: start ? { ...start } : null,
                 end:   end   ? { ...end }   : null,
                 transition: transition || null,
                 after: after || null });
        return makeFakeEl();
    },
    backgroundEffect(color, time, opacity, delay) {
        record({ type: 'backgroundEffect',
                 color, time, opacity, delay: delay || 0 });
    },
    wait(time) {
        record({ type: 'wait', time });
    },
    waitFor()  {},
    message()  {},
    timeOffset: 0,
    battle: { sides: [], gen: 9, dex: { moves: { get: () => ({}) } } },
    // Stub jQuery-like field elements accessed by ground/weather animations
    $spritesFront: [makeFakeEl(), makeFakeEl()],
    $sprites:      [makeFakeEl(), makeFakeEl()],
    $bg:           makeFakeEl(),
    $weather:      makeFakeEl(),
    $terrain:      makeFakeEl(),
    $bgEffect:     makeFakeEl(),
};

// ── Mock BattleOtherAnims ─────────────────────────────────────────────────
// Records a delegation call without executing it.

const BattleOtherAnims = new Proxy({}, {
    get(_, name) {
        const animFn = function(s, sprites) {
            record({ type: 'otherAnim', name: String(name) });
        };
        return { anim: animFn };
    }
});

// ── Mock Config ───────────────────────────────────────────────────────────
// Config.client is accessed by some animations for background/weather state.

const Config = {
    client: {
        curSrc: null,
        bg: null,
        getBgm: () => null,
    },
    routes: {
        client: 'play.pokemonshowdown.com',
        dex: 'dex.pokemonshowdown.com',
    },
};

// ── Injected (stripped) TypeScript ────────────────────────────────────────

// Move table (battle-animations-moves.ts): references the mock BattleOtherAnims
// Proxy above, so otherAnim delegations are recorded rather than expanded.
__TS_BODY__

// Shared tables (battle-animations.ts), renamed to RealOtherAnims /
// RealStatusAnims so they don't clash with the mock Proxy.
__ENGINE_BODY__

// ── Execute all animations ────────────────────────────────────────────────

const spriteArgs = [attacker, defender];

function runTable(section, table) {
    currentSection = section;
    for (const [id, animData] of Object.entries(table)) {
        results[section][id] = { anim: [], prepareAnim: [], residualAnim: [] };
        currentMove = id;

        // Reset sprite positions before each entry
        attacker.x = ATK_X; attacker.y = ATK_Y; attacker.z = ATK_Z;
        defender.x  = DEF_X; defender.y  = DEF_Y; defender.z  = DEF_Z;

        for (const phase of ['anim', 'prepareAnim', 'residualAnim']) {
            const fn = animData[phase];
            if (!fn) continue;
            currentPhase = phase;
            try {
                if (phase === 'residualAnim') {
                    fn(scene, [attacker]);
                } else {
                    fn(scene, spriteArgs);
                }
            } catch (e) {
                results[section][id][phase].push({ type: 'error', msg: String(e) });
            }
        }
    }
}

runTable('moves', BattleMoveAnims);
runTable('other', RealOtherAnims);
runTable('status', RealStatusAnims);

currentSection = null;
currentMove = null;
process.stdout.write(JSON.stringify(results));
"""


# ---------------------------------------------------------------------------
# Node.js execution (local or Docker)
# ---------------------------------------------------------------------------

def _run_node(script_content):
    """
    Execute a Node.js script and return a CompletedProcess.
    Prefers a locally-installed 'node'; falls back to Docker.
    Node.js reads the script from stdin when invoked as 'node -'.
    """
    import shutil

    node_bin = shutil.which("node") or shutil.which("nodejs")
    if node_bin:
        print("Using local Node.js ({})...".format(node_bin))
        return subprocess.run(
            [node_bin, "-"],
            input=script_content,
            capture_output=True, text=True, timeout=120,
        )

    if not shutil.which("docker"):
        print("ERROR: neither 'node' nor 'docker' found on PATH.")
        print("Install Node.js (https://nodejs.org) or Docker (https://docker.com).")
        sys.exit(1)

    print("Node.js not found locally; using Docker (node:lts-alpine)...")
    print("(The image will be pulled automatically on first run.)")
    return subprocess.run(
        ["docker", "run", "--rm", "-i", "node:lts-alpine", "node", "-"],
        input=script_content,
        capture_output=True, text=True, timeout=300,
    )


# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------

def main():
    print("Downloading battle-animations-moves.ts...")
    ts_code = get_remote_data(TS_URL)

    print("Downloading battle-animations.ts (shared anim tables)...")
    engine_ts = get_remote_data(ENGINE_TS_URL)

    print("Stripping TypeScript syntax...")
    js_code = strip_typescript(ts_code)
    engine_js = extract_other_status_tables(engine_ts)

    # Build the Node.js runner by substituting markers
    node_script = NODE_RUNNER
    node_script = node_script.replace("__ATK_X__", str(ATK_X))
    node_script = node_script.replace("__ATK_Y__", str(ATK_Y))
    node_script = node_script.replace("__ATK_Z__", str(ATK_Z))
    node_script = node_script.replace("__DEF_X__", str(DEF_X))
    node_script = node_script.replace("__DEF_Y__", str(DEF_Y))
    node_script = node_script.replace("__DEF_Z__", str(DEF_Z))
    node_script = node_script.replace("__ENGINE_BODY__", engine_js)
    node_script = node_script.replace("__TS_BODY__", js_code)

    print("Running Node.js animation extractor (this may take a few seconds)...")
    result = _run_node(node_script)

    if result.returncode != 0:
        print("Node.js runner failed (exit {})".format(result.returncode))
        print(result.stderr[:3000])
        sys.exit(1)

    if result.stderr.strip():
        print("Node.js warnings:\n" + result.stderr[:500])

    print("Parsing output...")
    try:
        raw = json.loads(result.stdout)
    except json.JSONDecodeError as e:
        print("Failed to parse Node.js output: {}".format(e))
        print("Output snippet:", result.stdout[:500])
        sys.exit(1)

    print("Decoding coordinates...")
    decoded = decode_calls(raw)

    errors = []
    for section, entries in decoded.items():
        for entry_id, phases in entries.items():
            for calls in phases.values():
                for c in calls:
                    if c.get("type") == "error":
                        errors.append((section, entry_id, c["msg"]))

    counts = {section: len(entries) for section, entries in decoded.items()}
    print("Extracted {} moves, {} other anims, {} status anims ({} errors)".format(
        counts.get("moves", 0), counts.get("other", 0),
        counts.get("status", 0), len(errors)))
    if errors:
        print("Entries with errors:")
        for section, entry_id, msg in errors:
            print("  [{}] {}: {}".format(section, entry_id, msg))

    output_path = os.path.join(OUTPUT_DIR, OUTPUT_FILE)

    # Track which directories need to be created so we can fix their ownership later.
    abs_out_dir = os.path.abspath(OUTPUT_DIR)
    new_dirs = []
    p = abs_out_dir
    while not os.path.exists(p):
        new_dirs.append(p)
        parent = os.path.dirname(p)
        if parent == p:
            break
        p = parent

    os.makedirs(OUTPUT_DIR, exist_ok=True)
    wrote = write_into_file(output_path, json.dumps(decoded, ensure_ascii=False, indent=2))

    # When run via sudo, newly created files/dirs are owned by root.
    # Restore ownership to the real user so they remain accessible.
    sudo_user = os.environ.get("SUDO_USER")
    if sudo_user and wrote:
        import pwd
        try:
            pw = pwd.getpwnam(sudo_user)
            uid, gid = pw.pw_uid, pw.pw_gid
            for d in new_dirs:
                os.chown(d, uid, gid)
            os.chown(output_path, uid, gid)
            print("Ownership restored to {}.".format(sudo_user))
        except (KeyError, OSError) as e:
            print("Warning: could not restore ownership: {}".format(e))

    finish()


if __name__ == "__main__":
    main()
