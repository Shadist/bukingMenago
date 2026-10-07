from pathlib import Path

for script_name in (
    "patch_login.py",
    "patch_live_verification.py",
):
    script = Path(".github/scripts") / script_name
    exec(compile(script.read_text(encoding="utf-8"), str(script), "exec"))

print("All booking patches applied")
