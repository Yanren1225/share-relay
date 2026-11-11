# ShareRelay icon artwork

The icon restores the default **① suspension bridge + 澎湃蓝** from the user's [interactive reference](https://miniapp.heimaois.me/s/8L-pM9j2vjeNwaSJGe06IZGD/widget/0).

- Foreground: the original five round-stroke paths and their opacities.
- Proportions: the website's entire 108-unit tile maps into Android's central 72dp visible region via scale 2/3 about (54, 54). This preserves the website's symbol size, with the full background extending into adaptive overscan. A shared +1.15dp horizontal translation optically centers the asymmetric bridge/arrow silhouette (previous left/right margins were approximately 28.3/30.6dp); color and monochrome variants receive the same correction. Shape, scale, vertical alignment, and background remain unchanged.
- Background: CSS 145-degree linear gradient, `#38b8ff` → `#1a73ff` at 55% → `#0b43d6`.
- Glow: white at 32% alpha, centered at (36, 28.8) on the adaptive canvas, fading to transparent white at radius 31.157953dp.
- Arrow shadow: a pure-vector approximation of the website hero's `drop-shadow(0 3px 5px rgba(5,25,90,.3))` at 128px, using layered low-alpha rounded strokes with matching offset. VectorDrawable cannot express a true Gaussian blur; this effect is approximate rather than pixel-identical. The webpage's external tile shadow is intentionally left to the launcher, not baked into artwork.
- Monochrome: a separate vector exactly follows the reference's alternate geometry: no water wave or shadow, tower breaks at the deck, 68% secondary opacity. Android launchers provide the actual wallpaper-dependent theme colors; the preview uses the website's light-mode colors.

## Sources and exports

- [Foreground vector](../../app/src/main/res/drawable/ic_launcher_foreground.xml).
- [Background vector](../../app/src/main/res/drawable/ic_launcher_background.xml).
- [Monochrome vector](../../app/src/main/res/drawable/ic_launcher_monochrome.xml).
- [Display icon](share-relay-icon.png): 1024 × 1024 visible crop without a baked-in launcher mask.
- [Launcher preview](launcher-preview.png): squircle, circular, and Android themed variants.
- [Earlier generated concept](share-relay-master.png): historical reference only; not used by the app or exports.

## Regenerate PNG resources

Requires Python, Pillow, and numpy:

```sh
python tools/generate_launcher_icons.py
```

The script reads the actual Android XML and exports all five legacy launcher densities plus the README icon and previews. It supports this icon's absolute M/L/H/V/Q/T paths, round strokes, nested uniformly scaled/translated groups, layered multistop linear/radial gradients, and dedicated monochrome geometry. Assertions check both variants against adaptive cropping, circle/squircle masks, the 66dp safe circle, and the monochrome tower gaps/removed wave. Modern Android uses the vectors directly.
