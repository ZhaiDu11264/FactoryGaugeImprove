"""Checks that Create's factory gauge classes still match this mod's mixins.

The mod is built against Create but cannot be launched from the dev environment
(Create is a compileOnly dependency), so its injections cannot be exercised at
runtime here. What *can* be checked is everything Mixin resolves at load time:
that each target exists, and that the injections addressed by ordinal still
point at what they were written for. Mixin's ``require`` would turn a change
into a startup crash - this script reports it *before* the jar is installed.

The last section goes one step further and cross-checks the *annotations the
mod actually compiled to* against Create's bytecode. That is not redundant: a
redirect can name a call site that exists in the right method and still be the
wrong one - ``FactoryPanelScreen.tick`` compares ``inputConfig.size()`` against
``targetedBy.size()``, and redirecting the wrong one of those two is invisible
to a Create-side-only check while breaking the panel at runtime.

Run from the project root:  python tools/verify_target.py
"""

from __future__ import annotations

import os
import re
import subprocess
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
JAR = os.path.join(ROOT, "libs", "create-1.21.1-6.0.10.jar")

SCREEN = "com/simibubi/create/content/logistics/factoryBoard/FactoryPanelScreen.class"
HANDLER = "com/simibubi/create/content/logistics/factoryBoard/FactoryPanelConnectionHandler.class"
BEHAVIOUR = "com/simibubi/create/content/logistics/factoryBoard/FactoryPanelBehaviour.class"
CONNECTION = "com/simibubi/create/content/logistics/factoryBoard/FactoryPanelConnection.class"
BIG_ITEM_STACK = "com/simibubi/create/content/logistics/BigItemStack.class"

AMOUNT_FIELD = "Field com/simibubi/create/content/logistics/BigItemStack.count:I"
AMOUNT_FIELD_DESC = "Lcom/simibubi/create/content/logistics/BigItemStack;count:I"
INPUT_CONFIG = "Field inputConfig:Ljava/util/List;"
OUTPUT_CONFIG = "Field outputConfig:Lcom/simibubi/create/content/logistics/BigItemStack;"
LIST_SIZE = "InterfaceMethod java/util/List.size:()I"
LIST_SIZE_DESC = "Ljava/util/List;size()I"
MAP_SIZE = "InterfaceMethod java/util/Map.size:()I"
MAP_SIZE_DESC = "Ljava/util/Map;size()I"
MAP_PUT = "InterfaceMethod java/util/Map.put:"

MIXIN_PACKAGE = "ho/artisan/factorygaugeimprove/mixin"

MODIFIERS = {"public", "private", "protected", "static", "final", "abstract",
             "synchronized", "native", "default", "strictfp", "transient", "volatile"}

def _jdks() -> list[str]:
    """JDKs to try, in order: JAVA_HOME, every toolchain Gradle has downloaded
    (~/.gradle/jdks/<vendor>-<version>-<arch>-<os>/), then whatever is on PATH.
    Nothing machine-specific is hard-coded - a fresh clone resolves the same way
    here as it does on the machine that built the jar."""
    found = [os.environ.get("JAVA_HOME", "")]
    gradle_jdks = os.path.expanduser("~/.gradle/jdks")
    if os.path.isdir(gradle_jdks):
        found += sorted(os.path.join(gradle_jdks, d) for d in os.listdir(gradle_jdks))
    return [d for d in found if d]

RESULTS: list[tuple[str, bool, str]] = []


def check(what: str, ok: bool, detail: str = "") -> bool:
    RESULTS.append((what, bool(ok), detail))
    return bool(ok)


def find_javap() -> str:
    for jdk in _jdks():
        for exe in ("javap.exe", "javap"):
            path = os.path.join(jdk, "bin", exe)
            if os.path.exists(path):
                return path
    return "javap"          # last resort: whatever the PATH offers


