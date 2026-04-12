import base64
import re
from io import BytesIO
from PIL import Image
import numpy as np
from collections import deque

# Read the SVG and extract base64 image data
with open('F:/My Projects/ClawBerry/zeroclaw.svg', 'r') as f:
    content = f.read()

# Find base64 image data
match = re.search(r'data:image/[^;]+;base64,([A-Za-z0-9+/=\s]+)', content)
if not match:
    print('No base64 image found in SVG')
    exit(1)

b64 = match.group(1).replace('\n','').replace(' ','').replace('\r','').strip()
img_data = base64.b64decode(b64)
img = Image.open(BytesIO(img_data)).convert('RGBA')
print(f'Extracted original PNG: {img.size}, mode={img.mode}')

data = np.array(img, dtype=np.int32)
h, w = data.shape[:2]
result = np.array(img).copy()
visited = np.zeros((h, w), dtype=bool)

def is_bg(pixel):
    r, g, b, a = pixel
    if a < 10:
        return False
    # Neutral grey/white background: all channels close to each other and relatively bright
    return (abs(r - g) < 25 and abs(g - b) < 25 and abs(r - b) < 25 and r > 70)

# Seed flood fill from all 4 edges
queue = deque()
for x in range(w):
    for y in [0, h-1]:
        if not visited[y, x]:
            queue.append((y, x))
            visited[y, x] = True
for y in range(h):
    for x in [0, w-1]:
        if not visited[y, x]:
            queue.append((y, x))
            visited[y, x] = True

count = 0
while queue:
    y, x = queue.popleft()
    if is_bg(data[y, x]):
        result[y, x] = [0, 0, 0, 0]
        count += 1
        for dy, dx in [(-1,0),(1,0),(0,-1),(0,1)]:
            ny, nx = y+dy, x+dx
            if 0 <= ny < h and 0 <= nx < w and not visited[ny, nx]:
                visited[ny, nx] = True
                queue.append((ny, nx))

print(f'Removed {count} background pixels')

# Upscale to 512x512 to match picoclaw quality
out = Image.fromarray(result)
out_hires = out.resize((512, 512), Image.LANCZOS)

dst = 'F:/My Projects/ClawBerry/app/src/main/res/drawable/ic_zeroclaw.png'
out_hires.save(dst, optimize=True)
print(f'Saved 512x512 transparent icon to {dst}')
