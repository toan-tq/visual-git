#!/usr/bin/env python3
"""Generate a macOS-style GitLog app icon (1024x1024 PNG).

Git commit graph on a dark background — commit dots connected by lines,
with a branch fork, on a purple-blue rounded-square background.
"""

from PIL import Image, ImageDraw, ImageFilter
import math, os

SIZE = 1024
PAD = 80

img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))


def lerp(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def gradient_rrect(size, bbox, radius, top_rgb, bot_rgb, alpha=255):
    x1, y1, x2, y2 = bbox
    layer = Image.new("RGBA", size, (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    for y in range(y1, y2 + 1):
        t = (y - y1) / max(y2 - y1, 1)
        c = lerp(top_rgb, bot_rgb, t)
        d.line([(x1, y), (x2, y)], fill=(*c, alpha))
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle(bbox, radius=radius, fill=255)
    layer.putalpha(Image.composite(layer.getchannel("A"), Image.new("L", size, 0), mask))
    return layer


# ── Background ───────────────────────────────────────────────────────────────

bg = gradient_rrect(
    (SIZE, SIZE),
    [PAD, PAD, SIZE - PAD, SIZE - PAD],
    radius=185,
    top_rgb=(45, 30, 90),
    bot_rgb=(25, 15, 60),
)
img = Image.alpha_composite(img, bg)
draw = ImageDraw.Draw(img)


# ── Git graph ────────────────────────────────────────────────────────────────

# Main branch (left column) and feature branch (right column)
COL_MAIN = 340        # x position of main branch
COL_FEAT = 580        # x position of feature branch
LINE_W = 8            # line thickness
DOT_R = 28            # commit dot radius
DOT_RING = 6          # dot ring thickness

# Commit positions (y coordinates) — top to bottom
commits_main = [210, 370, 530, 690, 850]
commits_feat = [370, 530]  # branch off from main[1], merge at main[3]

# Colors
MAIN_COLOR = (100, 200, 255)     # cyan-blue for main branch
FEAT_COLOR = (255, 130, 180)     # pink for feature branch
MERGE_COLOR = (180, 130, 255)    # purple for merge
LINE_ALPHA = 200
DOT_ALPHA = 255


def draw_thick_line(d, p1, p2, color, width):
    d.line([p1, p2], fill=(*color, LINE_ALPHA), width=width)


def draw_curve(img, x1, y1, x2, y2, color, width):
    """Draw a smooth bezier-like curve between two points."""
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    # Approximate bezier with line segments
    steps = 30
    points = []
    for i in range(steps + 1):
        t = i / steps
        # Cubic bezier: start→(x1,mid)→(x2,mid)→end
        mid_y = (y1 + y2) / 2
        bx = (1-t)**3 * x1 + 3*(1-t)**2*t * x1 + 3*(1-t)*t**2 * x2 + t**3 * x2
        by = (1-t)**3 * y1 + 3*(1-t)**2*t * mid_y + 3*(1-t)*t**2 * mid_y + t**3 * y2
        points.append((int(bx), int(by)))
    for i in range(len(points) - 1):
        d.line([points[i], points[i+1]], fill=(*color, LINE_ALPHA), width=width)
    return Image.alpha_composite(img, layer)


def draw_commit_dot(img, cx, cy, color, is_merge=False):
    layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)

    # Glow
    glow = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([cx - DOT_R - 12, cy - DOT_R - 12, cx + DOT_R + 12, cy + DOT_R + 12],
               fill=(*color, 50))
    glow = glow.filter(ImageFilter.GaussianBlur(10))
    img = Image.alpha_composite(img, glow)

    # Outer ring
    d.ellipse([cx - DOT_R, cy - DOT_R, cx + DOT_R, cy + DOT_R],
              fill=(*color, DOT_ALPHA))

    # Inner fill (darker)
    inner_r = DOT_R - DOT_RING
    inner_color = tuple(max(0, c - 60) for c in color)
    d.ellipse([cx - inner_r, cy - inner_r, cx + inner_r, cy + inner_r],
              fill=(*inner_color, DOT_ALPHA))

    # Highlight
    hl_r = DOT_R - DOT_RING - 4
    d.arc([cx - hl_r - 2, cy - hl_r - 2, cx + hl_r - 6, cy + hl_r - 6],
          start=200, end=300, fill=(*[min(255, c + 80) for c in color], 120), width=3)

    return Image.alpha_composite(img, layer)


# ── Draw lines first (behind dots) ──────────────────────────────────────────

lines_layer = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
ld = ImageDraw.Draw(lines_layer)

# Main branch vertical line
for i in range(len(commits_main) - 1):
    draw_thick_line(ld, (COL_MAIN, commits_main[i]), (COL_MAIN, commits_main[i+1]),
                    MAIN_COLOR, LINE_W)

# Branch-off curve: main[1] → feat[0]
img = Image.alpha_composite(img, lines_layer)
img = draw_curve(img, COL_MAIN, commits_main[1], COL_FEAT, commits_feat[0], FEAT_COLOR, LINE_W)

# Feature branch vertical line
feat_line = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
fd = ImageDraw.Draw(feat_line)
draw_thick_line(fd, (COL_FEAT, commits_feat[0]), (COL_FEAT, commits_feat[1]),
                FEAT_COLOR, LINE_W)
img = Image.alpha_composite(img, feat_line)

# Merge curve: feat[1] → main[3]
img = draw_curve(img, COL_FEAT, commits_feat[1], COL_MAIN, commits_main[3], FEAT_COLOR, LINE_W)


# ── Message bars (to the right of main dots) ────────────────────────────────

draw = ImageDraw.Draw(img)
BAR_X = COL_FEAT + 80
BAR_H = 12
BAR_R = 6
bar_widths = [220, 160, 0, 180, 140]  # 0 = skip (merge commit area)
bar_colors = [
    (100, 200, 255, 100),   # main color
    (100, 200, 255, 100),
    None,
    (180, 130, 255, 100),   # merge
    (100, 200, 255, 100),
]

for i, (y, w) in enumerate(zip(commits_main, bar_widths)):
    if w <= 0 or bar_colors[i] is None:
        continue
    draw.rounded_rectangle(
        [BAR_X, y - BAR_H // 2, BAR_X + w, y + BAR_H // 2],
        radius=BAR_R, fill=bar_colors[i]
    )

# Feature branch message bars
feat_bar_widths = [140, 180]
for i, (y, w) in enumerate(zip(commits_feat, feat_bar_widths)):
    draw.rounded_rectangle(
        [COL_FEAT + 55, y - BAR_H // 2, COL_FEAT + 55 + w, y + BAR_H // 2],
        radius=BAR_R, fill=(255, 130, 180, 100)
    )


# ── Draw commit dots (on top) ───────────────────────────────────────────────

for i, y in enumerate(commits_main):
    color = MERGE_COLOR if i == 3 else MAIN_COLOR
    img = draw_commit_dot(img, COL_MAIN, y, color, is_merge=(i == 3))

for y in commits_feat:
    img = draw_commit_dot(img, COL_FEAT, y, FEAT_COLOR)


# ── Save ─────────────────────────────────────────────────────────────────────

out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gitlog-icon.png")
img.save(out_path, "PNG")
print(f"Saved {out_path} ({os.path.getsize(out_path) // 1024} KB)")