def dump(entry: str) -> str:
    """Disassemble one class out of Create's jar and return the javap output."""
    out_dir = os.path.join(ROOT, "build", "_verify")
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, os.path.basename(entry))
    with zipfile.ZipFile(JAR) as jar:
        with jar.open(entry) as source, open(path, "wb") as target:
            target.write(source.read())
    text = subprocess.run([find_javap(), "-p", "-c", path],
                          capture_output=True, text=True, errors="replace").stdout
    if not text:
        raise SystemExit(f"javap produced no output for {entry} - is a JDK on PATH?")
    return text


def mixin_source(name: str) -> str:
    """Verbose disassembly of one of the mod's own compiled mixin classes."""
    path = os.path.join(ROOT, "build", "classes", "java", "main", MIXIN_PACKAGE, f"{name}.class")
    if not os.path.exists(path):
        return ""
    return subprocess.run([find_javap(), "-v", "-p", "-classpath", JAR, path],
                          capture_output=True, text=True, errors="replace").stdout


MOD_CLASSES = os.path.join(ROOT, "build", "classes", "java", "main")


def mod_class(*parts: str) -> str:
    """Plain disassembly of one of the mod's own compiled classes."""
    path = os.path.join(MOD_CLASSES, *parts)
    if not os.path.exists(path):
        return ""
    return subprocess.run([find_javap(), "-p", "-c", path],
                          capture_output=True, text=True, errors="replace").stdout


def annotation_blocks(verbose: str, simple_name: str) -> list[dict[str, str]]:
    """Every Mixin annotation of one kind, as a flat {member: value} mapping.

    javap prints the annotation and its nested ``@At`` as an indented tree; the
    members of both are flat ``key=value`` lines, so they are collected together
    and the depth is tracked by counting parentheses.
    """
    marker = f"org.spongepowered.asm.mixin.injection.{simple_name}("
    lines = verbose.splitlines()
    found: list[dict[str, str]] = []
    for index, line in enumerate(lines):
        if marker not in line:
            continue

        entry = {"method": "", "target": "", "ordinal": "", "value": "", "require": ""}
        depth = 1
        for follow in lines[index + 1:]:
            stripped = follow.strip()
            key, separator, value = stripped.partition("=")
            if separator and key in entry:
                entry[key] = value.strip()
            depth += stripped.count("(") - stripped.count(")")
            if depth <= 0:
                break
        found.append(entry)
    return found


def redirects(verbose: str) -> list[dict[str, str]]:
    return annotation_blocks(verbose, "Redirect")


def unwrap(value: str) -> str:
    """``["tick"]`` (javap's array form) and ``"tick"`` both become ``tick``."""
    return value.strip().strip("[]").strip().strip('"')


def invocation_descriptor(instruction: str) -> str:
    """``InterfaceMethod java/util/List.size:()I`` -> ``Ljava/util/List;size()I``."""
    match = re.search(r"Method ([\w/$]+)\.([\w$<>]+):(\([^)]*\)[\w/;\[]+)", instruction)
    if not match:
        return ""
    owner, name, descriptor = match.groups()
    return f"L{owner};{name}{descriptor}"


