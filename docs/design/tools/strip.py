# Concatenate phone screenshots side by side so several boards can be reviewed in one image.
import sys
from PIL import Image
out, *paths = sys.argv[1:]
ims = [Image.open(p).convert('RGB') for p in paths]
gap = 16
w = sum(i.width for i in ims) + gap * (len(ims) - 1)
h = max(i.height for i in ims)
canvas = Image.new('RGB', (w, h), (200, 200, 205))
x = 0
for i in ims:
    canvas.paste(i, (x, 0)); x += i.width + gap
canvas.save(out)
