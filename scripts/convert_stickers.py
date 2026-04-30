import os
import sys
from pathlib import Path
from PIL import Image

def convert_to_webp(root_dir):
    print(f"Scanning {root_dir}...")
    count = 0
    saved_space = 0
    
    for subdir, dirs, files in os.walk(root_dir):
        for file in files:
            file_path = Path(subdir) / file
            if file_path.suffix.lower() in ['.jpg', '.jpeg', '.png']:
                try:
                    # Open image
                    with Image.open(file_path) as img:
                        # Create new path with .webp extension
                        new_path = file_path.with_suffix('.webp')
                        
                        # Calculate original size
                        original_size = file_path.stat().st_size
                        
                        # Save as WebP
                        img.save(new_path, 'WEBP', quality=85)
                        
                        # Calculate new size
                        new_size = new_path.stat().st_size
                        
                        # Delete original
                        os.remove(file_path)
                        
                        saved = original_size - new_size
                        saved_space += saved
                        count += 1
                        print(f"Converted: {file} -> {new_path.name} (Saved {saved/1024:.2f} KB)")
                        
                except Exception as e:
                    print(f"Error converting {file}: {e}")

    print(f"\nDone! Converted {count} images.")
    print(f"Total space saved: {saved_space/1024/1024:.2f} MB")

if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("Usage: python convert_stickers.py <directory>")
    else:
        convert_to_webp(sys.argv[1])
