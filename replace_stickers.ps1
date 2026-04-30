$stickersDir = "d:\mod\keyboard\stickerboard\app\src\main\assets\stickers"
$downloadsDir = "C:\Users\UM880 Pro\Downloads"
$zips = @(
    "Memeisphong_batch_jpeg.zip",
    "MonoMemeee_batch_jpeg.zip",
    "Oldmasters_batch_jpeg.zip",
    "PePeFroGi_batch_jpeg.zip",
    "PEPEtop_batch_jpeg.zip"
)

# Clean up stickers directory
if (Test-Path $stickersDir) {
    Remove-Item -Path "$stickersDir\*" -Recurse -Force
} else {
    New-Item -ItemType Directory -Path $stickersDir
}

foreach ($zip in $zips) {
    $zipPath = Join-Path $downloadsDir $zip
    $packName = $zip -replace "_batch_jpeg.zip", ""
    $tempDir = Join-Path "d:\mod\keyboard\stickerboard" ("temp_" + $packName)
    
    Write-Host "Processing $packName..."
    
    # Extract
    Expand-Archive -Path $zipPath -DestinationPath $tempDir -Force
    
    # Find the inner directory (assuming one top level dir)
    $innerDir = Get-ChildItem -Path $tempDir -Directory | Select-Object -First 1
    
    if ($innerDir) {
        $destDir = Join-Path $stickersDir $packName
        Move-Item -Path $innerDir.FullName -Destination $destDir
        Write-Host "Moved to $destDir"
    } else {
        Write-Error "No directory found in $zip"
    }
    
    # Cleanup temp
    Remove-Item -Path $tempDir -Recurse -Force
}

# Cleanup previous temp checks
if (Test-Path "d:\mod\keyboard\stickerboard\temp_extract_check") { Remove-Item -Path "d:\mod\keyboard\stickerboard\temp_extract_check" -Recurse -Force }
if (Test-Path "d:\mod\keyboard\stickerboard\temp_extract_check_2") { Remove-Item -Path "d:\mod\keyboard\stickerboard\temp_extract_check_2" -Recurse -Force }
