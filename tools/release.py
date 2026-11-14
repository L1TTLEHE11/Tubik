#!/usr/bin/env python3
"""
Публикация новой версии Тюбика для встроенных обновлений.

    ./gradlew assembleRelease
    python3 tools/release.py "Что нового 1" "Что нового 2"

1. читает versionCode / versionName из app/build.gradle.kts;
2. копирует app-release.apk в apk/Tubik-<версия>.apk
   (держит 3 последних: пока кэши зеркал обновляются, уже установленные
   приложения могут качать предыдущий файл, он не должен пропасть);
3. пишет update.json (версия, путь, SHA-256, размер, дата, что нового);
4. обновляет ссылку на APK и SHA-256 в README.md.
Потом: git add -A && git commit && git push и сброс кэша jsDelivr (команда ниже).
"""
import datetime, hashlib, json, pathlib, re, shutil, sys

root = pathlib.Path(__file__).resolve().parent.parent
gradle = (root / "app/build.gradle.kts").read_text(encoding="utf-8")
code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)
notes = sys.argv[1:] or ["Улучшения и исправления"]

src = root / "app/build/outputs/apk/release/app-release.apk"
if not src.exists():
    sys.exit("Сначала собери: ./gradlew assembleRelease")

apk_dir = root / "apk"
apk_dir.mkdir(exist_ok=True)
dst = apk_dir / f"Tubik-{name}.apk"

def ver_key(p):
    return [int(x) for x in re.findall(r"\d+", p.name)]

others = sorted((p for p in apk_dir.glob("Tubik-*.apk") if p.name != dst.name), key=ver_key)
for old in others[: max(0, len(others) - 2)]:
    old.unlink()
shutil.copyfile(src, dst)
data = dst.read_bytes()
sha = hashlib.sha256(data).hexdigest()

info = {
    "versionCode": code,
    "versionName": name,
    "apk": f"apk/{dst.name}",
    "sha256": sha,
    "size": len(data),
    "date": datetime.date.today().strftime("%d.%m.%Y"),
    "notes": notes,
    "notes_ru": notes,
}
(root / "update.json").write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

readme = root / "README.md"
if readme.exists():
    t = readme.read_text(encoding="utf-8")
    t = re.sub(r"Tubik-[\d.]+\.apk", dst.name, t)
    t = re.sub(r"(SHA-256: `)[0-9a-f]{64}(`)", rf"\g<1>{sha}\g<2>", t)
    t = re.sub(r"(Версия: \*\*)[^*]+(\*\*)", rf"\g<1>{name}\g<2>", t)
    readme.write_text(t, encoding="utf-8")

print(f"✓ {dst.name}  ({len(data) / 1048576:.2f} МБ)\n  versionCode {code}, SHA-256 {sha}\n  update.json и README.md обновлены")
print("После git push сбрось кэш jsDelivr:\n  curl -s https://purge.jsdelivr.net/gh/L1TTLEHE11/Tubik@main/update.json")
