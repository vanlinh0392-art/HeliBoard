import os
import xml.etree.ElementTree as ET
import sys
sys.stdout.reconfigure(encoding='utf-8')

def get_keys(path):
    try:
        tree = ET.parse(path)
        root = tree.getroot()
        keys = {}
        for child in root:
            if 'name' in child.attrib:
                keys[child.attrib['name']] = child.text
        return keys
    except Exception as e:
        print(f"Error reading {path}: {e}")
        return {}

default_path = r"d:\mod\keyboard\stickerboard\app\src\main\res\values\strings.xml"
vi_path = r"d:\mod\keyboard\stickerboard\app\src\main\res\values-vi\strings.xml"

default_keys = get_keys(default_path)
vi_keys = get_keys(vi_path)

missing = []
for key, val in default_keys.items():
    if key not in vi_keys:
        missing.append((key, val))

print(f"Found {len(missing)} missing translations.")
print("--- START MISSING ---")
for k, v in missing:
    safe_v = v if v else ""
    print(f'<string name="{k}">{safe_v}</string>')
print("--- END MISSING ---")