class ClassFile:
    def __init__(self, entry: str = "", text: str = "") -> None:
        self.text = text or dump(entry)
        self.methods: dict[str, list[str]] = {}
        self.fields: dict[str, str] = {}
        self._parse()

    def _parse(self) -> None:
        current: str | None = None
        for line in self.text.splitlines():
            instruction = re.match(r"^\s+\d+: (\S+)(.*)$", line)
            if instruction:
                if current is not None:
                    self.methods[current].append(instruction.group(1) + instruction.group(2))
                continue
            if not line.startswith("  ") or line.startswith("   "):
                continue

            signature = re.match(r"^  (.+?)\(([^()]*)\);\s*(?:throws .+)?$", line)
            if signature:
                tokens = [t for t in signature.group(1).split() if t not in MODIFIERS]
                if not tokens:
                    continue
                name = tokens[-1]
                return_type = " ".join(tokens[:-1]) or "<init>"
                current = f"{return_type} {name}({signature.group(2)})"
                self.methods.setdefault(current, [])
                continue

            field = re.match(r"^  (.+);$", line)
            if field:
                tokens = field.group(1).split()
                if len(tokens) < 2:
                    continue
                self.fields[tokens[-1]] = " ".join(t for t in tokens[:-1] if t not in MODIFIERS)

    def method(self, name: str, arg_count: int | None = None) -> list[str] | None:
        for key, body in self.methods.items():
            match = re.match(r"^(.*?) ([\w$<>]+)\(([^)]*)\)$", key)
            if not match or match.group(2) != name:
                continue
            args = [a for a in match.group(3).split(",") if a.strip()]
            if arg_count is not None and len(args) != arg_count:
                continue
            return body
        return None

    def keys(self, name: str) -> list[str]:
        return [key for key in self.methods
                if re.match(rf"^.*? {re.escape(name)}\(", key)]

    @staticmethod
    def window(body: list[str], index: int, back: int = 30) -> str:
        return " ".join(body[max(0, index - back):index])


