"""Generate the Windows and browser icons from the Sootan square/slope geometry."""
from pathlib import Path
import sys
from PIL import Image, ImageDraw

root = Path(__file__).resolve().parents[2]
out = root / "packaging/windows/assets"
out.mkdir(exist_ok=True)
image = Image.new("RGBA", (512, 512), (0, 0, 0, 0))
draw = ImageDraw.Draw(image)
draw.rectangle((43, 43, 469, 469), fill="#286447")
draw.polygon([(107, 469), (469, 149), (469, 469)], fill="#727d88")
image.save(out / "sootan.png")
image.save(out / "sootan.ico", sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
brand = root / "frontend/src/editor/core/assets/brand/modern-logo"
image.resize((192,192), Image.Resampling.LANCZOS).save(brand / "logo192.png")
image.save(brand / "logo512.png")
(brand / "favicon.ico").write_bytes((out / "sootan.ico").read_bytes())
