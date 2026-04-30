import os
import glob

def delete_files(pattern, keep_list):
    files = glob.glob(pattern)
    print(f"Found {len(files)} files matching {pattern}")
    for f in files:
        basename = os.path.basename(f)
        if basename not in keep_list:
            print(f"Deleting {basename}")
            os.remove(f)
        else:
            print(f"Keeping {basename}")

# 1. Clean Layouts
layout_dir = r"d:\mod\keyboard\stickerboard\app\src\main\assets\layouts\main"
keep_layouts = ["qwerty.txt", "vi.txt", "pcqwerty.json"]
# Also keep colemak/dvorak if user might want them? User said "Leave Vietnamese and English". 
# English usually implies Qwerty. 
# "Việt hoá toàn bộ mene, xoá các bộ cục trong ảnh chỉ để lại tiếng việt và và Tiếng anh"
# "Xoá từ điển, chỉ để lại tiếng việt, tiếng anh"
# So strict cleanup.
delete_files(os.path.join(layout_dir, "*"), keep_layouts)

# 2. Clean Dictionaries
dict_dir = r"d:\mod\keyboard\stickerboard\app\src\main\assets\dicts"
keep_dicts = ["main_en-US.dict", "main_en-GB.dict", "main_vi.dict"]
delete_files(os.path.join(dict_dir, "*"), keep_dicts)