def main() -> int:
    if not os.path.exists(JAR):
        print(f"missing {JAR} - copy Create's jar into libs/ first")
        return 2

    screen = ClassFile(SCREEN)

    # ---- fields the mixin shadows ---------------------------------------
    for name in ("inputConfig", "connections"):
        check(f"FactoryPanelScreen.{name} is a List", "List" in screen.fields.get(name, ""),
              screen.fields.get(name, "missing"))
    for name in ("craftingActive", "restocker"):
        check(f"FactoryPanelScreen.{name} is a boolean", screen.fields.get(name, "") == "boolean",
              screen.fields.get(name, "missing"))

    # ---- mouseScrolled: two amount stores, inputs first, output second ----
    scroll = screen.method("mouseScrolled", 4)
    if check("FactoryPanelScreen.mouseScrolled(double x4) exists", scroll is not None):
        stores = [i for i, line in enumerate(scroll)
                  if line.startswith("putfield") and AMOUNT_FIELD in line]
        check("mouseScrolled stores the amount exactly twice", len(stores) == 2, f"found {len(stores)}")
        if len(stores) == 2:
            check("ordinal 0 reads inputConfig (the input cells)",
                  INPUT_CONFIG in ClassFile.window(scroll, stores[0]))
            check("ordinal 1 reads outputConfig (the expected output)",
                  OUTPUT_CONFIG in ClassFile.window(scroll, stores[1]))

    # ---- tick: exactly one List.size(), the inputConfig length check -----
    tick = screen.method("tick", 0)
    if check("FactoryPanelScreen.tick() exists", tick is not None):
        sizes = [i for i, line in enumerate(tick) if LIST_SIZE in line]
        check("tick has exactly one List.size() to redirect", len(sizes) == 1, f"found {len(sizes)}")
        if len(sizes) == 1:
            check("that size() is read off inputConfig",
                  INPUT_CONFIG in ClassFile.window(tick, sizes[0], 6))
        check("tick compares it against targetedBy.size()", MAP_SIZE in " ".join(tick))

    # ---- sendIt: exactly one Map.put, the amount per connection ----------
    send_it = screen.method("sendIt", 2)
    if check("FactoryPanelScreen.sendIt(Position, boolean) exists", send_it is not None):
        puts = [i for i, line in enumerate(send_it) if MAP_PUT in line]
        check("sendIt has exactly one Map.put() to redirect", len(puts) == 1, f"found {len(puts)}")

    # ---- updateConfigs: the layout injection point -----------------------
    update_configs = screen.method("updateConfigs", 0)
    if check("FactoryPanelScreen.updateConfigs() exists", update_configs is not None):
        check("updateConfigs ends in a return (TAIL injection point)",
              any(line.startswith("return") for line in update_configs))

    # ---- the connection guard -------------------------------------------
    handler = ClassFile(HANDLER)
    guards = [key for key in handler.keys("checkForIssues")
              if key.count("FactoryPanelBehaviour") == 2]
    check("FactoryPanelConnectionHandler.checkForIssues(Behaviour, Behaviour) exists",
          len(guards) == 1, f"found {len(guards)}")
    check("checkForIssues is the String-returning overload",
          any(key.startswith("java.lang.String ") for key in guards))

    behaviour = ClassFile(BEHAVIOUR)
    check("FactoryPanelBehaviour.targetedBy stays readable", "targetedBy" in behaviour.fields,
          behaviour.fields.get("targetedBy", "missing"))

    connection = ClassFile(CONNECTION)
    check("FactoryPanelConnection.amount stays readable", connection.fields.get("amount") == "int",
          connection.fields.get("amount", "missing"))

    # ---- BigItemStack: what the layout builds cells from -----------------
    big = ClassFile(BIG_ITEM_STACK)
    check("BigItemStack.stack and .count stay readable",
          "stack" in big.fields and big.fields.get("count") == "int")
    check("BigItemStack(ItemStack, int) exists",
          re.search(r"BigItemStack\(net\.minecraft\.world\.item\.ItemStack, int\);", big.text) is not None)

    # ---- the mixin's own annotations, against Create's call sites ---------
    # Create's side alone cannot tell a correct redirect from one aimed at a
    # different call of the same method, so the two are compared directly.
    verbose = mixin_source("FactoryPanelScreenMixin")
    if check("the mod's mixin class is built", bool(verbose), "run gradlew.bat build first"):
        annotations = redirects(verbose)
        tick_redirects = [a for a in annotations if unwrap(a["method"]) == "tick"]
        check("tick has exactly one redirect", len(tick_redirects) == 1, f"found {len(tick_redirects)}")

        expected = ""
        if tick:
            for i, line in enumerate(tick):
                if line.startswith("getfield") and INPUT_CONFIG in line and i + 1 < len(tick):
                    expected = invocation_descriptor(tick[i + 1])
                    break
        check("Create's tick still calls a method on inputConfig right after reading it",
              bool(expected), "no instruction follows the inputConfig read")
        check("the tick redirect aims at that exact call",
              bool(tick_redirects) and unwrap(tick_redirects[0]["target"]) == expected,
              f"mixin says {unwrap(tick_redirects[0]['target']) if tick_redirects else '?'}, "
              f"Create has {expected or '?'}")
        check("the tick redirect is not aimed at targetedBy's Map.size()",
              bool(tick_redirects) and unwrap(tick_redirects[0]["target"]) != MAP_SIZE_DESC,
              "the panel would rebuild its layout every tick")

        scroll_redirects = [a for a in annotations
                            if unwrap(a["method"]) == "mouseScrolled(DDDD)Z"
                            and unwrap(a["target"]) == AMOUNT_FIELD_DESC]
        check("mouseScrolled has two redirects on BigItemStack.count",
              len(scroll_redirects) == 2, f"found {len(scroll_redirects)}")
        ordinals = sorted(unwrap(a["ordinal"]) for a in scroll_redirects)
        check("and they are addressed as ordinal 0 and 1", ordinals == ["0", "1"], str(ordinals))

        send_redirects = [a for a in annotations if unwrap(a["method"]) == "sendIt"
                          and unwrap(a["target"]).startswith("Ljava/util/Map;put(")]
        check("sendIt has one redirect on Map.put()", len(send_redirects) == 1,
              f"found {len(send_redirects)}")

        # The wheel delta is captured at HEAD and is what every direction is read
        # from; without it the amount vanilla clamps back to 64 looks like no
        # scrolling at all, and nothing can ever spill past a full cell.
        head_injects = [a for a in annotation_blocks(verbose, "Inject")
                        if unwrap(a["method"]) == "mouseScrolled(DDDD)Z"
                        and unwrap(a["value"]) == "HEAD"]
        check("mouseScrolled captures the wheel delta at HEAD", len(head_injects) == 1,
              f"found {len(head_injects)}")

        # The layout is kept up to date from hooks that are exercised constantly
        # while the panel is open, because a hook on updateConfigs alone is not
        # enough: several Create addons reshape that method, and an injection that
        # loses that fight does nothing and says nothing. Each hook is asserted
        # here so none of them can be dropped by a later edit.
        tick_head = [a for a in annotation_blocks(verbose, "Inject")
                     if unwrap(a["method"]) in ("tick", "tick()V")
                     and unwrap(a["value"]) == "HEAD"]
        check("tick refreshes the grid layout at HEAD", len(tick_head) == 1,
              f"found {len(tick_head)}")

        configs_hooks = [a for a in annotation_blocks(verbose, "Inject")
                         if unwrap(a["method"]) == "updateConfigs"]
        check("updateConfigs is hooked exactly once", len(configs_hooks) == 1,
              f"found {len(configs_hooks)}")
        check("and that hook is optional (require = 0)",
              bool(configs_hooks) and unwrap(configs_hooks[0]["require"]) == "0",
              "a required hook there turns another mod reshaping the method into a startup crash")

    # ---- the request ceiling is a knob of its own -------------------------
    # The "expected output" slot is a single number, not a grid, so it is not
    # bounded by the input side: its ceiling is limits.maxRequestedOutput, 46656
    # by default. Aiming that redirect back at maxAmount() would quietly cap a
    # request at one package again - the exact behaviour this lift exists for -
    # and nothing else in the mod would notice.
    own = ClassFile(text=mod_class(*MIXIN_PACKAGE.split("/"), "FactoryPanelScreenMixin.class"))
    output_body = own.method("factorygaugeimprove$scrollOutputAmount", 2) or []
    if check("the output slot redirect is built", bool(output_body), "run gradlew.bat build first"):
        check("the output slot does not fall back on the grid's ceiling",
              "FactoryGaugeImprove.maxAmount:" not in " ".join(output_body),
              "the output slot is not a grid, so the grid's ceiling must not bound it")

        # The read that feeds the notch itself, not the one the log line names:
        # reverting only the amount argument to maxAmount() would still print the
        # right ceiling in the log, so the call site is what has to be checked.
        call = next((i for i, instruction in enumerate(output_body)
                     if "AmountStepping.next" in instruction), -1)
        check("and the notch itself is clamped by the request ceiling",
              call >= 0 and "maxRequestedOutput:()I" in ClassFile.window(output_body, call),
              "the redirect has to read limits.maxRequestedOutput where it computes the amount")

    config = ClassFile(text=mod_class("ho", "artisan", "factorygaugeimprove", "FactoryGaugeImprove.class"))
    check("the request ceiling is read from the config",
          "maxRequestedOutput" in config.text, "no accessor for limits.maxRequestedOutput")
    check("the built-in request ceiling is 46656",
          re.search(r"//\s*int 46656\b", config.text) is not None,
          "the default has to be a real int constant, not only a config comment")

    # ---- report ----------------------------------------------------------
    failed = 0
    print(f"verifying against {os.path.basename(JAR)}\n")
    for what, ok, detail in RESULTS:
        if ok:
            print(f"  ok    {what}")
        else:
            failed += 1
            print(f"  FAIL  {what}{'  <- ' + detail if detail else ''}")
    print()
    if failed:
        print(f"FAIL - {failed} of {len(RESULTS)} contracts broken.")
        print("Either Create's target moved (re-aim the injection) or one of the mod's own")
        print("annotations names the wrong call site - the FAIL lines say which is which.")
        return 1
    print(f"PASS - all {len(RESULTS)} bytecode contracts hold")
    return 0


if __name__ == "__main__":
    sys.exit(main())
